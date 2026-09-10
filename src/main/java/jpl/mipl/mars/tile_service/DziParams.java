package jpl.mipl.mars.tile_service;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.function.Function;
import java.util.regex.Pattern;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.digest.DigestUtils;

import jpl.mipl.mars.viewer.api.Constants;
import jpl.mipl.mars.viewer.image.config.ImageConfiguration;
import jpl.mipl.mars.viewer.image.config.ImageConfigurationSingleton;
import jpl.mipl.mars.viewer.finder.AbstractMarsImageFileFinder;
import jpl.mipl.mars.viewer.finder.mission.m20combined.M20FlatUberImageFileFinder;
import jpl.mipl.mars.viewer.finder.mission.mslcombined.MslUberOdsImageFileFinder;
import jpl.mipl.mars.viewer.finder.mission.cadre.CadreOdsImageFileFinder;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonArrayBuilder;
import javax.json.JsonValue;
import javax.json.JsonString;
import javax.json.JsonNumber;
import javax.json.JsonArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Marsette Vona
 */
public class DziParams {
    
    public static final Logger log = LoggerFactory.getLogger(DziTask.class);

    public static final String[] SUPPORTED_MISSIONS = { "MSL", "M20", "CADRE" };
    public static final String DEFAULT_MISSION = "M20";

    //originally the tiler lambda would trigger on IMG but not VIC
    //and the MV frontend would request .IMG but not .VIC
    //but after that ticket, we swapped to prefer VIC
    //we had been using the full source image URL to generate the cache hash
    //so now if we get a request for *either* a VIC or a IMG, use the IMG form for the cache hash
    public static final String CACHE_PDS_EXT = "IMG";

    public static final String DEF_TILE_FORMAT = "png";
    
    //default from https://github.com/usnistgov/pyramidio/blob/1d5c802fa8641cfdd00cdea02f416ea350edd92b/pyramidio/src/main/java/gov/nist/isg/pyramidio/ScalablePyramidBuilder.java#L36
    public static final int DEF_TILE_SIZE = 254;
    
    //default from https://github.com/usnistgov/pyramidio/blob/1d5c802fa8641cfdd00cdea02f416ea350edd92b/pyramidio/src/main/java/gov/nist/isg/pyramidio/ScalablePyramidBuilder.java#L36
    public static final int DEF_TILE_OVERLAP = 1;
    
    public static final int DEF_THUMB_HEIGHT = 160;

    public enum StretchType { percent, extrema, relative, none };
    public static final StretchType DEF_STRETCH_TYPE = StretchType.percent;

    public static final float DEF_PERCENT_STRETCH_LOW = 0.5f; //range 0-100
    public static final float DEF_PERCENT_STRETCH_HIGH = 0.5f; //range 0-100

    public static final float DEF_RELATIVE_STRETCH_LOW = 1; //range 0-1
    public static final float DEF_RELATIVE_STRETCH_HIGH = 1; //range 0-1

    //value from jpl.mipl.jade.LessSimpleOverlayer
    public static final float DEF_OVERLAY_ALPHA = 220.0f / 255.0f; //range 0-1

    public enum GammaMode { none, passthrough, convert, lineartosrgb, srgbtolinear };
    public static final GammaMode DEF_GAMMA_MODE = GammaMode.convert;

    public static final String SHA1_REGEX = "[a-fA-F0-9]{40}";
    public static final Pattern HASH_PATTERN = Pattern.compile(SHA1_REGEX + "(/cfg/" + SHA1_REGEX + ")?");
    
    public final String rdrUrl; //always has forward slash separators
    
    public final String productType; //three letter code from product ID, or null if unknown
    
    //NOTE: this is the image type corresponding to the product type in the rdrUrl product ID
    //the frontend may supply it as a URL parameter, which it may get from the image_type metadata in OCS
    //and that is in turn populated in a mission dependent way by parsing the rdrUrl when it was ingested
    //so this is computable from rdrUrl if it was not given as a URL param, if you also know what mission to use
    //null if unknown
    public final String rdrType;

    public final String instrument; //two letter code from product ID, or null if unknown

    public final boolean isPDS, isCRISP, isICM, isQuicklook, isUserUpload, isRaw, isEDLCam, isDef15Bit;
    public final boolean isPreappliedGamma;
    
    public final boolean rdrUrlIsS3, rdrUrlIsHTTP;
    public final boolean overlayable;
    public final boolean stretchable;
    
