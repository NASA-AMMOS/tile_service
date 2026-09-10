package jpl.mipl.mars.tile_service;

import java.util.Hashtable;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.awt.Transparency;
import java.awt.image.SampleModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.ColorModel;
import java.awt.color.ColorSpace;
import javax.media.jai.PlanarImage;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;

import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Document;

import org.apache.commons.lang3.ArrayUtils;

import org.slf4j.Logger;

import jpl.mipl.io.util.DOMtoHashtable;

/**
 *
 * @author Marsette Vona
 */
public class ImageInfo {

    public final int width;
    public final int height;
    public final int bands;
    public final int bytesPerSample;

    public final int significantBits;
    public final boolean floatImage;
    public final float gamma; //NaN if unknown
    public final boolean hasAlpha;
    public final List<float[]> invalid;

    public static final Pattern BIT_MASK_PATTERN = Pattern.compile("^2#0*(1+)#$");

    public ImageInfo(int width, int height, int bands, int bytesPerSample, int significantBits, boolean floatImage,
                     float gamma, boolean hasAlpha, List<float[]> invalid) {
        this.width = width;
        this.height = height;
        this.bands = bands;
        this.bytesPerSample = bytesPerSample;
        this.significantBits = significantBits > 0 ? significantBits : 8 * bytesPerSample;
        this.floatImage = floatImage;
        this.gamma = gamma;
        this.hasAlpha = hasAlpha;
        this.invalid = invalid;
    }

    public ImageInfo(int width, int height, int bands, int bytesPerSample, int significantBits, float gamma,
                     boolean hasAlpha, boolean maskBlack) {
        this(width, height, bands, bytesPerSample, significantBits, false, gamma, hasAlpha,
             finishInvalid(null, bands, maskBlack));
    }

    public ImageInfo withGamma(float g) {
        return new ImageInfo(width, height, bands, bytesPerSample, significantBits, floatImage, g, hasAlpha, invalid);
    }

    public static ImageInfo parse(IIOMetadata md, int defSigBits, float defGamma, boolean blackInvalid,
                                  Logger log, String pfx) throws IOException {
        String[] mds = md.getMetadataFormatNames();
        String nmd = md.getNativeMetadataFormatName();
        ImageInfo ret = parse(nmd, md.getAsTree(nmd), defSigBits, defGamma, blackInvalid, log, pfx);
        for (int i = 0; ret == null && i < mds.length; i++) {
            ret = parse(mds[i], md.getAsTree(mds[i]), defSigBits, defGamma, blackInvalid, log, pfx);
        }
        if (ret == null) {
            throw new IOException("failed to parse image metadata: " + String.join(", ", mds));
        }
        return ret;
    }

    private static ImageInfo parse(String metadataFormat, Node root, int defSigBits, float defGamma,
                                   boolean blackInvalid, Logger log, String pfx)
        throws IOException {
        if (root != null && metadataFormat != null) {
            String umd = metadataFormat.toUpperCase();
            if (umd.equals("VICAR_LABEL")) {
                return parseVICAR(root, defSigBits, defGamma, blackInvalid, log, pfx);
            } else if (umd.equals("PDS_LABEL")) {
                return parsePDS(root, defSigBits, defGamma, blackInvalid, log, pfx);
            } else if (umd.contains("JPEG") || umd.contains("JPG")) {
                return parseJPEG(root, blackInvalid);
            } else if (umd.contains("PNG")) {
                return parsePNG(root, defSigBits, blackInvalid);
            } else if (umd.contains("TIF")) {
                return parseTIFF(root, defSigBits, blackInvalid);
            }
        }
        return null;
    }

