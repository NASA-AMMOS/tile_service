package jpl.mipl.mars.tile_service;

import java.awt.Rectangle;
import java.awt.Point;
import java.awt.image.RenderedImage;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.awt.image.Raster;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import jpl.mipl.io.plugins.VicarRenderedImage;

import gov.nist.isg.pyramidio.PartialImageReader;

import org.slf4j.Logger;

/**
 * Adapter that rasterizes portions of a RenderedImage for use by pyramidio to build a DZI.
 *
 * The input RenderedImage may represent a JAI image processing chain.  Only one rectangle is rasterized at a time,
 * typically corresponding to a DZI leaf tile.
 *
 * The input RenderedImage may have a variety of color and sample models.  It may or may not have an alpha channel.
 *
 * Stretching and masking is optionally performed as part of rasterization.  The resulting raster is always in an 8 bit
 * per band [A]RGB format.
 *
 * If stretching is requested (stretchLow != NaN, stretchHigh != NaN) then the input RenderedImage must have either a
 * grayscale or RGB colorspace, optionally with an alpha channel.  Conversion to 8 bit [A]RGB is defined by the stretch
 * parameters.  Stretch is done in linear RGB colorspace.  The alpha channel is converted to 8 bit but not stretched.
 *
 * If stretching is not requested then the input RenderedImage can have any color space and also can optionally have an
 * alpha channel.  During rasterization the colors are converted to [A]RGB and the alpha channel is converted to 8 bit.
 *
 * If convertsRGBToLinear is specified then the input data is assumed to be sRGB (regardless of the ColorModel of the
 * RenderedImage) and is converted to linear RGB.
 *
 * If convertLinearTosRGB is specified then the input data is assumed to be linar RGB (regardless of the ColorModel of
 * the RenderedImage) and is converted to sRGB.  Ignored if outputLinearRGB is true.
 *
 * Returned BufferedImages will always be sRGB unless outputLinearRGB is true.
 *
 * If masking is requested (mask != null, unmaskedAlpha &lt; 1 and/or maskedAlpha &lt; 1) then during rasterization each
 * pixel is assigned an alpha value.  If the input RenderedImage had an alpha channel, the output alpha value is the
 * product of the alpha value from the input image and either maskedAlpha, if the mask is 0 at the pixel, or
 * unmaskedAlpha.  If the input RenderedImage did not have an alpha channel the output alpha for each pixel is either
 * maskedAlpha or unmaskedAlpha.
 *
 * @author Marsette Vona
 */
public class RenderedImageReader implements PartialImageReader {

    //common case for stretching is 10-12 bit vicar DNs
    public static final int MAX_STRETCH_CACHE = 65536;

    //it *might* be a win to just directly read the requested rectangles from the underlying vicar image
    //because typically the vicar image data is uncompressed
    //and LRUCacheImageInputStream will have already memcached the bits
    //setting this to true will effectively disable the JAI tile cache for reading tiles from the vicar image
    public static final boolean VICAR_READ_DIRECT = false;

    public float minBandValue = Float.NaN, maxBandValue = Float.NaN;

    public float stretchLow = Float.NaN, stretchHigh = Float.NaN; //stretch disabled if either is NaN

    public float overrideInputGamma = Float.NaN; //input is encoded with this gamma instead of sRGB curve if not NaN

    public RenderedImage mask; //1 = unmasked, 0 = masked, may be null

    public float unmaskedAlpha = 1, maskedAlpha = 1; //will be clamped to [0,1]

    public boolean convertsRGBToLinear;
    public boolean convertLinearTosRGB;

    public boolean outputLinearRGB;

    private RenderedImage image;

    private ConcurrentHashMap<Rectangle, Integer> stats = new ConcurrentHashMap<Rectangle, Integer>();

    private float[] stretchCache, convertCache;