    public final String tileFormat;
    public final String tileMimeType;
    
    public final int tileSize;
    public final int tileOverlap;
    public final int thumbHeight;
    
    public final StretchType stretchType;
    public final float stretchLow;
    public final float stretchHigh;
    
    public final List<String> overlayPreferences = new ArrayList<String>();
    
    public final boolean maskBlack;
    public final float unmaskedAlpha;
    public final float maskedAlpha;
    
    public final GammaMode gammaMode;
    
    public final boolean isNominal;
    public final String hashCode;
    
    public final boolean doMonolithic;
    public final boolean noDZI;
    public final boolean forceCustom;
    
    public final boolean skipCached;

    public final int maxWaitSec;

    public volatile boolean force;
    
    public static String fsInputDir = "";

    public String pfx;

    public static Map<String, Object> defArgs = new HashMap<String, Object>();
    
    public static void setDefaults(Function<String, String> getter, Map<String, String> overrides) {
        
        Function<String, String> get = (name) -> {
            return overrides != null && overrides.containsKey(name) ? overrides.get(name) : getter.apply(name);
        };
        
        for (String name : new String[] { "tile_format", "tile_size", "tile_overlap", "thumb_height",
                                          "stretch_type", "stretch_low", "stretch_high", "mask_black", 
                                          "overlay_alpha", "unmasked_alpha", "masked_alpha", "gamma_mode" } ) {
            defArgs.put(name, get.apply(name.toUpperCase()));
        }
    }
    
    public static void setDefaults(Function<String, String> getter) {
        setDefaults(getter, null);
    }

    public static boolean isHashCode(String str) {
        return HASH_PATTERN.matcher(str).matches();
    }
    
    public static String getHashCode(String rdrUrl) {
        if (CACHE_PDS_EXT != null && Utils.hasExt(rdrUrl, DziTask.PDS_EXTS)) {
            rdrUrl = Utils.setExt(rdrUrl, CACHE_PDS_EXT);
        }
        rdrUrl = rdrUrl.replace(File.separator, "/");
        if (S3Helper.isS3Url(rdrUrl)) {
            rdrUrl = S3Helper.getS3Url(rdrUrl); //normalize http[s]:// to s3://
        } else if (!HTTPHelper.isHTTPUrl(rdrUrl) && !rdrUrl.toLowerCase().startsWith("file://")) {
            rdrUrl = "file://" + rdrUrl;
        }
        return DigestUtils.sha1Hex(rdrUrl);
    }

    public static String checkMission(String mission) {
        if (mission == null || mission.trim().length() == 0) {
            mission = DEFAULT_MISSION;
        } else {
            mission = mission.trim();
        }
        mission = mission.toUpperCase();
        if ("M2020".equals(mission)) {
            mission = "M20";
        }
        final String fm = mission;
        if (!Arrays.stream(SUPPORTED_MISSIONS).anyMatch(m -> m.equals(fm))) {
            log.warn("unknown mission " + mission);
        }
        return mission;
    }