    private static int parseSBMLabel(Hashtable ht, int bytesPerSample, int defSigBits, Logger log, String pfx) {
        int bits = bytesPerSample * 8;
        int sigBits = (bits < 0 || defSigBits < bits) ? defSigBits : bits;
        boolean ok = false;
        String sk = "SAMPLE_BIT_MASK";
        for (String key : new String[] { "IMAGE" /* IMG */, "IMAGE_DATA" /* VIC */}) {
            if (ht.containsKey(key)) {
                Hashtable id = (Hashtable)(ht.get(key));
                if (id.containsKey(sk)) {
                    String sbm = Utils.trimWhitespaceQuotesBraces((String)(id.get(sk)));
                    Matcher matcher = BIT_MASK_PATTERN.matcher(sbm);
                    if (matcher.matches()) {
                        int ones = matcher.group(1).length(); //always > 0
                        if (bits > 0 && ones > bits) {
                            //this has been observed to happen e.g. on an MSL MXY RDR (ones=15, bits=8)
                            log.warn(pfx + sk + " \"" + sbm + "\" sig bits " + ones + " > " + bits);
                        }
                        sigBits = ones;
                        ok = true;
                    } else {
                        log.warn(pfx + "invalid format for " + sk + "=" + ht.get(sk));
                    }
                }
                break;
            }
        }
        if (!ok) {
            log.warn(pfx + sk + " not found, using " + sigBits);
        }
        return sigBits;
    }

    private static float parseGammaLabel(Hashtable ht, float defGamma, Logger log, String pfx) {
        float gamma = defGamma;
        String dik = "DERIVED_IMAGE_PARMS";
        String gk = "ENCODED_DISPLAY_GAMMA";
        if (ht.containsKey(dik)) {
            Hashtable dip = (Hashtable)(ht.get(dik));
            if (dip.containsKey(gk)) {
                try {
                    String gammaStr = Utils.trimWhitespaceQuotesBraces((String)(dip.get(gk)));
                    if (gammaStr.toLowerCase().equals("srgb")) {
                        gamma = 0.45f;
                    } else {
                        gamma = 1.0f / Float.parseFloat(gammaStr); //file gamma is inverse of encoded display gamma
                    }
                } catch (Exception ex) {
                    log.warn(pfx + "invalid format for " + dip + "." + gk + "=" + dip.get(gk));
                }
            }
        }
        return gamma;
    }

    private static List<float[]> parseInvalidLabel(Hashtable ht, int bands, Logger log, String pfx) {
        var invalid = new ArrayList<float[]>();
        for (String key : new String[] { "IMAGE" /* IMG */, "IMAGE_DATA" /* VIC */}) {
            if (ht.containsKey(key)) {
                Hashtable id = (Hashtable)(ht.get(key));
                for (String key2 : new String[] { "INVALID_CONSTANT", "MISSING_CONSTANT" }) {
                    if (id.containsKey(key2)) {
                        try {
                            String val = Utils.trimWhitespaceQuotesBraces((String)(id.get(key2)));
                            if (val.length() > 0 && !val.toUpperCase().equals("UNK")) {
                                var components = new ArrayList<Float>();
                                for (String comp : val.split(",")) {
                                    components.add((float)(Double.parseDouble(comp.trim())));
                                }
                                if (components.size() == bands) {
                                    invalid.add(ArrayUtils.toPrimitive(components.toArray(new Float[0]), 0.0F));
                                } else {
                                    float[] fullVal = new float[bands];
                                    Arrays.fill(fullVal, components.get(0));
                                    invalid.add(fullVal);
                                }
                            }
                        } catch (Exception ex) {
                            log.warn(pfx + "invalid format for " + key + "." + key2 + "=" + id.get(key2));
                        }
                    }
                }
                break;
            }
        }
        return invalid;
    }

    private static List<float[]> finishInvalid(List<float[]> invalid, int colorBands, boolean blackInvalid) {
        if (blackInvalid) {
            if (invalid == null) invalid = new ArrayList<float[]>();
            float[] black = new float[colorBands];
            Arrays.fill(black, 0.0f);
            invalid.add(black);
        }
        if (invalid == null) return null;
        var unique = new ArrayList<float[]>();
        for (float[] val : invalid) {
            boolean found = false;
            for (var uval : unique) {
                if (Arrays.equals(val, uval)) {
                    found = true;
                    break;
                }
            }
            if (!found) unique.add(val);
        }
        return unique;
    }