    public void dumpStats(Logger log, String pfx) {
        log.debug(pfx + stats.size() + " rects requested, " +
                  stats.entrySet().stream().mapToInt((e) -> e.getValue()).min().orElse(0) + "-" +
                  stats.entrySet().stream().mapToInt((e) -> e.getValue()).max().orElse(0) + " duplicates, " +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().x).min().orElse(0) + "-" +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().x).max().orElse(0) + " x0, " +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().y).min().orElse(0) + "-" +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().y).max().orElse(0) + " y0, " +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().width).min().orElse(0) + "-" +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().width).max().orElse(0) + " w, " +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().height).min().orElse(0) + "-" +
                  + stats.entrySet().stream().mapToInt((e) -> e.getKey().height).max().orElse(0) + " h");
    }

    public void dumpStats(Logger log) {
        dumpStats(log, "");
    }
                                                   
    public RenderedImageReader(RenderedImage image) {
        this.image = image;

        stretchCache = new float[MAX_STRETCH_CACHE];
        for (int i = 0; i < stretchCache.length; i++) {
            stretchCache[i] = -1;
        }

        convertCache = new float[256];
        for (int i = 0; i < convertCache.length; i++) {
            convertCache[i] = -1;
        }
    }

    public BufferedImage read() throws IOException {
        return read(new Rectangle(0, 0, getWidth(), getHeight()));
    }
    
    public BufferedImage read(Rectangle rect) throws IOException {
        String msg = "attempting to read " + rect.width + "x" + rect.height + " subrect of " +
            image.getWidth() + "x" + image.getHeight() + " image, " +
            Utils.kmg(ImageInfo.getImageBytes(rect.width, rect.height, image.getSampleModel())) + " bytes";
        var oom = new OutOfMemoryError(msg);
        try {
            stats.compute(rect, (r, c) -> c == null ? 1 : c + 1);
            var raster = renderRect(image, rect);
            boolean doStretch = !Float.isNaN(stretchLow) && !Float.isNaN(stretchHigh);
            var bi = doStretch ? stretchTo8BitARGB(raster) : convertTo8BitARGB(raster);
            return hasMask() ? applyMask(bi, renderRect(mask, rect)) : bi;
        } catch (OutOfMemoryError ex) {
            throw oom;
        }
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    private WritableRaster renderRect(RenderedImage ri, Rectangle rect) throws IOException {
        var sm = ri.getSampleModel().createCompatibleSampleModel(rect.width, rect.height);
        if (VICAR_READ_DIRECT && ri instanceof VicarRenderedImage) { //skip JAI tile cache
            var db = sm.createDataBuffer();
            ((VicarRenderedImage)ri).vif.readTile(rect.x, rect.y, rect.width, rect.height,
                                                  0, 0, //destination offset
                                                  null, //bandList
                                                  sm, db);
            return Raster.createWritableRaster(sm, db, new Point(0, 0));
        } else {
            var raster = Raster.createWritableRaster(sm, rect.getLocation());
            try {
                ri.copyData(raster);
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.startsWith("IOException")) { //freaking VICARIO
                    throw new IOException(msg);
                }
                throw ex;
            }
            return raster.createWritableChild(rect.x, rect.y, //parent origin
                                              rect.width, rect.height,
                                              0, 0, //child origin
                                              null); //band list
        }
    }

    private BufferedImage stretchTo8BitARGB(Raster raster) {

        //get here only for stretchable images
        //NOTE: overlayable and stretchable are mutually exclusive
        //minBandValue, maxBandValue, stretchLow, and stretchHigh will be set here

        //the input image is a raw loaded RDR
        //or maybe a user upload or quicklook/autolook/closerlook
        //it might have been any supported format: IMG, VIC, PNG, TIFF, JPG
        //it might be grayscale or RGB
        //(we don't handle other color models here yet, and we assume any general 3 color image is RGB)
        //it might or might not have an alpha channel
        //it might have float or integer pixel data
        //for IMG/VIC integer images there might be a SAMPLE_BIT_MASK (i.e. fewer significant bits than storage format)

        //for integer images, minBandValue = 0, maxBandValue = 2^sigBits - 1
        //for float images {min/max}BandValue = min/max actual pixel DN across all bands

        //our tasks here are to
        //(a) color convert to linear RGB
        //(b) perform stretching in linear RGB:
        //    [-inf,        stretchLow)  -> 0
        //    [stretchLow,  stretchHigh] -> [0, 255]
        //    (stretchHigh, inf]         -> 255
        //(c) color convert to sRGB
        //(d) handle any requests for outputLinearRGB, convertsRGBToLinear, or convertLinearTosRGB
        //(e) add an alpha channel if necessary
        //(f) convert to 8 bit per channel 32 bit int packed (A)RGB

        var cm = ImageInfo.getColorModel(image);
        var cs = cm != null ? cm.getColorSpace() : null;
        int cst = cs != null ? cs.getType() : -1;
        if (cs == null || (cst != ColorSpace.TYPE_GRAY && cst != ColorSpace.TYPE_RGB && cst != ColorSpace.TYPE_3CLR)) {
            String typeInfo = cs != null ? (cs.getClass().getName() + " type " + cst + ": ") : "unknown color model";
            if (cs != null) {
                for (int i = 0; i < cs.getNumComponents(); i++) {
                    typeInfo += cs.getName(i) + ((i < (cs.getNumComponents() - 1)) ? ", " : "");
                }
            } else {
                typeInfo += ", " + raster.getNumBands() + " bands";
            }
            throw new IllegalStateException("GRAY or RGB color space required for stretching, got " + typeInfo);
        }

        boolean inAlpha = ImageInfo.hasAlpha(image);
        var sm = raster.getSampleModel();
        int inBands = sm.getNumBands(); 
        int numColorChannels = inBands - (inAlpha ? 1 : 0);
        int expectedNumColorChannels = cst == ColorSpace.TYPE_GRAY ? 1 : 3;
        if (numColorChannels != expectedNumColorChannels) {
            throw new IllegalStateException("expected " + expectedNumColorChannels + ", got " + numColorChannels);
        }

        boolean outAlpha = inAlpha || hasMask();
        int outBands = outAlpha ? 4 : 3;

        float stretchLow = this.stretchLow;
        float stretchHigh = this.stretchHigh;

        float quantum = ImageInfo.getQuantum(image);
        if (stretchLow == stretchHigh) {
            if (stretchLow >= quantum) {
                stretchLow = Math.max(0, stretchLow - quantum);
            } else {
                stretchHigh += quantum;
            }
        }

        float inMin = minBandValue, inMax = maxBandValue;
        float inRange = inMax - inMin;
        if (convertsRGBToLinear && inRange > 0) {
            stretchLow = inMin + inRange * (sRGBToLinear(255 * ((stretchLow - inMin) / inRange)) / 255);
            stretchHigh = inMin + inRange * (sRGBToLinear(255 * ((stretchHigh - inMin) / inRange)) / 255);
            stretchLow = Math.min(Math.max(stretchLow, minBandValue), maxBandValue);
            stretchHigh = Math.min(Math.max(stretchHigh, minBandValue), maxBandValue);
        }

        //based on code in marsviewer StretchManager.java

        float rescale = 255 / (stretchHigh - stretchLow);

        float[] inPixel = new float[inBands];
        float[] outPixel = new float[outBands];

        int w = raster.getWidth(), h = raster.getHeight();

        var inBuffer = raster.getDataBuffer();
        var inOffsets = inBuffer.getOffsets();
        if (inBuffer.getNumBanks() != inBands || inBuffer.getSize() != w * h ||
            inOffsets == null || inOffsets.length != inBands) {
            inBuffer = null;
        }
        if (inBuffer != null) {
            for (int i = 0; i < inBands; i++) {
                if (inOffsets[i] != 0) {
                    inBuffer = null;
                }
            }
        }
        //if inBuffer is still non-null we'll use it to read a little bit faster
        //typically the conditions above will hold for the common case of a band sequential VICAR input image

        var bi = makeBufferedImage(w, h, outAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var outRaster = bi.getRaster();
        var outBuffer = outRaster.getDataBuffer();

        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                if (inBuffer != null) {
                    for (int i = 0; i < inBands; i++) {
                        inPixel[i] = inBuffer.getElemFloat(i, y * w + x);
                    }
                } else {
                    raster.getPixel(x, y, inPixel); //works but slower
                }
                for (int i = 0; i < numColorChannels; i++) {
                    int cacheKey = (int)inPixel[i];
                    boolean useCache =  cacheKey == inPixel[i] && cacheKey >= 0 && cacheKey < stretchCache.length;
                    if (useCache) {
                        float cacheValue = stretchCache[cacheKey];
                        if (cacheValue >= 0) {
                            //even in MT case if we get here we should have a valid cacheValue
                            //because 4 byte write and read should be atomic
                            //(we might not get here even if some other thread has written to this cachekey,
                            //but we'll just recompute and re-write the same value, and then it should be visible to us)
                            outPixel[i] = cacheValue;
                            continue;
                        }
                    }
                    if (convertsRGBToLinear && inRange > 0) {
                        inPixel[i] = inMin + inRange * (sRGBToLinear(255 * ((inPixel[i] - inMin) / inRange)) / 255);
                    }
                    //assuming convertsRGBToLinear has been set if the input was actually sRGB
                    //and that the input was linear RGB otherwise
                    //at this point we should be in linear RGB colorspace [0-maxBandValue]
                    //do stretching from [0-maxBandValue] to [0-255] in this space
                    if (inPixel[i] <= stretchLow) {
                        inPixel[i] = 0;
                    } else if (inPixel[i] >= stretchHigh) {
                        inPixel[i] = 255;
                    } else {
                        inPixel[i] = Math.max(Math.min((inPixel[i] - stretchLow) * rescale, 255), 0);
                    }
                    //now we are in linear RGB [0-255]
                    //output in sRGB [0-255] unless outputLinearRGB is set or convertLinearTosRGB is not set
                    outPixel[i] = (outputLinearRGB || !convertLinearTosRGB) ? inPixel[i] : linearTosRGB(inPixel[i]);
                    if (useCache) {
                        //in MT case all threads should have computed the same value for the same cacheKey
                        stretchCache[cacheKey] = outPixel[i];
                    }
                }
                if (numColorChannels == 1) {
                    outPixel[1] = outPixel[2] = outPixel[0]; //monochrome -> RGB
                }
                if (outAlpha) {
                    outPixel[3] =
                        255 * ((inAlpha && inRange > 0) ? ((inPixel[inPixel.length - 1] - inMin) / inRange) : 1);
                }
                outRaster.setPixel(x, y, outPixel); //works but slower
                int argb  = ((int)outPixel[2] & 0xff) << 0;  //b
                argb     |= ((int)outPixel[1] & 0xff) << 8;  //g
                argb     |= ((int)outPixel[0] & 0xff) << 16; //r
                argb     |= ((outAlpha ? (int)outPixel[3] : 255) & 0xff) << 24; //a
                outBuffer.setElem(y * w + x, argb);
            }
        }

        return bi;
    }

    private BufferedImage makeBufferedImage(int w, int h, int biType) {
        if (outputLinearRGB) {
            ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
            int bits = 0;
            int amask = 0;
            if (biType == BufferedImage.TYPE_INT_ARGB) {
                bits = 32;
                amask = 0xff000000;
            } else if (biType == BufferedImage.TYPE_INT_RGB) {
                bits = 24;
            } else {
                throw new IllegalArgumentException("unsupported BufferedImage type: " + biType);
            }
            ColorModel cm = new DirectColorModel(cs, bits, 0x00ff0000, 0x0000ff00, 0x000000ff, amask, false,
                                                 DataBuffer.TYPE_INT);
            return new BufferedImage (cm, cm.createCompatibleWritableRaster(w, h), false, null);
        } else {
            return new BufferedImage(w, h, biType);
        }
    }

    private BufferedImage convertTo8BitARGB(WritableRaster raster) {

        //get here only for overlayable images
        //NOTE: overlayable and stretchable are mutually exclusive
        //minBandValue, maxBandValue, stretchLow, and stretchHigh will not be set here

        //the input raster here will have been something produced by a marsviewer library RdrImageContent
        //which generally (always? almost always?) seem to spit out 8 bit sRGB data
        //though not necessarily with an alpha channel

        //our tasks here are to
        //(a) attempt to color convert any weird overlay images that are not sRGB to sRGB
        //(b) handle any requests for outputLinearRGB, convertsRGBToLinear, or convertLinearTosRGB
        //(c) add an alpha channel if necessary
        //(d) convert any weird non-8-bit overlay images to 8 bit per channel 32 bit int packed (A)RGB

        var cm = ImageInfo.getColorModel(image);
        if (cm == null) {
            int numBands = image.getSampleModel().getNumBands(); 
            throw new IllegalStateException("cannot convert " + numBands + " band image to 8 bit (A)RGB");
        }

        var bi = new BufferedImage(cm, raster, cm.isAlphaPremultiplied(), null);

        boolean inAlpha = ImageInfo.hasAlpha(image);
        boolean outAlpha = inAlpha || hasMask();

        int biType =  outAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        boolean mustRebuild = bi.getType() != biType || outputLinearRGB || !cm.getColorSpace().isCS_sRGB();

        if (mustRebuild || convertsRGBToLinear || convertLinearTosRGB) {
            
            var sm = raster.getSampleModel();
            int inBands = sm.getNumBands(); 
            int numColorChannels = inBands - (inAlpha ? 1 : 0);
            
            int outBands = outAlpha ? 4 : 3;
            
            float[] pixel = new float[outBands];

            int w = bi.getWidth(), h = bi.getHeight();
            var tmp = mustRebuild ? makeBufferedImage(w, h, biType) : bi;
            var outRaster = tmp.getRaster();
            var outBuffer = outRaster.getDataBuffer();

            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    //careful - BufferedImage.getRGB() breaks if raster has fewer signficant than actual bits per band
                    //but we shouldn't get here if that's true
                    int argb = bi.getRGB(x, y); //[0-255] in sRGB colorspace (if ColorModel is to be trusted)
                    pixel[0] = (argb & 0x00ff0000) >> 16; //r
                    pixel[1] = (argb & 0x0000ff00) >> 8;  //g
                    pixel[2] = (argb & 0x000000ff) >> 0;  //b
                    for (int i = 0; i < 3; i++) {
                        int cacheKey = (int)pixel[i];
                        boolean useCache = cacheKey >= 0 && cacheKey < convertCache.length;
                        if (useCache) { //should always be true, but whatever
                            float cacheValue = convertCache[cacheKey];
                            if (cacheValue >= 0) {
                                pixel[i] = cacheValue;
                                continue;
                            }
                        }
                        if (outputLinearRGB) {
                            if (convertsRGBToLinear) {
                                pixel[i] = sRGBToLinear(pixel[i]);
                            }
                        } else {
                            if (convertsRGBToLinear) {
                                pixel[i] = sRGBToLinear(pixel[i]);
                            }
                            if (convertLinearTosRGB) {
                                pixel[i] = linearTosRGB(pixel[i]);
                            }
                        }
                        if (useCache) {
                            convertCache[cacheKey] = pixel[i];
                        }
                    }
                    if (outAlpha) pixel[3] = inAlpha ? ((argb & 0xff000000) >> 24) : 255;
                    //outRaster.setPixel(x, y, pixel); //works but slower
                    argb  = ((int)pixel[2] & 0xff) << 0;  //b
                    argb |= ((int)pixel[1] & 0xff) << 8;  //g
                    argb |= ((int)pixel[0] & 0xff) << 16; //r
                    argb |= ((outAlpha ? (int)pixel[3] : 255) & 0xff) << 24; //a
                    outBuffer.setElem(y * w + x, argb);
                }
            }

            bi = tmp;
        }

        return bi;
    }

    private boolean hasMask() {
        return mask != null && !Float.isNaN(unmaskedAlpha) && !Float.isNaN(maskedAlpha) &&
            (unmaskedAlpha < 1 || maskedAlpha < 1);
    }

    private BufferedImage applyMask(BufferedImage bi, Raster maskRaster) {

        //get here only after stretchTo8BitARGB() or convertTo8BitARGB()
        //so the input should always be ARGB with 8 bits per channel packed into 32 bit ints
        
        if (!hasMask()) {
            throw new IllegalStateException("no mask");
        }

        WritableRaster raster = bi.getRaster();

        if (bi.getType() != BufferedImage.TYPE_INT_ARGB ||
            raster.getDataBuffer().getDataType() != DataBuffer.TYPE_INT) { //should be implied, but let's verify
            throw new IllegalArgumentException("INT_ARGB image required to apply alpha mask");
        }

        int mb = mask.getSampleModel().getNumBands();
        if (mask.getWidth() != getWidth() || mask.getHeight() != getHeight() || mb != 1) {
            throw new IllegalStateException
                (String.format("mask must be (%dx%dx%d), got (%dx%dx%d)",
                               getWidth(), getHeight(), 1, mask.getWidth(), mask.getHeight(), mb));
        }

        unmaskedAlpha = Math.max(Math.min(unmaskedAlpha, 1), 0);
        maskedAlpha = Math.max(Math.min(maskedAlpha, 1), 0);

        int[] argb = new int[1];

        int w = bi.getWidth(), h = bi.getHeight();
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                raster.getDataElements(x, y, argb);
                float a = ((argb[0] & 0xff000000) >>> 24) / 255.0f; //>>> is unsigned right shift
                a *= maskRaster.getSample(x, y, 0) == 0 ? maskedAlpha : unmaskedAlpha;
                int newAlpha = ((int)(a * 255) & 0xff) << 24;
                int newARGB = newAlpha | (argb[0] & 0x00ffffff);
                if (newARGB != argb[0]) {
                    argb[0] = newARGB;
                    raster.setDataElements(x, y, argb);
                }
            }
        }

        return bi;
    }

    //http://entropymine.com/imageworsener/srgbformula
    private float linearTosRGB(float l)
    {
        l /= 255.0f;
        double s = l < 0.0031308 ? (l * 12.92) : (1.055 * Math.pow(l, 1.0 / 2.4) - 0.055); //sRGB curve
        return 255.0f * (float)Math.max(Math.min(s, 1), 0);
    }

    private float sRGBToLinear(float s)
    {
        s /= 255.0f;
        double l = !Float.isNaN(overrideInputGamma) ?
            Math.pow(s, 1 / overrideInputGamma) : //general gamma curve
            s < 0.04045 ? (s / 12.92) : Math.pow((s + 0.055) / 1.055, 2.4); //sRGB curve
        return 255.0f * (float)Math.max(Math.min(l, 1), 0);
    }
}