    private static AbstractMarsImageFileFinder getFileFinder(String mission) {
        try {
            switch (checkMission(mission)) {
            case "MSL": return new MslUberOdsImageFileFinder("https://");
            case "M20": return new M20FlatUberImageFileFinder("https://");
            case "CADRE": return new CadreOdsImageFileFinder(fsInputDir);
            default: throw new IllegalStateException("unknown mission " + mission);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("error instantiating file finder: " + ex.getMessage());
        }
    }

    private static ImageConfiguration getImageConfig() {
        try {
            return ImageConfigurationSingleton.getConfiguration();
        } catch (Exception ex) {
            throw new IllegalStateException("error instantiating image configuration: " + ex.getMessage());
        }
    }

    //this returns the three-letter product type snipped out of the product ID, e.g. ECV
    //also see getRDRType()
    private String getProductType(String filename) {
        try { 
            filename = Utils.getFilename(filename);
            if (Utils.anyMatch(DziTask.icmPatterns, "/" + filename)) {
                return "DSP"; //disparity
            }
            if (Utils.anyMatch(DziTask.quicklookPatterns, "/" + filename)) {
                return "EDR";
            }
            if (Utils.anyMatch(DziTask.crispPatterns, "/" + filename)) {
                filename = extractCrispProductId(filename);
                if (filename == null) {
                    throw new Exception("error getting product ID for CRISP product");
                }
            }
            var ff = getFileFinder(DziTask.mission);
            String productType = ff.extractImageType(filename, Constants.PRODUCT_TYPE_RDR);
            if (productType == null || productType.trim().isEmpty()) {
                throw new Exception(DziTask.mission + " file finder returned null or empty product type");
            }
            return productType;
        } catch (Exception ex) {
            log.warn(pfx + "error getting product type for " + filename + ", defaulting to EDR: " + ex.getMessage());
            return "EDR";
        }
    }

    private String extractCrispProductId(String filename) {
        int lastDash = filename.lastIndexOf('-');
        int lastDot = filename.lastIndexOf('.');
        if (lastDash <= 0 || lastDot <= lastDash) {
            return null;
        }
        String productId = filename.substring(lastDash + 1, lastDot);
        String ext = filename.substring(lastDot);
        int expectedLength = -1;
        switch (DziTask.mission) {
            case "MSL": expectedLength = 36; break;
            case "M20": expectedLength = 54; break;
            case "CADRE": expectedLength = 41; break;
            default: {
                log.warn(pfx + "CRISP product ID extraction not implemented for mission \"" + DziTask.mission + "\"");
                return null;
            }
        }
        int idLength = productId.length();
        if (idLength != expectedLength && DziTask.crispSuffixen != null) {
            for (int i = 0; i < DziTask.crispSuffixen.length; i++) {
                if (DziTask.crispSuffixen[i] != null &&
                    (idLength + DziTask.crispSuffixen[i].length()) == expectedLength) {
                    productId = productId + DziTask.crispSuffixen[i];
                    break;
                }
            }
        }
        return productId.length() == expectedLength ? (productId + ext) : null;
    }

    private String getInstrumentId(String filename) {
        try { 
            filename = Utils.getFilename(filename);
            if (Utils.anyMatch(DziTask.crispPatterns, "/" + filename)) {
                filename = extractCrispProductId(filename);
                if (filename == null) {
                    log.warn(pfx + "error getting instrument for CRISP product");
                    return null;
                }
            }
            var ff = getFileFinder(DziTask.mission);
            String instrumentId = ff.extractInstrumentId(filename);
            if (instrumentId == null || instrumentId.trim().isEmpty()) {
                log.warn(DziTask.mission + " file finder returned null or empty instrument ID for " + filename);
                return null;
            }
            return instrumentId;
        } catch (Exception ex) {
            log.warn(pfx + "error getting instrument ID for " + filename + ": " + ex.getMessage());
            return null;
        }
    }

    //this returns the marsviewer RDR type string, e.g. video_image for product type ECV
    //also see getProductType()
    private String getRDRType(String productType) {
        try {
            var ic = getImageConfig();
            var ff = getFileFinder(DziTask.mission);
            String rdrType = ic.getImageTypeId(ff.getProductNamespace(), productType);
            if (rdrType == null) {
                log.warn(pfx + "no RDR type for product type " + productType);
                rdrType = productType;
            }
            return rdrType;
        } catch (Exception ex) {
            log.warn(pfx + "error getting RDR type for product type " + productType + ": " + ex.getMessage());
            return productType;
        }
    }

    private boolean isOverlayable(String rdrType) {
        try {
            var ic = getImageConfig();
            var imageLookup = ic.getImageLookup();
            if (imageLookup == null) {
                throw new IllegalStateException("no image lookup"); //turns into false below
            }
            return imageLookup.isOverlayable(rdrType);
        } catch (Exception ex) {
            log.warn(pfx + "error checking if RDR type " + rdrType + " is overlayable: " + ex.getMessage());
            return false;
        }
    }
        
    public DziParams(String url, Map<String, Object> args) {

        pfx = DziTask.getVersion() + " ";

        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("no input URL");
        }

        rdrUrlIsS3 = S3Helper.isS3Url(url); //check first to catch http[s]://BUCKET.s3-REGION.amazonaws.com/KEY
        rdrUrlIsHTTP = !rdrUrlIsS3 && HTTPHelper.isHTTPUrl(url);

        url = url.replace(File.separator, "/"); //always normalize slashes

        pfx = DziTask.getVersion() + " [" + url + "] ";

        //don't do this here
        //the lambda always does this first
        //the CLI never does this
        //the service does this if so configured
        //if (!filterUrl(url)) {
        //    throw new IllegalArgumentException("unsupported input file");
        //}

        String fullUrl = url;
        if (fullUrl.indexOf('/') < 0) {
            fullUrl = "/" + fullUrl;
        }

        if (!rdrUrlIsS3 && !rdrUrlIsHTTP) {
            url = fsInputDir + url;
        }

        this.rdrUrl = url;

        isPDS = Utils.hasExt(url, DziTask.PDS_EXTS);
        isCRISP = Utils.anyMatch(DziTask.crispPatterns, url);
        isICM = Utils.anyMatch(DziTask.icmPatterns, url);
        isQuicklook = Utils.anyMatch(DziTask.quicklookPatterns, url);
        isUserUpload = Utils.anyMatch(DziTask.userUploadPatterns, url);

        var argsOrDef = args != null ? args : defArgs;

        if (DziTask.debug && DziTask.verbose) {
            for (var entry : argsOrDef.entrySet()) {
                log.debug(pfx + entry.getKey() + "=" + entry.getValue());
            }
        }

        String defProductType = getProductType(url);
        productType = Utils.getStringArg(args, "product_type", defProductType);

        String defRDRType = getRDRType(productType);
        rdrType = Utils.getStringArg(args, "rdr_type", defRDRType);

        instrument = getInstrumentId(url);

        isRaw = productType.equalsIgnoreCase("ECM") || productType.equalsIgnoreCase("ECV");
        isDef15Bit = Utils.anyMatch(DziTask.fifteenBitPatterns, productType);
        isEDLCam = instrument != null && instrument.startsWith("E");
        isPreappliedGamma =
            Utils.anyMatch(DziTask.preappliedGammaPatterns, productType) ||
            Utils.anyMatch(DziTask.preappliedInstGammaPatterns, instrument);
        //note: isPreappliedGamma includes preappliedGammaPatterns (blanket product type)i
        //and preappliedInstGammaPatterns (blanket instrument)
        //but not preapplied8BitGammaPatterns because that will require downloading the headers which is done later

        if (Utils.hasExt(url, DziTask.PLAIN_EXTS)) {
            overlayable = false;
            stretchable = true;
        } else if (Utils.hasExt(url, DziTask.PDS_EXTS)) {
            overlayable = isOverlayable(rdrType);
            stretchable = !overlayable;
        } else {
            throw new IllegalArgumentException("unsupported input file extension");
        }

        String defTileFormat = Utils.getStringArg(defArgs, "tile_format", DEF_TILE_FORMAT);
        tileFormat = Utils.getStringArg(args, "tile_format", defTileFormat).toLowerCase();
        tileMimeType = Utils.formatToMimeType(tileFormat); //also validates

        int defTileOverlap = Utils.getIntArg(defArgs, "tile_overlap", DEF_TILE_OVERLAP, Utils::isNonNegative);
        tileOverlap = Utils.getIntArg(args, "tile_overlap", defTileOverlap, Utils::isNonNegative);

        int defTileSize = Utils.getIntArg(defArgs, "tile_size", DEF_TILE_SIZE, Utils::isPositive);
        tileSize = Utils.getIntArg(args, "tile_size", defTileSize, Utils::isPositive);

        int defThumbHeight = Utils.getIntArg(defArgs, "thumb_height", DEF_THUMB_HEIGHT, Utils::isPositive);
        thumbHeight = Utils.getIntArg(args, "thumb_height", defThumbHeight, Utils::isPositive);

        handleLegacyStretch(defArgs);
        if (args != null) {
            handleLegacyStretch(args);
        }
        var defStretchType = Utils.getEnumArg(defArgs, "stretch_type", DEF_STRETCH_TYPE, StretchType.class);
        if (isQuicklook || isUserUpload || isEDLCam || isRaw) {
            defStretchType = StretchType.none;
        }
        stretchType = Utils.getEnumArg(args, "stretch_type", defStretchType, StretchType.class);

        float defStretchLow = Float.NaN;
        float defStretchHigh = Float.NaN;
        if (stretchType == StretchType.percent) {
            defStretchLow = Utils.getFloatArg(defArgs, "stretch_low", DEF_PERCENT_STRETCH_LOW, Utils::isPercent);
            stretchLow = Utils.getFloatArg(args, "stretch_low", defStretchLow, Utils::isPercent);
            defStretchHigh = Utils.getFloatArg(defArgs, "stretch_high", DEF_PERCENT_STRETCH_HIGH, Utils::isPercent);
            stretchHigh = Utils.getFloatArg(args, "stretch_high", defStretchHigh, Utils::isPercent);
        } else if (stretchType == StretchType.extrema) {
            stretchLow = Utils.getFloatArg(args, "stretch_low", defStretchLow, Utils::isNonNaN);
            stretchHigh = Utils.getFloatArg(args, "stretch_high", defStretchHigh, Utils::isNonNaN);
        } else if (stretchType == StretchType.relative) {
            defStretchLow = Utils.getFloatArg(defArgs, "stretch_low", DEF_RELATIVE_STRETCH_LOW, Utils::isAlpha);
            stretchLow = Utils.getFloatArg(args, "stretch_low", defStretchLow, Utils::isAlpha);
            defStretchHigh = Utils.getFloatArg(defArgs, "stretch_high", DEF_RELATIVE_STRETCH_HIGH, Utils::isAlpha);
            stretchHigh = Utils.getFloatArg(args, "stretch_high", defStretchHigh, Utils::isAlpha);
        } else {
            stretchLow = defStretchLow;
            stretchHigh = defStretchHigh;
        }

        if (overlayable && argsOrDef.containsKey("overlay_preferences")) {
            Object op = argsOrDef.get("overlay_preferences");
            if (op instanceof String[]) {
                for (String pref : (String[])op) {
                    if (pref != null && !pref.isEmpty()) {
                        if (!DziTask.PREF_PATTERN.matcher(pref).matches()) {
                            throw new IllegalArgumentException("invalid overlay preference format: " + pref);
                        }
                        overlayPreferences.add(pref);
                    }
                }
            } else if (op != null) {
                throw new IllegalArgumentException("overlayPreferences must be an array of strings");
            }
        }

        boolean defMaskBlack = false;
        float defOverlayAlpha = DEF_OVERLAY_ALPHA;
        float overlayAlpha = defOverlayAlpha;
        float defUnmaskedAlpha = 1;
        float defMaskedAlpha = 1;
            
        if (tileMimeType.equals("image/png")) { //only png output supports alpha channel

            defMaskBlack = Utils.getBoolArg(defArgs, "mask_black", isCRISP);
            maskBlack = Utils.getBoolArg(args, "mask_black", defMaskBlack);

            defOverlayAlpha = Utils.getFloatArg(defArgs, "overlay_alpha", DEF_OVERLAY_ALPHA, Utils::isAlpha);
            overlayAlpha = Utils.getFloatArg(args, "overlay_alpha", defOverlayAlpha, Utils::isAlpha);

            defUnmaskedAlpha = overlayable ? overlayAlpha : 1;
            defUnmaskedAlpha = Utils.getFloatArg(defArgs, "unmasked_alpha", defUnmaskedAlpha, Utils::isAlpha);
            unmaskedAlpha = Utils.getFloatArg(args, "unmasked_alpha", defUnmaskedAlpha, Utils::isAlpha);

            defMaskedAlpha = (overlayable || isCRISP) ? 0 : 1;
            defMaskedAlpha = Utils.getFloatArg(defArgs, "masked_alpha", defMaskedAlpha, Utils::isAlpha);
            maskedAlpha = Utils.getFloatArg(args, "masked_alpha", defMaskedAlpha, Utils::isAlpha);
        } else {
            maskBlack = defMaskBlack;
            unmaskedAlpha = defUnmaskedAlpha;
            maskedAlpha = defMaskedAlpha;
        }

        var defGammaMode = Utils.getEnumArg(defArgs, "gamma_mode", DEF_GAMMA_MODE, GammaMode.class);
        gammaMode = Utils.getEnumArg(args, "gamma_mode", defGammaMode, GammaMode.class);

        force = Utils.getBoolArg(args, "force", false);

        maxWaitSec = Utils.getIntArg(args, "max_wait_sec", -1, Utils::isNonNegative);

        skipCached = Utils.getBoolArg(args, "skip_cached", false);

        boolean isDef = true;
        isDef &= productType.toLowerCase().equals(defProductType.toLowerCase());
        isDef &= rdrType.toLowerCase().equals(defRDRType.toLowerCase());
        isDef &= tileFormat.toLowerCase().equals(defTileFormat.toLowerCase());
        isDef &= tileSize == defTileSize;
        isDef &= tileOverlap == defTileOverlap;
        isDef &= thumbHeight == defThumbHeight;
        if (stretchable) {
            isDef &= stretchType == defStretchType;
            isDef &= stretchLow == defStretchLow || (Float.isNaN(stretchLow) && Float.isNaN(defStretchLow));
            isDef &= stretchHigh == defStretchHigh || (Float.isNaN(stretchHigh) && Float.isNaN(defStretchHigh));
        }
        if (overlayable) {
            isDef &= overlayAlpha == defOverlayAlpha;
            isDef &= overlayPreferences.isEmpty();
        }
        isDef &= maskBlack == defMaskBlack;
        isDef &= unmaskedAlpha == defUnmaskedAlpha;
        isDef &= maskedAlpha == defMaskedAlpha;
        isDef &= gammaMode == defGammaMode;

        doMonolithic = Utils.getBoolArg(args, "do_monolithic", false);
        noDZI = Utils.getBoolArg(args, "no_dzi", false);
        forceCustom = Utils.getBoolArg(args, "force_custom", false);

        isNominal = isDef && !forceCustom;

        if (DziTask.debug) {
            log.debug(pfx + "productType=" + productType + ", defProductType=" + defProductType);
            log.debug(pfx + "rdrType=" + rdrType + ", defRDRType=" + defRDRType + ", instrument=" + instrument);
            log.debug(pfx + "tileFormat=" + tileFormat + ", defTileFormat=" + defTileFormat);
            log.debug(pfx + "tileSize=" + tileSize + ", defTileSize=" + defTileSize);
            log.debug(pfx + "tileOverlap=" + tileOverlap + ", defTileOverlap=" + defTileOverlap);
            log.debug(pfx + "thumbHeight=" + thumbHeight + ", defThumbHeight=" + defThumbHeight);
            log.debug(pfx + "stretchable=" + stretchable + ", overlayable=" + overlayable);
            log.debug(pfx + "stretchType=" + stretchType + ", defStretchType=" + defStretchType);
            log.debug(pfx + "stretchLow=" + stretchLow + ", defStretchLow=" + defStretchLow);
            log.debug(pfx + "stretchHigh=" + stretchHigh + ", defStretchHigh=" + defStretchHigh);
            log.debug(pfx + "overlayAlpha=" + overlayAlpha + ", defOverlayAlpha=" + defOverlayAlpha);
            log.debug(pfx + "overlayPreferences.isEmpty()=" + overlayPreferences.isEmpty());
            log.debug(pfx + "isPDS=" + isPDS + ", isCRISP=" + isCRISP + ", isICM=" + isICM +
                      ", isQuicklook=" + isQuicklook + ", isUserUpload=" + isUserUpload + ", isEDLCam=" + isEDLCam);
            log.debug(pfx + "isRaw=" + isRaw + ", isDef15Bit=" + isDef15Bit +
                      ", isPreappliedGamma=" + isPreappliedGamma);
            log.debug(pfx + "maskBlack=" + maskBlack + ", defMaskBlack=" + defMaskBlack);
            log.debug(pfx + "unmaskedAlpha=" + unmaskedAlpha + ", defUnmaskedAlpha=" + defUnmaskedAlpha);
            log.debug(pfx + "maskedAlpha=" + maskedAlpha + ", defMaskedAlpha=" + defMaskedAlpha);
            log.debug(pfx + "gammaMode=" + gammaMode + ", defGammaMode=" + defGammaMode);
            log.debug(pfx + "isNominal=" + isNominal);
            log.debug(pfx + "doMonolithic=" + doMonolithic + ", noDZI=" + noDZI);
            log.debug(pfx + "forceCustom=" + forceCustom);
        }

        //cache non-default processing in a subdir of the default processing cache dir
        //so that if the original product is deleted the lambda will delete the whole subtree
        String urlHash = getHashCode(url);
        hashCode = isNominal ? urlHash : (urlHash + "/cfg/" + DigestUtils.sha1Hex(toJson()));

        pfx = DziTask.getVersion() + " [" + url + " -> " + hashCode + "] ";
    }