    public static ImageInfo parseVICAR(Node root, int defSigBits, float defGamma, boolean blackInvalid,
                                       Logger log, String pfx) throws IOException {

        int width = -1, height = -1, bands = -1, bytesPerSample = -1;

        int sigBits = defSigBits;
        boolean floatImage = false;
        float gamma = defGamma;
        boolean hasAlpha = false;
        List<float[]> invalid = null;

        Hashtable ht = (new DOMtoHashtable((Document)root)).getHashtable();
        ht = ht.containsKey("VICAR_LABEL") ? (Hashtable)(ht.get("VICAR_LABEL")) : ht;

        Hashtable sys = ht.containsKey("SYSTEM") ? ((Hashtable)(ht.get("SYSTEM"))) : ht;

        if (sys.containsKey("NL")) {
            height = parseInt(Utils.trimWhitespaceQuotesBraces((String)(sys.get("NL"))), "VICAR NL");
        }

        if (sys.containsKey("NS")) {
            width = parseInt(Utils.trimWhitespaceQuotesBraces((String)(sys.get("NS"))), "VICAR NS");
        }

        if (sys.containsKey("NB")) {
            bands = parseInt(Utils.trimWhitespaceQuotesBraces((String)(sys.get("NB"))), "VICAR NS");
        }

        if (sys.containsKey("FORMAT")) {
            String fmt = Utils.trimWhitespaceQuotesBraces((String)(sys.get("FORMAT")));
            if ("BYTE".equalsIgnoreCase(fmt)) {
                bytesPerSample = 1;
            } else if("HALF".equalsIgnoreCase(fmt) || "WORD".equalsIgnoreCase(fmt)) {
                bytesPerSample = 2;
            } else if ("FULL".equalsIgnoreCase(fmt) || "LONG".equalsIgnoreCase(fmt)) {
            } else if ("REAL".equalsIgnoreCase(fmt)) {
                bytesPerSample = 4;
                floatImage = true;
            } else if ("COMP".equalsIgnoreCase(fmt)) {
                bytesPerSample = 8;
            } else if ("DOUB".equalsIgnoreCase(fmt)) {
                bytesPerSample = 8;
                floatImage = true;
            }
        }

        sigBits = !floatImage ? parseSBMLabel(ht, bytesPerSample, defSigBits, log, pfx) : bytesPerSample * 8;

        gamma = parseGammaLabel(ht, defGamma, log, pfx);

        if (bands > 0) {
            invalid = finishInvalid(parseInvalidLabel(ht, bands, log, pfx), bands, blackInvalid);
        }

        if (width > 0 && height > 0 && bands > 0 && bytesPerSample > 0) {
            return new ImageInfo(width, height, bands, bytesPerSample, sigBits, floatImage, gamma, hasAlpha, invalid);
        }

        return null;
    }

    public static ImageInfo parsePDS(Node root, int defSigBits, float defGamma, boolean blackInvalid,
                                     Logger log, String pfx) throws IOException {

        int width = -1, height = -1, bands = -1, bytesPerSample = -1;

        int sigBits = defSigBits;
        boolean floatImage = false;
        float gamma = defGamma;
        boolean hasAlpha = false;
        List<float[]> invalid = null;

        Hashtable ht = (new DOMtoHashtable((Document)root)).getHashtable();
        if (ht.containsKey("PDS_LABEL")) {
            ht = (Hashtable)(ht.get("PDS_LABEL"));
        }

        if (ht.containsKey("IMAGE")) {
            Hashtable img = (Hashtable)(ht.get("IMAGE"));
            if (img.containsKey("LINES")) {
                height = parseInt(Utils.trimWhitespaceQuotesBraces((String)(img.get("LINES"))), "PDS IMAGE.LINES");
            }
            if (img.containsKey("LINE_SAMPLES")) {
                width = parseInt(Utils.trimWhitespaceQuotesBraces((String)(img.get("LINE_SAMPLES"))),
                                 "PDS IMAGE.LINE_SAMPLES");
            }
            if (img.containsKey("BANDS")) {
                bands = parseInt(Utils.trimWhitespaceQuotesBraces((String)(img.get("BANDS"))), "PDS IMAGE.BANDS");
            }
            if (img.containsKey("SAMPLE_BITS")) {
                int sampleBits = parseInt(Utils.trimWhitespaceQuotesBraces((String)(img.get("SAMPLE_BITS"))),
                                          "PDS IMAGE.SAMPLE_BITS");
                if (sampleBits != 8 && sampleBits != 16 && sampleBits != 32 && sampleBits != 64) {
                    throw new IOException("unsupported PDS sample bits: " + sampleBits);
                }
                bytesPerSample = sampleBits / 8;
            }
            if (img.containsKey("SAMPLE_TYPE")) {
                String st = Utils.trimWhitespaceQuotesBraces((String)(img.get("SAMPLE_TYPE")));
                if ("IEEE_REAL".equalsIgnoreCase(st) || "PC_REAL".equalsIgnoreCase(st)) {
                    floatImage = true;
                }
                //MSB_INTEGER, VAX_INTEGER, MSB_UNSIGNED_INTEGER, PC_UNSIGNED_INTEGER, UNSIGNED_INTEGER, USHORT
            }
        }

        sigBits = !floatImage ? parseSBMLabel(ht, bytesPerSample, defSigBits, log, pfx) : bytesPerSample * 8;

        gamma = parseGammaLabel(ht, defGamma, log, pfx);

        if (bands > 0) {
            invalid = finishInvalid(parseInvalidLabel(ht, bands, log, pfx), bands, blackInvalid);
        }

        if (width > 0 && height > 0 && bands > 0 && bytesPerSample > 0) {
            return new ImageInfo(width, height, bands, bytesPerSample, sigBits, floatImage, gamma, hasAlpha, invalid);
        }

        return null;
    }

