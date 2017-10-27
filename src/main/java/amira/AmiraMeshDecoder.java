package amira;

import ij.IJ;
import ij.ImageStack;

import java.awt.image.ColorModel;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class AmiraMeshDecoder {
	private int width,height,numSlices;
	private int mode;
	final public int RAW = 0;
	final public int RLE = 1;
	final public int ZLIB = 2;
	final public int ASCII = 3;

	public AmiraParameters parameters;

	private RandomAccessFile file;
	private long endOffsetOfPreamble;
	private String line;

	/** flag for short/ushort data files */
	boolean shortData = false;
	/** endianness */
	ByteOrder byteOrder = ByteOrder.nativeOrder();

	// RLE
	private byte[] rleOverrun;
	private int rleOverrunLength;
	private InflaterInputStream zStream;
	private BufferedInputStream in;
	private int zLength;

	// ASCII
	private String fileName;
	private String[] colFormat;
	private String[] colName;

	public void AmiraMeshDecoder() {
		width=height=numSlices=-1;
		endOffsetOfPreamble=-1;
		rleOverrunLength=0;
	}

	public boolean open(String fileName) {
		try {
			File file1 = new File(fileName);
			file=new RandomAccessFile(file1, "r");
			this.fileName = file1.getName();

			Pattern latticePattern=Pattern.compile("define Lattice ([0-9]+) ([0-9]+) ([0-9]+).*");

			/* The AmiraMesh file format documentation:

			    https://amira.zib.de/usersguide31/amiramesh/HxFileFormat_AmiraMesh.html

			   ... states that, "The first line of an
			   AmiraMesh file should be a special comment
			   including the identifier AmiraMesh."  It's
			   not clear how strong the use of "should" is
			   in this very informal documentation, but
			   (a) we get into trouble by trying to parse
			   non-AmiraMesh files and (b) Amira itself
			   requires that the first line is a comment
			   of that form, so we enforce that here. */

			boolean firstLine=true;
			Pattern firstLinePattern=Pattern.compile("^\\s*#.*AmiraMesh.*$");

			while(true) {
				IJ.showStatus( "Reading AmiraMesh file header..." );
				if(!readPreambleLine())
					return false;
				if(firstLine) {
					Matcher firstLineMatcher=firstLinePattern.matcher(line);

					if( !firstLineMatcher.matches() && line.startsWith( "# Avizo" ) == false ) {
						throw new Exception("This doesn't look like an AmiraMesh file; the first line must be a comment containing the text 'AmiraMesh' or 'Avizo'.");
					}
					firstLine=false;
					if( line.contains( "LITTLE-ENDIAN" ) )
						byteOrder = ByteOrder.LITTLE_ENDIAN;
					if( line.contains( "BIG-ENDIAN" ) )
						byteOrder = ByteOrder.BIG_ENDIAN;
				}
				Matcher m=latticePattern.matcher(line);
				if(m.matches()) {
					width=Integer.decode(m.group(1)).intValue();
					height=Integer.decode(m.group(2)).intValue();
					numSlices=Integer.decode(m.group(3)).intValue();
					break;
				}
				if(line.startsWith("# AmiraMesh 3D ASCII")) {
					mode = ASCII;
					break;
				}
			}

			while(readPreambleLine() &&
					(line.length() < 11 ||
					 !line.substring(0,10).equals("Parameters")));
			String parametersString = line;
			while(readPreambleLine())
				parametersString+=line+"\n";
			IJ.showStatus( "Setting Amira parameters..." );
			this.parameters=new AmiraParameters(parametersString);

			if (mode == ASCII)
				parseColumns(parametersString);
			IJ.showStatus( "Checking enconding mode..." );
			Pattern rlePattern=Pattern.compile(".*HxByteRLE.*",Pattern.DOTALL|Pattern.MULTILINE);
			if(rlePattern.matcher(parametersString).matches())
				mode = RLE;

			Matcher zMatcher=Pattern.compile(".*HxZip,([0-9]+).*",Pattern.DOTALL|Pattern.MULTILINE)
				.matcher(parametersString);
			if(zMatcher.matches()) {
				mode = ZLIB;
				zLength = Integer.parseInt(zMatcher.group(1));
			}
		} catch(Exception e) {
			e.printStackTrace();
			IJ.error(e.toString());
			return false;
		}
		return true;
	}

	private boolean readPreambleLine() {
		if(file==null || endOffsetOfPreamble>0)
			return false;
		if (!readLine())
			return false;
		if( line.startsWith("Lattice {" ) )
		{
			if ( line.startsWith("Lattice { ushort" ) ||
					line.startsWith("Lattice { short" ) )
				this.shortData = true;
			return true;
		}
		if(line!="" && line.charAt(0)=='@') {
			try {
				endOffsetOfPreamble=file.getFilePointer();
			} catch(Exception e) {
				IJ.error("error: "+e.toString());
			}
			return false;
		}
		return true;
	}

	private boolean readLine() {
		line="";
		int input;
		try {
			while((input=file.read())>=0 && input!=0x0d && input!=0x0a)
				line+=String.valueOf((char)input);
			while((input=file.read())>=0 && input==0x0d && input==0x0a);
			file.seek(file.getFilePointer()-1);
		} catch(Exception e) {
			IJ.error("error: "+e.toString());
			return false;
		}
		return true;
	}

	private void parseColumns(String p) {
		int index = p.lastIndexOf('@') + 1;
		int index2;
		for (index2 = index + 1;
				Character.isDigit(p.charAt(index2));
				index2++);
		int numCols = Integer.parseInt(p.substring(index, index2));
		colFormat = new String[numCols];
		colName = new String[numCols];

		for (int i = numCols; i > 0; i--) {
			index2 = p.lastIndexOf('\n', index);
			int index3 = p.indexOf('{', index2);
			while (p.charAt(index3 - 1) == ' ')
				index3--;
			colName[i-1] = p.substring(index2 + 1, index3);

			index3++;
			while (p.charAt(index3) == ' '
					|| p.charAt(index3) == '{')
				index3++;

			int index4 = p.indexOf(' ', index3);
			colFormat[i-1] = p.substring(index3, index4);

			index = p.lastIndexOf('@', index2);
		}
	}

	public int readRLE(byte[] pixels,int offset,int length) throws java.io.IOException {
		if(rleOverrun==null)
			rleOverrun=new byte[256];
		int i;
		if(rleOverrunLength>0) {
			for(i=0;length>0&&i<rleOverrunLength;offset++,length--,i++)
				pixels[offset]=rleOverrun[i];
			if(i<rleOverrunLength) {
				for(int j=0;i+j<rleOverrunLength;j++)
					rleOverrun[j]=rleOverrun[i+j];
				rleOverrunLength-=i;
			}
			rleOverrunLength=0;
			return i;
		}
		file.read(rleOverrun,0,1);
		if(rleOverrun[0]==0) {
			IJ.log("byte at offset "+file.getFilePointer()+" is 0!");
			throw new java.io.IOException("unexpected zero");
		}
		if(rleOverrun[0]<0) {
			rleOverrun[0]&=0x7f;
			if(rleOverrun[0]>length) {
				file.read(pixels,offset,length);
				rleOverrunLength=rleOverrun[0]-length;
				file.read(rleOverrun,0,rleOverrunLength);
				return length;
			}
			file.read(pixels,offset,rleOverrun[0]);
			return rleOverrun[0];
		}
		file.read(pixels,offset,1);
		if(rleOverrun[0]>length) {
			for(i=1;i<length;i++)
				pixels[offset+i]=pixels[offset];
			rleOverrunLength=rleOverrun[0]-length;
			for(i=0;i<rleOverrunLength;i++)
				rleOverrun[i]=pixels[offset];
			return length;
		}
		for(i=1;i<rleOverrun[0];i++)
			pixels[offset+i]=pixels[offset];
		return rleOverrun[0];
	}

	public int readZlib(byte[] pixels,int offset,int length) throws java.io.IOException {

		if (zStream == null){
			in = new BufferedInputStream( new FileInputStream( file.getFD() ) );				
			zStream = new InflaterInputStream( in, new Inflater(), length );				
		}
		return zStream.read(pixels, offset, length);		
	}
	/**
	 * Get AmiraMesh data as a stack. This method reads the slices one by one,
	 * so it is slower than <code>getStackFast</code> but uses less memory.
	 * Important: the file and output stream are closed after reading.
	 * 
	 * @return image info a stack
	 */
	public ImageStack getStack() {
		if(file==null || endOffsetOfPreamble<0)
			return null;
		ImageStack stack;
		int extra = shortData ? 2 : 1;
		ColorModel colorModel=parameters.getColorModel();
		if(colorModel==null)
			stack=new ImageStack(width,height);
		else
			stack=new ImageStack(width,height,colorModel);
		byte[] buffer;
		try {
			file.seek(endOffsetOfPreamble);
			for(int z=0;z<numSlices;z++) {
				buffer=new byte[width*height*extra];
				if(mode == RLE) {
					int count,offset=0,length=width*height*extra;
					while((count=readRLE(buffer,offset,length))<length) {
						offset+=count;
						length-=count;
					}
				} else if(mode == ZLIB) {
					int count,offset=0,length=width*height*extra;
					while((count=readZlib(buffer,offset,length))<length) {
						if (count < 0)
							break;
						offset+=count;
						length-=count;
					}
				} else
					file.read(buffer,0,width*height*extra);

				if( shortData )
				{
					short[] shortBuffer = new short[ width * height ];
					ByteBuffer.wrap( buffer ).order( byteOrder ).asShortBuffer().get( shortBuffer );
					stack.addSlice(null, shortBuffer );
				}
				else
					stack.addSlice(null,buffer);
				IJ.showStatus( "Reading slice " + (z+1) + "/" + numSlices +"...");
				IJ.showProgress(z+1, numSlices);
			}
			
			if( mode == ZLIB )
			{
				zStream.close();
				in.close();
			}
			
		} catch(Exception e) {
			e.printStackTrace();
			IJ.error("internal: "+e.toString());
		}
		
		IJ.showProgress( 1.0 );
		return stack;
	}

	/**
	 * Get AmiraMesh data as a stack in a fast way, at a cost of reading the 
	 * whole image information at once. It requires more memory than <code>getStack</code>
	 * but it is much faster.
	 * Important: the file and output stream are closed after reading.
	 * 
	 * @return image info as stack or null if error
	 */
	public ImageStack getStackFast() {
		if(file==null || endOffsetOfPreamble<0)
			return null;
		// output stack
		ImageStack stack;
		int extra = shortData ? 2 : 1;

		ColorModel colorModel=parameters.getColorModel();
		
		if(colorModel==null)
			stack=new ImageStack(width,height);
		else
			stack=new ImageStack(width,height,colorModel);
		
		// buffer to store all pixel information
		byte[] buffer = null;
		
		// read all pixel data from file at once
		try {
			file.seek(endOffsetOfPreamble);
			int length = width * height * numSlices * extra;
			buffer = new byte[ length ];
			
			if(mode == RLE) {				
				if( readRLE( buffer, 0 ,length ) < length )
					return null;
			} 
			else if(mode == ZLIB) {
				if ( readZlib( buffer, 0, length) < length ) 
					return null;
			} else{
				if( file.read( buffer, 0, length ) < length )
					return null;
			}
			
			if( mode == ZLIB )
			{
				zStream.close();
				in.close();
			}
				
		} catch(Exception e) {
			e.printStackTrace();
			IJ.error("internal: "+e.toString());
		}
		
		// copy all pixel data to stack
		if(null != buffer )
			for( int z=0; z<numSlices; z++ ) {
				byte[] pixels = new byte[ width * height * extra ];
				System.arraycopy( buffer, z*pixels.length, pixels, 0, pixels.length );
				if( shortData )
				{
					short[] shortBuffer = new short[ width * height ];
					ByteBuffer.wrap( pixels ).order( byteOrder ).asShortBuffer().get( shortBuffer );
					stack.addSlice( null, shortBuffer );
				}
				else
					stack.addSlice( null, pixels);
				IJ.showProgress( z+1, numSlices );
			}
		
		IJ.showProgress( 1.0 );
		return stack;
	}
	
	public boolean isTable() {
		return mode == ASCII;
	}

	public AmiraTable getTable() {
		try {
			int numRows = Integer.parseInt(parameters.getProperty("numRows"));
			file.seek(endOffsetOfPreamble);

			String[] data = new String[numRows];
			int numCols = colName.length;
			for(int i = 0; i < numCols; i++) {
				for(int j = 0; j < numRows; j++) {
					String value;
					if (colFormat[i].equals("byte")) {
						value = "";
						int c;
						while(readLine() &&
								(c = Integer.parseInt(line.trim())) != 0)
							value += Character.toString((char)c);
					} else {
						readLine();
						value = line;
					}
					if (i == 0)
						data[j] = value;
					else
						data[j] += "\t" + value;
				}
				while(i < colName.length - 1 && readLine() &&
					(line=="" || line.charAt(0) != '@'));
			}

			String headings = colName[0];
			for (int i = 1; i < colName.length; i++)
				headings += "\t" + colName[i];

			String wholeData = data.length > 0 ? data[0] : "";
			for (int i = 1; i < numRows; i++)
				wholeData += "\n" + data[i];

			AmiraTable result = new AmiraTable(fileName, headings, wholeData);
			parameters.setParameters(result.properties);
			return result;
		} catch(Exception e) {
			e.printStackTrace();
			throw new RuntimeException("internal: ", e);
		}
	}
}