    private static void handleLegacyStretch(Map<String, Object> args) {
        //accept stretch_type=histogram_percent and extrema_stretch=true for backward compat
        if (args.containsKey("stretch_type") &&
            "histogram_percent".equalsIgnoreCase((String)(args.get("stretch_type")))) {
            args.put("stretch_type", StretchType.percent.name());
        }
        if (Utils.getBoolArg(args, "extrema_stretch", false)) {
            args.put("stretch_type", StretchType.extrema.name());
        }
    }

    public DziParams(String url) {
        this(url, null);
    }

    public DziParams(Map<String, Object> args) {
        this((String)(args.get("rdr_url")), args);
    }

    private static Map<String, Object> parseRequest(HttpServletRequest request) throws IOException {
        if ("GET".equals(request.getMethod())) {
            var img = request.getParameter("image");
            if (img == null) {
                throw new IllegalArgumentException("missing image parameter");
            }
            var args = new HashMap<String, Object>();
            args.put("rdr_url", img);
            //product_type
            args.put("rdr_type", request.getParameter("rdr"));
            args.put("tile_format", request.getParameter("format"));
            args.put("stretch_type", request.getParameter("stretchType"));
            if (Utils.parseBool(request.getParameter("extremaStretch"))) {
                args.put("stretch_type", StretchType.extrema.name()); //legacy compat
            }
            args.put("stretch_low", request.getParameter("stretchLow"));
            args.put("stretch_high", request.getParameter("stretchHigh"));
            args.put("overlay_preferences", request.getParameterValues("overlayPreference"));
            args.put("tile_size", request.getParameter("tileSize"));
            args.put("tile_overlap", request.getParameter("tileOverlap"));
            args.put("thumb_height", request.getParameter("thumbHeight"));
            args.put("mask_black", request.getParameter("maskBlack"));
            args.put("overlay_alpha", request.getParameter("overlayAlpha"));
            args.put("unmasked_alpha", request.getParameter("unmaskedAlpha"));
            args.put("masked_alpha", request.getParameter("maskedAlpha"));
            args.put("gamma_mode", request.getParameter("gammaMode"));
            args.put("force", request.getParameter("force"));
            args.put("max_wait_sec", request.getParameter("maxWaitSec"));
            args.put("do_monolithic", request.getParameter("doMonolithic"));
            args.put("no_dzi", request.getParameter("noDZI"));
            args.put("force_custom", request.getParameter("forceCustom"));
            return args;
        } else if ("POST".equals(request.getMethod()) && "application/json".equals(request.getContentType())) {
            try (var reader = request.getReader()) {
                return parseJson(reader);
            }
        } else {
            throw new IllegalArgumentException("unrecognized HTTP request method " + request.getMethod() +
                                               " and/or content type " + request.getContentType() +
                                               ", must be either GET or POST application/json");
        }
    }