    public static ImageInfo parseJPEG(Node root, boolean blackInvalid) throws IOException {

        //javax_imageio_jpeg_image_1.0 -> markerSequence -> sof numLines=N samplesPerLine=N numFrameComponents=N

        int width = -1, height = -1, bands = -1, bytesPerSample = 1;

        int sigBits = 8;
        boolean floatImage = false;
        float gamma = Float.NaN;
        List<float[]> invalid = null;
        boolean hasAlpha = false;

        for (Node ms = root.getFirstChild(); ms != null; ms = ms.getNextSibling()) {
            if ("markerSequence".equals(ms.getNodeName())) {
                for (Node sof = ms.getFirstChild(); sof != null; sof = sof.getNextSibling()) {
                    if ("sof".equals(sof.getNodeName())) {
                        NamedNodeMap attrs = sof.getAttributes();
                        width = parseInt(attrs.getNamedItem("samplesPerLine"),
                                         "JPEG markerSequence.sof.samplesPerLine");
                        height = parseInt(attrs.getNamedItem("numLines"),
                                          "JPEG markerSequence.sof.numLines");
                        bands = parseInt(attrs.getNamedItem("numFrameComponents"),
                                         "JPEG markerSequence.sof.numFrameComponents");
                        break;
                    }
                }
                break;
            }
        }

        //leave gamma as unknown
        //afaik the jpeg format doesn't directly deal with colorspace or gamma metadata
        //but it's common for application specfic marker segments to do so
        //the ImageIO loader *seems* to be aware of those so let's just trust that it's colorspace/gamma correct
        //all the way through to correctly specifying the colorspace of the images it returns

        if (bands > 0) {
            invalid = finishInvalid(invalid, bands, blackInvalid);
        }

        if (width > 0 && height > 0 && bands > 0 && bytesPerSample > 0) {
            return new ImageInfo(width, height, bands, bytesPerSample, sigBits, floatImage, gamma, hasAlpha, invalid);
        }

        return null;
    }

    public static ImageInfo parsePNG(Node root, int defSigBits, boolean blackInvalid) throws IOException {

        //javax_imageio_png_1.0 -> IHDR width=N height=N bitDepth=8|16 colorType=Grayscale|RGB|GrayAlpha|RGBAlpha

        int width = -1, height = -1, bands = -1, bytesPerSample = -1;

        int sigBits = defSigBits;
        boolean floatImage = false;
        float gamma = Float.NaN;
        boolean hasAlpha = false;
        List<float[]> invalid = null;

        for (Node ihdr = root.getFirstChild(); ihdr != null; ihdr = ihdr.getNextSibling()) {
            if ("IHDR".equals(ihdr.getNodeName())) {
                NamedNodeMap attrs = ihdr.getAttributes();
                width = parseInt(attrs.getNamedItem("width"), "PNG IHDR width");
                height = parseInt(attrs.getNamedItem("height"), "PNG IHDR height");
                int bitDepth = parseInt(attrs.getNamedItem("bitDepth"), "PNG IHDR bitDepth");
                if (bitDepth != 8 && bitDepth != 16) {
                    throw new IOException("unsupported PNG bit depth: " + bitDepth);
                }
                bytesPerSample = bitDepth / 8;
                sigBits = bitDepth;
                Node ct = attrs.getNamedItem("colorType");
                String colorType = ct != null ? ct.getNodeValue() : null;
                switch (colorType) {
                case "Grayscale": bands = 1; break;
                case "RGB": bands = 3; break;
                case "GrayAlpha": bands = 2; hasAlpha = true; break;
                case "RGBAlpha": bands = 4; hasAlpha = true; break;
                default: throw new IOException("unsupported PNG color type: " + colorType);
                }
                break;
            }
        }

        //leave gamma as unknown
        //PNG metadata can optionally specify colorspace and gamma in several different ways
        //the ImageIO loader *seems* to be aware of those so let's just trust that it's colorspace/gamma correct
        //all the way through to correctly specifying the colorspace of the images it returns

        if (bands > 0 && (!hasAlpha || bands > 1)) {
            invalid = finishInvalid(invalid, hasAlpha ? (bands - 1) : bands, blackInvalid);
        }

        if (width > 0 && height > 0 && bands > 0 && bytesPerSample > 0) {
            return new ImageInfo(width, height, bands, bytesPerSample, sigBits, floatImage, gamma, hasAlpha, invalid);
        }

        return null;
    }

