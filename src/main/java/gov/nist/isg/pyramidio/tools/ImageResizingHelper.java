package gov.nist.isg.pyramidio.tools;

import net.coobird.thumbnailator.Thumbnails;
import java.awt.image.BufferedImage;
import java.io.IOException;

//replaces buggy code in pyramidio
//the original class is excluded using the maven shade plugin in pom.xml
//https://github.com/usnistgov/pyramidio/issues/12
public class ImageResizingHelper {
	public static BufferedImage resizeImage(BufferedImage img, int width, int height) {
        try {
            return Thumbnails.of(img).size(width, height).asBufferedImage();
        } catch (IOException ex) {
            throw new RuntimeException("error resizing image", ex);
        }
	}
}
