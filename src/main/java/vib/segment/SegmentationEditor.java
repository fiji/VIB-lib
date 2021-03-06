package vib.segment;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.PlugIn;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Segmentation_Editor : ImageJ plugin.
 * Adds a panel containing all tools needed for a Segmentation Editor to
 * the left side of the current stack.
 * 
 * @author Francois KUSZTOS
 * @version 3.0
 */
public class SegmentationEditor implements PlugIn {

	private CustomStackWindow csw;

	public void run(String arg) {
		ImagePlus image = WindowManager.getCurrentImage();
		if (image==null) {
			IJ.error("No image?");
			return;
		}

		ActionListener a = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(e.getActionCommand().equals("Ok")) {
					synchronized (this) {
						notifyAll();
					}
				}
			}
		};

		
		csw = new CustomStackWindow(image);
		csw.getLabels().show();
		csw.addActionListener(a);
		synchronized (a) {
			try {
				a.wait();
			} catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
		System.out.println("arriving here");
		ImagePlus labels = csw.getLabels();
		System.out.println(image);
		image.show();
	}

	public CustomStackWindow getCustomStackWindow() {
		return csw;
	}

	public ImagePlus getLabels() {
		return csw.getLabels();
	}
}