    public static ImageInfo parseTIFF(Node root, int defSigBits, boolean blackInvalid) throws IOException {

        //javax_imageio_tiff_image_1.0 -> TIFFIFD ->
        //  TIFFField name=ImageWidth -> TIFFLongs -> TIFFLong value=N
        //  TIFFField name=ImageLength -> TIFFLongs -> TIFFLong value=N
        //  TIFFField name=BitsPerSample -> TIFFShorts -> TIFFShort value=N, TIFFShort value=N, ...

        int width = -1, height = -1, bands = -1, bytesPerSample = -1;

        int sigBits = defSigBits;
        boolean floatImage = false;
        float gamma = Float.NaN;
        boolean hasAlpha = false;
        List<float[]> invalid = null;
        String[] kinds = new String[] { "TIFFLong", "TIFFShort" };

        for (Node ifd = root.getFirstChild(); ifd != null; ifd = ifd.getNextSibling()) {
            if ("TIFFIFD".equals(ifd.getNodeName())) {
                boolean gotWidth = false, gotLength = false, gotBPS = false;
                for (Node field = ifd.getFirstChild(); field != null; field = field.getNextSibling()) {
                    if ("TIFFField".equals(field.getNodeName())) {
                        Node fn = field.getAttributes().getNamedItem("name");
                        switch (fn != null ? fn.getNodeValue() : null) {
                        case "ImageWidth": {
                            for (String kind : kinds) {
                                Node arr = field.getFirstChild();
                                if (arr != null && (kind + "s").equals(arr.getNodeName())) {
                                    Node val = arr.getFirstChild();
                                    if (val != null && kind.equals(val.getNodeName())) {
                                        width = parseInt(val.getAttributes().getNamedItem("value"), "TIFF ImageWidth");
                                        gotWidth = true;
                                    }
                                }
                            }
                            break;
                        }
                        case "ImageLength": {
                            for (String kind : kinds) {
                                Node arr = field.getFirstChild();
                                if (arr != null && (kind + "s").equals(arr.getNodeName())) {
                                    Node val = arr.getFirstChild();
                                    if (val != null && kind.equals(val.getNodeName())) {
                                        height =
                                            parseInt(val.getAttributes().getNamedItem("value"), "TIFF ImageLength");
                                        gotLength = true;
                                    }
                                }
                            }
                            break;
                        }
                        case "BitsPerSample": {
                            for (String kind : kinds) {
                                Node arr = field.getFirstChild();
                                if (arr != null && (kind + "s").equals(arr.getNodeName())) {
                                    bands = 0;
                                    for (Node val = arr.getFirstChild(); val != null; val = val.getNextSibling()) {
                                        if (kind.equals(val.getNodeName())) {
                                            int bps = parseInt(val.getAttributes().getNamedItem("value"),
                                                               "TIFF BitsPerSample");
                                            if (bps != 8 && bps != 16 && bps != 32 && bps != 64) {
                                                throw new IOException("unsupported TIFF bits per sample: " + bps);
                                            }
                                            if (bytesPerSample < 0) {
                                                bytesPerSample = bps / 8;
                                                sigBits = bps;
                                            } else if (bytesPerSample != (bps / 8)) {
                                                throw new IOException("unsupported TIFF varying bytes per sample: " +
                                                                      bytesPerSample + ", " + (bps / 8));
                                            }
                                            bands++;
                                            gotBPS = true;
                                        }
                                    }
                                }
                            }
                            break;
                        }
                        }
                        if (gotWidth && gotLength && gotBPS) {
                            break;
                        }
                    }
                }
                break;
            }
        }

        //it doesn't appear trivial to figure this stuff out here: floatImage, gamma, hasAlpha
        //
        //we leave gamma as NaN which is basically not a problem
        //the ImageIO loader *seems* to be s colorspace/gamma correct
        //all the way through to correctly specifying the colorspace of the images it returns
        //
        //and floatImage and hasAlpha are currently unused in this codepath, afaik

        if (bands > 0 && (!hasAlpha || bands > 1)) {
            invalid = finishInvalid(invalid, hasAlpha ? (bands - 1) : bands, blackInvalid);
        }

        if (width > 0 && height > 0 && bands > 0 && bytesPerSample > 0) {
            return new ImageInfo(width, height, bands, bytesPerSample, sigBits, floatImage, gamma, hasAlpha, invalid);
        }

        return null;
    }