    public DziParams(HttpServletRequest request) throws IOException {
        this(parseRequest(request));
    }

    public JsonObjectBuilder toJson(JsonObjectBuilder json, boolean includeStretch, boolean shortIfNominal) {
        json.add("rdr_url", rdrUrl);
        if (!isNominal || !shortIfNominal) {
            json.add("product_type", productType);
            json.add("rdr_type", rdrType);
            json.add("tile_format", tileFormat);
            json.add("tile_size", tileSize);
            json.add("tile_overlap", tileOverlap);
            json.add("thumb_height", thumbHeight);
            json.add("mask_black", maskBlack);
            json.add("unmasked_alpha", unmaskedAlpha);
            json.add("masked_alpha", maskedAlpha);
            if (stretchable && includeStretch) {
                //using "histogram_percent" for backward compatibility here
                json.add("stretch_type", stretchType == StretchType.percent ? "histogram_percent" : stretchType.name());
                if (!Float.isNaN(stretchLow)) {
                    json.add("stretch_low", stretchLow);
                } else {
                    json.add("stretch_low", "auto");
                }
                if (!Float.isNaN(stretchHigh)) {
                    json.add("stretch_high", stretchHigh);
                } else {
                    json.add("stretch_high", "auto");
                }
            }
            json.add("gamma_mode", gammaMode.name());
            if (overlayable && !overlayPreferences.isEmpty()) {
                JsonArrayBuilder prefs = Json.createArrayBuilder();
                for (String pref : overlayPreferences) {
                    prefs.add(pref);
                }
                json.add("overlay_preferences", prefs);
            }
        }
        return json;
    }