    private static int parseInt(Node node, String what) throws IOException {
        return parseInt(node != null ? node.getNodeValue() : null, what);
    }

    private static int parseInt(String str, String what) throws IOException {
        if (str == null) {
            throw new IOException(what + " missing");
        }
        try {
            return Integer.parseInt(str);
        } catch (Exception ex) {
            throw new IOException("error parsing " + what + " as int: \"" + str + "\"");
        }
    }

    public long totalBytes() {
        return (long)width * height * bytesPerPixel();
    }

    public long totalPixels() {
        return (long)width * height;
    }

    public int bytesPerLine() {
        return width * bytesPerPixel();
    }

    public int bytesPerPixel() {
        //assume 8 bit 3 band (typically RGB) images will be represented in memory as TYPE_INT_RGB BufferedImage
        return ((bands == 3 && bytesPerSample == 1) ? 4 : bands) * bytesPerSample;
    }

    public void spew(Logger log, String pfx) {
        log.info(pfx + toString());
    }

    public String toString() {
        return width + "x" + height + " " + bands + " band " + (floatImage ? "float" : "integer") + " image, " +
            bytesPerPixel() + " bytes/pixel, " + significantBits + " significant bits, " +
            (hasAlpha ? "has" : "no") + " alpha channel, gamma " + gamma + ", " +
            (invalid != null ? invalid.size() : 0) + " invalid values, " +
            Utils.kmg(totalPixels()) + " total pixels, " + Utils.kmg(totalBytes()) + " total bytes";
    }

    public static boolean isFloat(RenderedImage image) {
        return isFloat(image.getSampleModel());
    }

    public static boolean isFloat(SampleModel sm) {
        int dt = sm.getDataType();
        return dt == DataBuffer.TYPE_DOUBLE || dt == DataBuffer.TYPE_FLOAT;
    }

    public static boolean isSigned(RenderedImage image) {
        return isSigned(image.getSampleModel());
    }

    public static boolean isSigned(SampleModel sm) {
        int dt = sm.getDataType();
        return !(dt == DataBuffer.TYPE_BYTE || dt == DataBuffer.TYPE_USHORT);
    }

    public static boolean is8Bit(RenderedImage image) {
        return is8Bit(image.getSampleModel());
    }

    public static boolean is8Bit(SampleModel sm) {
        return sm.getSampleSize(0) == 8;
    }

    public static String getImageDataTypeName(int dataType) {
        switch (dataType) {
        case DataBuffer.TYPE_BYTE: return "byte";
        case DataBuffer.TYPE_DOUBLE: return "double";
        case DataBuffer.TYPE_FLOAT: return "float";
        case DataBuffer.TYPE_INT: return "int";
        case DataBuffer.TYPE_SHORT: return "short";
        case DataBuffer.TYPE_USHORT: return "ushort";
        default: throw new IllegalArgumentException("unknown data type " + dataType);
        }
    }

    public static int getImageDataTypeBytes(int dataType) {
        switch (dataType) {
        case DataBuffer.TYPE_BYTE: return 1;
        case DataBuffer.TYPE_DOUBLE: return 8;
        case DataBuffer.TYPE_FLOAT: return 4;
        case DataBuffer.TYPE_INT: return 4;
        case DataBuffer.TYPE_SHORT: return 2;
        case DataBuffer.TYPE_USHORT: return 2;
        default: throw new IllegalArgumentException("unknown data type " + dataType);
        }
    }

    public static long getImageBytes(RenderedImage image) {
        //TileWindowImage.getSampleModel() might fib about the image dimensions to avoid a Java limitation
        return getImageBytes(image.getWidth(), image.getHeight(), image.getSampleModel());
    }

    public static long getImageBytes(int width, int height, SampleModel sm) {
        double numBands = sm.getNumBands();
        double elementsPerPixel = 0;
        int dataType = sm.getDataType();
        if (sm instanceof ComponentSampleModel) { //subclasses PixelInterleavedSampleModel, BandedSampleModel
            //"image data which is stored such that each sample of a pixel occupies one data element of the
            //DataBuffer. It stores the N samples which make up a pixel in N separate data array elements."
            elementsPerPixel = numBands;
        } else if (sm instanceof MultiPixelPackedSampleModel) {
            //"represents one-banded images and can pack multiple one-sample pixels into one data element. Pixels are
            //not allowed to span data elements."
            elementsPerPixel = sm.getSampleSize(0) / (8.0 * getImageDataTypeBytes(dataType));
        } else if (sm instanceof SinglePixelPackedSampleModel) {
            //"the N samples which make up a single pixel are stored in a single data array element, and each data
            //array element holds samples for only one pixel"
            elementsPerPixel = 1;
        } else {
            throw new IllegalArgumentException("unsupported sample model: " + sm.getClass().getName());
        }
        return getImageBytes(width, height, dataType, elementsPerPixel);
    }

    public static long getImageBytes(int width, int height, int dataType, double elementsPerPixel) {
        long totalPixels = ((long)width) * height;
        long totalElements = elementsPerPixel >= 1.0 ? totalPixels * (long)Math.round(elementsPerPixel)
            : totalPixels / (long)Math.round((1.0 / elementsPerPixel));
        return totalElements * getImageDataTypeBytes(dataType);
    }

    public static final float FLOAT_QUANTUM = .000001f;
    public static float getQuantum(RenderedImage image) {
        return isFloat(image) ? FLOAT_QUANTUM : 1.0f;
    }

    //note this can return null e.g. if image.getColorModel() is null and the number of bands is greater than 4
    public static ColorModel getColorModel(RenderedImage image) {
        var cm = image.getColorModel();
        if (cm == null) {
            var sm = image.getSampleModel();
            int dt = sm.getDataType();
            if (dt == DataBuffer.TYPE_SHORT) {
                dt = DataBuffer.TYPE_USHORT;
            }
            cm = PlanarImage.getDefaultColorModel(dt, sm.getNumBands());
        }
        return cm;
    }

    public static int getNumColorComponents(RenderedImage image) {
        return getNumColorComponents(image.getSampleModel(), getColorModel(image));
    }

    public static int getNumColorComponents(SampleModel sm, ColorModel cm) {
        int numBands = sm.getNumBands();
        //generally the number of color components would equal the number of bands
        //but if the image has an alpha channel, there would be one fewer color component than bands
        //currently there is a probable bug in vicario where for a two-band input VIC/IMG
        //then the colormodel says there is only one color component because PlanarImage.createColorModel()
        //assumes that a two band image would be greyscale + alpha
        return (cm != null && numBands > 3) ? cm.getNumColorComponents() : numBands;
    }

    public static boolean hasAlpha(RenderedImage image) {
        return hasAlpha(getColorModel(image));
    }

    public static boolean hasAlpha(ColorModel cm) {
        return cm != null && cm.getTransparency() != Transparency.OPAQUE;
    }

    public static String describeColorModel(RenderedImage image) {
        return describeColorModel(getColorModel(image));
    }

    public static String describeColorModel(ColorModel cm) {
        ColorSpace cs = cm != null ? cm.getColorSpace() : null;
        if (cs == null) {
            return "unknown colorspace";
        }
        String name = cs.isCS_sRGB() ? "sRGB" : getColorSpaceTypeName(cs.getType());
        String[] bandNames = new String[cs.getNumComponents()];
        for (int i = 0; i < bandNames.length; i++) {
            bandNames[i] = cs.getName(i);
        }
        return name + " (" + String.join(", ", bandNames) + ")" + (hasAlpha(cm) ? " with alpha" : "");
    }