    public String toJson() {
        return toJson(Json.createObjectBuilder(), true, true).build().toString();
    }

    public static DziParams fromJson(JsonObject json) {
        return new DziParams(parseJson(json));
    }

    public static DziParams fromJson(Reader reader) {
        return new DziParams(parseJson(reader));
    }

    public static DziParams fromJson(String json) {
        return new DziParams(parseJson(json));
    }

    public static Map<String, Object> parseJson(JsonObject json) {

        var args = new HashMap<String, Object>();

        if (!json.containsKey("rdr_url")) {
            throw new IllegalArgumentException("JSON missing rdr_url");
        }
        var rdrUrl = json.get("rdr_url");
        if (rdrUrl instanceof JsonString) {
            args.put("rdr_url", ((JsonString)rdrUrl).getString());
        } else if (rdrUrl != JsonValue.NULL) {
            throw new IllegalArgumentException("JSON value for rdr_url is a " + rdrUrl.getValueType() +
                                               ", not string");
        }

        for (String name : new String[] { "rdr_type", "tile_format", "gamma_mode", "stretch_type" })  {
            if (json.containsKey(name)) {
                var val = json.get(name);
                if (val instanceof JsonString) {
                    args.put(name, ((JsonString)val).getString());
                } else if (val != JsonValue.NULL) {
                    throw new IllegalArgumentException("JSON value for " + name + " is a " + val.getValueType() +
                                                       ", not string");
                }
            }
        }

        for (String name : new String[] { "tile_size", "tile_overlap", "thumb_height" })  {
            if (json.containsKey(name)) {
                var val = json.get(name);
                if (val instanceof JsonNumber) {
                    args.put(name, ((JsonNumber)val).intValue());
                } else if (val != JsonValue.NULL) {
                    throw new IllegalArgumentException("JSON value for " + name + " is a " + val.getValueType() +
                                                       ", not number");
                }
            }
        }

        for (String name : new String[] { "mask_black", "force", "do_monolithic", "no_dzi", "force_custom" })  {
            if (json.containsKey(name)) {
                var val = json.get(name);
                if (val == JsonValue.FALSE) {
                    args.put(name, false);
                } else if (val == JsonValue.TRUE) {
                    args.put(name, true);
                } else if (val != JsonValue.NULL) {
                    throw new IllegalArgumentException("JSON value for " + name + " is a " + val.getValueType() +
                                                       ", not true or false");
                }
            }
        }

        for (String name : new String[] { "stretch_low", "stretch_high" })  {
            if (json.containsKey(name)) {
                var val = json.get(name);
                if (val instanceof JsonString) {
                    args.put(name, ((JsonString)val).getString());
                } else if (val instanceof JsonNumber) {
                    args.put(name, (float)((JsonNumber)val).doubleValue());
                } else if (val != JsonValue.NULL) {
                    throw new IllegalArgumentException("JSON value for " + name + " is a " + val.getValueType() +
                                                       ", not a string or number");
                }
            }
        }

        for (String name : new String[] { "max_wait_sec", "overlay_alpha", "unmasked_alpha", "masked_alpha" })  {
            if (json.containsKey(name)) {
                var val = json.get(name);
                if (val instanceof JsonNumber) {
                    args.put(name, (float)((JsonNumber)val).doubleValue());
                } else if (val != JsonValue.NULL) {
                    throw new IllegalArgumentException("JSON value for " + name + " is a " + val.getValueType() +
                                                       ", not a number");
                }
            }
        }

        if (json.containsKey("overlay_preferences") && json.get("overlay_preferences") != JsonValue.NULL) {
            var val = json.get("overlay_preferences");
            if (val instanceof JsonArray) {
                var prefs = new ArrayList<String>();
                for (int i = 0; i < ((JsonArray)val).size(); i++) {
                    var pv = ((JsonArray)val).get(i);
                    if (pv instanceof JsonString) {
                        prefs.add(((JsonString)pv).getString());
                    } else if (pv != JsonValue.NULL) {
                        throw new IllegalArgumentException("JSON value for overlay_preferences[" + i + "] is a " +
                                                           pv.getValueType() + ", not string");
                    }
                }
                args.put("overlay_preferences", prefs.toArray(new String[0]));
            } else if (val != JsonValue.NULL) {
                throw new IllegalArgumentException("JSON value for overlay_preferences is a " + val.getValueType() +
                                                   ", not an array");
            }
        }

        return args;
    }

    public static Map<String, Object> parseJson(Reader reader) {
        return parseJson(Json.createReader(reader).readObject());
    }

    public static Map<String, Object> parseJson(String json) {
        return parseJson(new StringReader(json));
    }
}