    public static String getColorSpaceTypeName(int type) {
        switch (type) {
        case 12: return "2CLR";
        case 13: return "3CLR";
        case 14: return "4CLR";
        case 15: return "5CLR";
        case 16: return "6CLR";
        case 17: return "7CLR";
        case 18: return "8CLR";
        case 19: return "9CLR";
        case 20: return "ACLR";
        case 21: return "BCLR";
        case 22: return "CCLR";
        case 11: return "CMY";
        case 9: return "CMYK";
        case 23: return "DCLR";
        case 24:  return "ECLR";
        case 25: return "FCLR";
        case 6: return "GRAY";
        case 8: return "HLS";
        case 7: return "HSV";
        case 1: return "Lab";
        case 2: return "Luv";
        case 5: return "RGB";
        case 0: return "XYZ";
        case 3: return "YCbCr";
        case 4: return "Yxy";
        default: return "type " + type + " unknown";
        }
    }

    public static String describeImage(RenderedImage image) {
        return describeImage(image, image.getClass());
    }

    public static String describeImage(RenderedImage image, Class clazz) {
        int cb = getNumColorComponents(image);
        var sm = image.getSampleModel();
        int nb = sm.getNumBands();
        return image.getWidth() + "x" + image.getHeight() + " " + nb + " band " +
            (nb != cb ? ("(" + cb + " color bands) ") : "") + getImageDataTypeName(sm.getDataType()) + " " +
            describeColorModel(image) + " " + clazz.getSimpleName() + ", " +
            Utils.kmg(getImageBytes(image)) + " expected bytes," +
            " JAI tile size " + image.getTileWidth() + "x" + image.getTileHeight();
    }

    @SuppressWarnings("unchecked")
    public static void spewMetadata(IIOMetadata md, Logger log, String pfx) {

        final var sb = new StringBuilder();

        Consumer<Integer> indent = (level) -> {
            for (int i = 0; i < level; i++) {
                sb.append(" ");
            }
        };

        Consumer<NamedNodeMap> spewAttribs = (attrs) -> {
            if (attrs != null) {
                int n = attrs.getLength();
                for (int i = 0; i < n; i++) {
                    Node attr = attrs.item(i);
                    sb.append(" ");
                    sb.append(attr.getNodeName());
                    sb.append("='");
                    sb.append(attr.getNodeValue());
                    sb.append("'");
                }
            }
        };

        final Object[] sn = new Object[1];
        BiConsumer<Node, Integer> spewNode = (node, level) -> {
            indent.accept(level);
            sb.append("<");
            sb.append(node.getNodeName());
            spewAttribs.accept(node.getAttributes());
            Node child = node.getFirstChild();
            if (child == null) {
                String value = node.getNodeValue();
                if (value == null || value.length() == 0) {
                    sb.append("/>\n");
                } else {
                sb.append(">");
                sb.append(value);
                sb.append("<");
                sb.append(node.getNodeName());
                sb.append(">\n");
                }
            } else {
                sb.append(">\n");
                while (child != null) {
                    ((BiConsumer<Node, Integer>)(sn[0])).accept(child, level + 1);
                    child = child.getNextSibling();
                }
                indent.accept(level);
                sb.append("</");
                sb.append(node.getNodeName());
                sb.append(">\n");
            }
        };
        sn[0] = spewNode;
     
        String[] mds = md.getMetadataFormatNames();
        String nmd = md.getNativeMetadataFormatName();
        String smd = IIOMetadataFormatImpl.standardMetadataFormatName;
        
        log.debug(pfx + "has " + mds.length + " metadata formats: " + String.join(", ", mds));
        for (int i = 0; i < mds.length; i++)
        {
            sb.setLength(0);
            Node n = md.getAsTree(mds[i]);
            if (n != null) {
                spewNode.accept(n, 1);
            } else {
                sb.append("(null)");
            }
            log.debug(pfx + "metadata format \"" + mds[i] + "\"" +
                      (mds[i].equals(nmd) ? " (native)" : mds[i].equals(smd) ? " (standard)" : "") + ":\n" +
                      sb.toString());
        }
    }

    public static void spewMetadata(IIOMetadata md, Logger log) {
        spewMetadata(md, log, "");
    }
}
