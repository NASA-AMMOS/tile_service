package jpl.mipl.mars.tile_service;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Files;
import java.nio.file.Paths;

import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.RenderingHints;
import java.awt.image.DataBuffer;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.awt.image.BandedSampleModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.awt.color.ColorSpace;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import javax.media.jai.JAI;
import javax.media.jai.Histogram;
import javax.media.jai.RenderedOp;
import javax.media.jai.ImageLayout;
import javax.media.jai.ROI;
import javax.media.jai.ROIShape;
import javax.media.jai.util.ImagingListener;
import com.sun.media.jai.util.SunTileCache;
import javax.media.jai.operator.HistogramDescriptor;
import javax.media.jai.operator.ExtremaDescriptor;
import javax.media.jai.operator.NullDescriptor;
import javax.media.jai.operator.BandSelectDescriptor;
import javax.media.jai.operator.ThresholdDescriptor;
import javax.media.jai.operator.AbsoluteDescriptor;
import javax.media.jai.operator.MultiplyConstDescriptor;
import javax.media.jai.operator.BandCombineDescriptor;
import javax.media.jai.operator.FormatDescriptor;
import javax.media.jai.operator.ConstantDescriptor;

import org.apache.commons.lang3.mutable.MutableLong;

import jpl.mipl.mars.viewer.MarsImageViewModel;
import jpl.mipl.mars.viewer.api.Constants;
import jpl.mipl.mars.viewer.api.ApplicationPropertyManager;
import jpl.mipl.mars.viewer.util.ImagePreferences;
import jpl.mipl.mars.viewer.util.ImagePreferencesParseUtil;
import jpl.mipl.mars.viewer.image.RdrImageContentFactory;

import jpl.mipl.io.plugins.VicarRenderedImage;
import jpl.mipl.io.plugins.VicarImageReader;

import gov.nist.isg.pyramidio.ScalablePyramidBuilder;
import gov.nist.isg.pyramidio.TileBuilder;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import net.coobird.thumbnailator.Thumbnails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Marsette Vona
 */
public class DziTask {

    public static final Logger log = LoggerFactory.getLogger(DziTask.class);

    //with the hack in getImageReader() to properly limit the (tile) width/height of the VicarImageReader
    //we don't actually have to limit IMG/VIC total pixels
    public static final boolean DEF_LIMIT_PDS_HEIGHT = false;

    //typically set one or the other of these
    public static final boolean DEF_LIMIT_NON_PDS_HEIGHT = false;
    public static final boolean DEF_FULL_LOAD_SMALL_PDS = false;

    public static final boolean DEF_TILE_WINDOW_LARGE_NON_PDS = true;
    public static final float DEF_MIN_TILE_WINDOW_CHUNK = 0;

    public enum HistogramMode { jai, reverseRaster, reverseQuadtree };
    public static final HistogramMode DEF_HISTOGRAM_MODE = HistogramMode.reverseQuadtree;

    //this differs from RdrFormatImageContent.java which uses default bins=256
    public static final int DEF_MAX_HISTOGRAM_BINS = 4096;
    public static final boolean DEF_NEVER_ESTIMATE_EXTREMA_FROM_HISTOGRAM = false;
    public static final boolean DEF_ALWAYS_ESTIMATE_EXTREMA_FROM_HISTOGRAM = true;

    public static final long PROGRESS_PERIOD_SEC = 10;

    public static final String[] PLAIN_EXTS = new String[] { "png", "jpg", "jpeg", "tif", "tiff" };
    public static final String[] PDS_EXTS = new String[] { "img", "vic" };
    public static final String[] PNG_EXTS = new String[] { "png" };
    
    public static final String DEF_MISSION = "M20";

    public static final int DEF_PDS_TILE_WIDTH = 512; //negative uses image width
    public static final int DEF_PDS_TILE_HEIGHT = 512; //negative uses 1

    public static final boolean ALLOW_TILING_ICM = false;

    public static final String DEF_RDR_URL_WHITELIST_PATTERNS = ""; //allow any input rdrUrl by default

    public static final String DEF_CRISP_PATTERNS = ".*/warped-[^/]*";
    public static final String DEF_CRISP_SUFFIXEN = "LUJ00,J00,00";

    public static final String DEF_ICM_PATTERNS = ".*/ICM-[^/]*";

    //I think technically .VIC may not be in the spec
    //but the frontend is switching to prefer .VIC to .IMG
    public static final String DEF_QUICKLOOK_PATTERNS = "(?i).*/[AQC][^/]+SOL[^/]+[.](PNG|JPG|JPEG|TIF|TIFF|IMG|VIC)$";

    public static final String DEF_USER_UPLOAD_PATTERNS = "(?i)^s3://[^/]*user-uploads.*";
    
    public static final String DEF_FIFTEEN_BIT_PATTERNS = "R[AZ][DY],[CDZM]NR,[CDZM][SPW]D";

    public static final String DEF_PREAPPLIED_GAMMA_PATTERNS = "[CR].G";
    public static final String DEF_PREAPPLIED_8BIT_GAMMA_PATTERNS = "ECM,ECV";
    public static final String DEF_PREAPPLIED_INST_GAMMA_PATTERNS = "";
//    public static final String DEF_PREAPPLIED_INST_GAMMA_PATTERNS = "E.,H.,PC";

    public static final String DZI_FILE = "image.dzi";
    public static final String THUMB_FORMAT = "png";
    public static final String THUMB_FILE = "thumbnail." + THUMB_FORMAT;
    public static final String PARAMS_FILE = "params.json";
    public static final String METADATA_FILE = "metadata.json";
    public static final String ETAG_FILE = "etag.txt";
    public static final String ERROR_FILE = "error.txt";
    public static final String PROGRESS_FILE = "progress.txt";
    public static final String ABORT_FILE = "abort.txt";
    public static final String PID_FILE_EXT = ".pid";

    public static final int DEF_PLAIN_BITS = 8;
    public static final int DEF_PDS_BITS = 12;

    public static final int DEF_DZI_PARALLELISM = Math.round(Runtime.getRuntime().availableProcessors());
    public static final String DEF_READ_CHUNK = "0";

    //0.25 is relative to image, 25% is relative to task mem budget
    public static final String DEF_TILE_WINDOW_CHUNK = "25%";

    public static final boolean DEF_MEM_CACHE_TILES = false; //default to no memcache for created tiles

    public static final int DEF_MAX_CONCURRENT_TASKS = 32;

    public static final float DEF_MEMORY_BUDGET_PERCENT = 80;
    public static final float DEF_DISK_BUDGET_PERCENT = 80;

    public static final String DEF_JAI_CACHE_CAPACITY = "auto";
    public static final long DEF_JAI_CACHE_CAPACITY_BYTES = 500 * 1024 * 1024;
    public static final float DEF_JAI_CACHE_PERCENT = 20;

    //NOTE: large objects do not seem to play nice with the new default G1 garbage collector
    //probably causing major heap fragmentation
    //(G1HeapRegionSize defaults to max_heap / 2048, but even manually setting it to its max of 32M didn't help)
    //so run with -XX:+UseParallelGC
    public static final String DEF_LRU_PAGE_BYTES = "16m";
    public static final String DEF_LRU_MEM_CACHE_PAGES = "auto";
    public static final int DEF_LRU_MEM_CACHE_PAGES_NUM = 12; //12 * 16m = 192m
    public static final String DEF_LRU_DISK_CACHE_DIR = "auto";
    public static final String DEF_LRU_DISK_CACHE_PAGES = "auto";
    public static final int DEF_LRU_DISK_CACHE_PAGES_NUM = 32; //32 * 16m = 512m
    public static final int FULL_LOAD_MAX_MEM_CACHE_PAGES = 1;

    public static final String DEF_LARGE_FILE_THRESHOLD = "128M";
    public static final String DEF_LARGE_IMAGE_THRESHOLD = "128M"; //M20 ECAM 5120 * 3840 * 3 * 2 = 117,964,800

    public static final float DEF_TASK_MEM_PERCENT = 80;
    public static final float DEF_TASK_DISK_PERCENT = 80;
    public static final long TASK_OVERHEAD_BYTES = 100 * 1024 * 1024;

    public static final String DEF_MAX_WAIT_TIME = "30s";
    public static final String DEF_MAX_ZOMBIE_TIME = "1h";
    public static final String DEF_MAX_INTERLOCK_WAIT_TIME = "2m";
    public static final String DEF_TASK_INTERLOCK_TIME = "10s";

    //TYPE:NAME=VALUE (adapted from jpl.mipl.mars.viewer.util.ImagePreferencesParseUtil)
    public static final Pattern PREF_PATTERN = Pattern.compile("^([^:]{1,100}):([^=]{1,100})=([\\S]{1,1000})$");

    public static boolean debug = false;
    public static boolean verbose = false;
    public static boolean enableProgress = true;
    public static boolean requestGC = false;
    public static boolean tuningHacks = false;
    public static boolean formatJson = true;
    public static boolean spewPID = false;

    public static String pfx = getVersion() + " ";

    public static String mission;

    public static boolean allowDotDotInput = false;

    public static long maxWaitMS = 1000l * Utils.parseSeconds(DEF_MAX_WAIT_TIME, -1);
    public static long maxZombieMS = 1000l * Utils.parseSeconds(DEF_MAX_ZOMBIE_TIME, -1);

    public static Pattern[] rdrUrlWhitelistPatterns = null;
    public static String[] crispSuffixen = null;
    public static Pattern[] crispPatterns = null;
    public static Pattern[] icmPatterns = null;
    public static Pattern[] quicklookPatterns = null;
    public static Pattern[] userUploadPatterns = null;
    public static Pattern[] fifteenBitPatterns = null;
    public static Pattern[] preappliedGammaPatterns = null;
    public static Pattern[] preapplied8BitGammaPatterns = null;
    public static Pattern[] preappliedInstGammaPatterns = null;

    private static long maxInterlockWaitMS = 1000l * Utils.parseSeconds(DEF_MAX_INTERLOCK_WAIT_TIME, -1);
    private static long taskInterlockMS = 1000l * Utils.parseSeconds(DEF_TASK_INTERLOCK_TIME, -1);

    private static boolean limitPDSHeight = DEF_LIMIT_PDS_HEIGHT;
    private static boolean limitNonPDSHeight = DEF_LIMIT_NON_PDS_HEIGHT;
    private static boolean fullLoadSmallPDS = DEF_FULL_LOAD_SMALL_PDS;

    private static int dziParallelism = DEF_DZI_PARALLELISM;
    private static double readChunk = -1; //[0-1]: fraction of source image, else bytes

    private static boolean tileWindowLargeNonPDS = DEF_TILE_WINDOW_LARGE_NON_PDS;
    private static double tileWindowChunk = -1; //same semantics as readChunk
    private static double minTileWindowChunk = DEF_MIN_TILE_WINDOW_CHUNK;

    private static boolean memCacheTiles = DEF_MEM_CACHE_TILES;

    private static volatile long memoryBudget = Runtime.getRuntime().freeMemory();

    private static volatile long diskBudget = Utils.diskFreeBytesSafe(System.getProperty("java.io.tmpdir"));

    private static volatile long lruPageBytes = (int)Utils.parseBytes(DEF_LRU_PAGE_BYTES, 0);
    private static volatile int lruMemCachePages =
        (int)Utils.parseInt(DEF_LRU_MEM_CACHE_PAGES, DEF_LRU_MEM_CACHE_PAGES_NUM);
    private static volatile int lruDiskCachePages =
        (int)Utils.parseInt(DEF_LRU_DISK_CACHE_PAGES, DEF_LRU_DISK_CACHE_PAGES_NUM);
    private static String lruDiskCacheDir = DEF_LRU_DISK_CACHE_DIR; //ends with path separator

    private static final AtomicLong peakMem = new AtomicLong();

    private static int pdsTileWidth = -1;
    private static int pdsTileHeight = -1;

    private static String[] ignoreSubdirs = null;
    private static String[] ignoreExtensions = null;
    private static Pattern[] ignoreExceptions = null;

    private static long maxRDRBytes = -1; //non-positive disables limit
    private static long maxImageBytes = -1; //non-positive disables limit

    private static long largeFileThreshold = -1; //negative disables limit
    private static long largeImageThreshold = -1; //negative disables limit

    private static int maxHistogramBins = DEF_MAX_HISTOGRAM_BINS;
    private static HistogramMode histogramMode = DEF_HISTOGRAM_MODE;
    private static boolean neverEstimateExtremaFromHistogram = DEF_NEVER_ESTIMATE_EXTREMA_FROM_HISTOGRAM;
    private static boolean alwaysEstimateExtremaFromHistogram = DEF_ALWAYS_ESTIMATE_EXTREMA_FROM_HISTOGRAM;

    private static ConcurrentHashMap<DziTask, String> currentTasks = new ConcurrentHashMap<DziTask, String>();

    private static ConcurrentHashMap<String, ImageInfo> imageInfoCache = new ConcurrentHashMap<String, ImageInfo>();

    public static final int MAX_CONCURRENT_IMAGE_INFO_LOADS = 2;
    private static Semaphore imageInfoSemaphore = new Semaphore(MAX_CONCURRENT_IMAGE_INFO_LOADS, true);

    public static boolean filterUrl(String url, boolean securityOnly) {
        
        url = url.replace(File.separator, "/"); //always normalize slashes

        if (!allowDotDotInput && url.contains("../")) { //avoid tricks like ../../../pwned
            return false;
        }

        if (rdrUrlWhitelistPatterns.length > 0 && !Utils.anyMatch(rdrUrlWhitelistPatterns, url)) {
            return false;
        }

        if (securityOnly) {
            return true;
        }
        
        if (ignoreSubdirs != null) {
            for (int i = 0; i < ignoreSubdirs.length; i++) {
                if (url.contains("/" + ignoreSubdirs[i] + "/")) {
                    return false;
                }
            }
        }

        if (Utils.anyMatch(quicklookPatterns, url) || Utils.anyMatch(userUploadPatterns, url) ||
            Utils.anyMatch(crispPatterns, url) || Utils.anyMatch(ignoreExceptions, url)) {
            return true;
        }
        
        if (!ALLOW_TILING_ICM && Utils.anyMatch(icmPatterns, url)) {
            return false;
        }

        if (ignoreExtensions != null && Utils.hasExt(url, ignoreExtensions)) {
            return false;
        }
        
        return Utils.hasExt(url, PLAIN_EXTS) || Utils.hasExt(url, PDS_EXTS);
    }
    
    public static boolean filterUrl(String url) {
        return filterUrl(url, false);
    }

    private static volatile String version = null;
    public static String getVersion() {
        if (version == null) {
            version = Utils.getVersion(DziTask.class);
        }
        return version;
    }

    public static void setMission(String mission) {
        DziTask.mission = DziParams.checkMission(mission);
        if (debug) log.debug(pfx + "mission " + mission);
    }

    private static boolean didSetMemoryBudget;
    public static void setMemoryBudget(String budget) {
        long mm = Runtime.getRuntime().maxMemory();
        if (Utils.isAuto(budget)) {
            memoryBudget = (long)(((double)DEF_MEMORY_BUDGET_PERCENT / 100) * mm);
        } else if (budget.endsWith("%")) {
            double pct = Utils.parseFloat(budget.substring(0, budget.length() - 1),
                                          DEF_MEMORY_BUDGET_PERCENT, Utils::isPercent);
            memoryBudget = (long)((pct / 100) * mm);
        } else {
            memoryBudget = Utils.parseBytes(budget, -1);
        }
        if (debug) log.debug(pfx + "memory budget " + Utils.kmg(memoryBudget) + ", max heap " + Utils.kmg(mm));
        didSetMemoryBudget = true;
    }

    private static boolean didSetDiskBudget;
    public static void setDiskBudget(String budget, String cacheDir) {
        diskBudget = 0;
        long fb = 0;
        cacheDir = Utils.isAuto(cacheDir) ? System.getProperty("java.io.tmpdir") : cacheDir.trim();
        if (cacheDir != null && !cacheDir.toLowerCase().equals("none")) {
            try {
                fb = Utils.diskFreeBytes(cacheDir);
            } catch (IOException ex) {
                log.error(pfx + "error getting disk free space for cache dir " + cacheDir, ex);
            }
        }
        if (Utils.isAuto(budget)) {
            diskBudget = (long)(((double)DEF_DISK_BUDGET_PERCENT / 100) * fb);
        } else if (budget.endsWith("%")) {
            double pct = Utils.parseFloat(budget.substring(0, budget.length() - 1),
                                          DEF_DISK_BUDGET_PERCENT, Utils::isPercent);
            diskBudget = (long)((pct / 100) * fb);
        } else {
            diskBudget = Utils.parseBytes(budget, -1);
        }
        if (debug) {
            log.debug(pfx + "cache dir " + cacheDir + ", disk budget " + Utils.kmg(diskBudget) +
                      ", free space " + Utils.kmg(fb));
        }
        didSetDiskBudget = true;
    }

    public static void setIgnore(String subdirs, String exts, String exceptions,
                                 String fileSizeLimit, String imageSizeLimit) {
        
        if (subdirs != null) {
            if (debug) log.debug(pfx + "ignore subdirs: " + subdirs);
            ignoreSubdirs = subdirs.split(",");
        }

        if (exts != null) {
            if (debug) log.debug(pfx + "ignore extensions: " + exts);
            ignoreExtensions = exts.split(",");
        }

        if (exceptions != null) {
            if (debug) log.debug(pfx + "ignore exceptions: " + exceptions);
            ignoreExceptions = Utils.parsePatternList(exceptions);
        }

        if (Utils.isAuto(fileSizeLimit)) {
            maxRDRBytes = -1; //unlimited
        } else if (fileSizeLimit.endsWith("%")) {
            double pct = Utils.parseFloat(fileSizeLimit.substring(0, fileSizeLimit.length() - 1), 0, Utils::isPercent);
            maxRDRBytes = (long)((pct / 100) * getMaxMemoryForAllTasks());
        } else {
            maxRDRBytes = Utils.parseBytes(fileSizeLimit, -1);
        }
        if (maxRDRBytes == 0) {
            maxRDRBytes = -1; //unlimited
        }
        if (maxRDRBytes > 0 && maxRDRBytes < 1024) {
            throw new IllegalArgumentException("max RDR file bytes " + maxRDRBytes + " < 1024");
        }
        if (debug) log.debug(pfx + "max RDR file bytes: " + Utils.kmg(maxRDRBytes));

        if (Utils.isAuto(imageSizeLimit)) {
            maxImageBytes = -1; //unlimited
        } else if (imageSizeLimit.endsWith("%")) {
            double pct =
                Utils.parseFloat(imageSizeLimit.substring(0, imageSizeLimit.length() - 1), 0, Utils::isPercent);
            maxImageBytes = (long)((pct / 100) * getMaxMemoryForAllTasks());
        } else {
            maxImageBytes = Utils.parseBytes(imageSizeLimit, -1);
        }
        if (maxImageBytes == 0) {
            maxImageBytes = -1; //unlimited
        }
        if (maxImageBytes > 0 && maxImageBytes < 1024) {
            throw new IllegalArgumentException("max RDR image bytes " + maxImageBytes + " < 1024");
        }
        if (debug) log.debug(pfx + "max RDR image bytes: " + Utils.kmg(maxImageBytes));
    }

    public static void setPatterns(String rdrUrlWhitelistRegex, String crispRegex, String crispSuffixenList,
                                   String icmRegex, String quicklookRegex, String userUploadRegex,
                                   String fifteenBitRegex, String preappliedGammaRegex,
                                   String preapplied8BitGammaRegex, String preappliedInstGammaRegex)
    {
        try {
            rdrUrlWhitelistRegex = Utils.parseString(rdrUrlWhitelistRegex, DEF_RDR_URL_WHITELIST_PATTERNS);
            if (debug) log.debug(pfx + "RDR URL whitelist patterns: " + rdrUrlWhitelistRegex);
            rdrUrlWhitelistPatterns = Utils.parsePatternList(rdrUrlWhitelistRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing RDR URL whitelist patterns: " + ex.getMessage());
        }

        try {
            crispRegex = Utils.parseString(crispRegex, DEF_CRISP_PATTERNS);
            if (debug) log.debug(pfx + "CRISP patterns: " + crispRegex);
            crispPatterns = Utils.parsePatternList(crispRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing CRISP patterns: " + ex.getMessage());
        }

        if (crispSuffixenList == null) {
            crispSuffixenList = DEF_CRISP_SUFFIXEN;
        }
        crispSuffixen = crispSuffixenList.split(",");
        if (debug) log.debug(pfx + "CRISP suffixen: " + String.join(",", crispSuffixen));

        try {
            icmRegex = Utils.parseString(icmRegex, DEF_ICM_PATTERNS);
            if (debug) log.debug(pfx + "ICM patterns: " + icmRegex);
            icmPatterns = Utils.parsePatternList(icmRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing ICM patterns: " + ex.getMessage());
        }

        try {
            quicklookRegex = Utils.parseString(quicklookRegex, DEF_QUICKLOOK_PATTERNS);
            if (debug) log.debug(pfx + "quicklook patterns: " + quicklookRegex);
            quicklookPatterns = Utils.parsePatternList(quicklookRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing quicklook patterns: " + ex.getMessage());
        }

        try {
            userUploadRegex = Utils.parseString(userUploadRegex, DEF_USER_UPLOAD_PATTERNS);
            if (debug) log.debug(pfx + "user upload patterns: " + userUploadRegex);
            userUploadPatterns = Utils.parsePatternList(userUploadRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing user upload patterns: " + ex.getMessage());
        }

        try {
            fifteenBitRegex = Utils.parseString(fifteenBitRegex, DEF_FIFTEEN_BIT_PATTERNS);
            if (debug) log.debug(pfx + "15 bit product type patterns: " + fifteenBitRegex);
            fifteenBitPatterns = Utils.parsePatternList(fifteenBitRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing 15 bit product type patterns: " + ex.getMessage());
        }

        try {
            preappliedGammaRegex = Utils.parseString(preappliedGammaRegex, DEF_PREAPPLIED_GAMMA_PATTERNS);
            if (debug) log.debug(pfx + "preapplied gamma product type patterns: " + preappliedGammaRegex);
            preappliedGammaPatterns = Utils.parsePatternList(preappliedGammaRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing preapplied gamma product type patterns: " + ex.getMessage());
        }

        try {
            preapplied8BitGammaRegex = Utils.parseString(preapplied8BitGammaRegex, DEF_PREAPPLIED_8BIT_GAMMA_PATTERNS);
            if (debug) log.debug(pfx + "preapplied gamma 8 bit product type patterns: " +
                                 preapplied8BitGammaRegex);
            preapplied8BitGammaPatterns = Utils.parsePatternList(preapplied8BitGammaRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing preapplied gamma 8 bit product type patterns: " + ex.getMessage());
        }

        try {
            preappliedInstGammaRegex = Utils.parseString(preappliedInstGammaRegex, DEF_PREAPPLIED_INST_GAMMA_PATTERNS);
            if (debug) log.debug(pfx + "preapplied gamma instrument patterns: " + preappliedInstGammaRegex);
            preappliedInstGammaPatterns = Utils.parsePatternList(preappliedInstGammaRegex);
        } catch (Exception ex) {
            log.warn(pfx + "error parsing preapplied gamma instrument patterns: " + ex.getMessage());
        }
    }

    public static void setJAICache(String bytes, String tileWidth, String tileHeight) {
        long capacity = -1;
        if (Utils.isAuto(bytes)) {
            if (!didSetMemoryBudget) throw new IllegalStateException("memory budget not set");
            if (memoryBudget > 0) {
                capacity = (long)(((double)DEF_JAI_CACHE_PERCENT / 100) * memoryBudget);
            } else {
                capacity = DEF_JAI_CACHE_CAPACITY_BYTES;
            }
        } else if (bytes.endsWith("%")) {
            if (!didSetMemoryBudget) throw new IllegalStateException("memory budget not set");
            double pct = Utils.parseFloat(bytes.substring(0, bytes.length() - 1), DEF_JAI_CACHE_PERCENT,
                                          Utils::isPercent);
            if (memoryBudget > 0) {
                capacity = (long)((pct / 100) * memoryBudget);
            } else {
                capacity = (long)((pct / 100) * DEF_JAI_CACHE_CAPACITY_BYTES);
            }
        } else {
            capacity = Utils.parseBytes(bytes, Utils.parseBytes(DEF_JAI_CACHE_CAPACITY, DEF_JAI_CACHE_CAPACITY_BYTES));
        }
        if (debug) log.debug(pfx + "JAI cache capacity: " + Utils.kmg(capacity));

        pdsTileWidth = Utils.isAuto(tileWidth) ? -1 : Utils.parseInt(tileWidth, -1, Utils::isPositive);
        pdsTileHeight = Utils.isAuto(tileHeight) ? -1 : Utils.parseInt(tileHeight, -1, Utils::isPositive);

        var tc = JAI.getDefaultInstance().getTileCache();
        tc.setMemoryCapacity(capacity);
        //JAI.getDefaultInstance().setTileCache(new WrappedTileCache(tc));
    }

    public static void setDZIBuilderParams(String parallelism, String chunk, String cacheTiles) {
        dziParallelism = Utils.parseInt(parallelism, DEF_DZI_PARALLELISM, Utils::isNonNegative); //0 is equivalent to 1
        if (Utils.isAuto(chunk)) {
            chunk = DEF_READ_CHUNK;
        }
        if (chunk.endsWith("%")) {
            double pct = Utils.parseFloat(chunk.substring(0, chunk.length() - 1), 0, Utils::isPercent);
            readChunk = (pct / 100) * getMaxMemoryForAllTasks();
        } else {
            readChunk = Utils.parseBytes(chunk, 0);
            if (readChunk <= 1) {
                readChunk = Utils.parseFloat(chunk, 0, Utils::isNonNegative);
                readChunk = Math.max(Math.min(readChunk, 1), 0);
            }
        }
        memCacheTiles = Utils.parseBool(cacheTiles, DEF_MEM_CACHE_TILES);
        if (debug) {
            log.debug(pfx + "dziParallelism=" + dziParallelism + ", readChunk=" + readChunk +
                      ", memCacheTiles=" + memCacheTiles);
        }
    }

    private static int getLRUMemCachePages(float percent) {
        if (!didSetMemoryBudget) throw new IllegalStateException("memory budget not set");
        if (memoryBudget < 0) {
            return DEF_LRU_MEM_CACHE_PAGES_NUM;
        } else if (memoryBudget == 0) {
            return 0;
        } else {
            float taskMem = (percent / 100) * getMaxMemoryForAllTasks() - TASK_OVERHEAD_BYTES;
            return Math.max(1, (int)(taskMem / lruPageBytes));
        }
    }

    private static int getLRUDiskCachePages(float percent, String cacheDir) {
        if (!didSetDiskBudget) throw new IllegalStateException("disk budget not set");
        if (diskBudget < 0) {
            return DEF_LRU_DISK_CACHE_PAGES_NUM;
        } else if (diskBudget == 0) {
            return 0;
        } else {
            float taskDisk = (percent / 100) * diskBudget;
            return (int)(taskDisk / lruPageBytes);
        }
    }

    public static void setLRUCacheParams(String pageBytes, String memCachePages, String diskCachePages,
                                         String diskCacheDir) {

        lruPageBytes = (int)Utils.parseBytes(pageBytes, DEF_LRU_PAGE_BYTES);

        if (Utils.isAuto(memCachePages)) {
            lruMemCachePages = getLRUMemCachePages(DEF_TASK_MEM_PERCENT);
        } else if (memCachePages.endsWith("%")) {
            float pct = Utils.parseFloat(memCachePages.substring(0, memCachePages.length() - 1),
                                         DEF_TASK_MEM_PERCENT, Utils::isPercent);
            lruMemCachePages = getLRUMemCachePages(pct);
        } else {
            int def = (int)Utils.parseInt(DEF_LRU_MEM_CACHE_PAGES, DEF_LRU_MEM_CACHE_PAGES_NUM);
            lruMemCachePages = Utils.parseInt(memCachePages, def, Utils::isNonNegative);
        }

        lruDiskCacheDir = Utils.isAuto(diskCacheDir) ? System.getProperty("java.io.tmpdir") : diskCacheDir.trim();

        if (lruDiskCacheDir.toLowerCase().equals("none")) {
            lruDiskCacheDir = null;
        } else {
            lruDiskCacheDir = Utils.ensureSeparators(lruDiskCacheDir, File.separator);
            if (Utils.isAuto(diskCachePages)) {
                lruDiskCachePages = getLRUDiskCachePages(DEF_TASK_DISK_PERCENT, lruDiskCacheDir);
            } else if (diskCachePages.endsWith("%")) {
                float pct = Utils.parseFloat(diskCachePages.substring(0, diskCachePages.length() - 1),
                                             DEF_TASK_DISK_PERCENT, Utils::isPercent);
                lruDiskCachePages = getLRUDiskCachePages(pct, lruDiskCacheDir);
            } else {
                int def = (int)Utils.parseInt(DEF_LRU_DISK_CACHE_PAGES, DEF_LRU_DISK_CACHE_PAGES_NUM);
                lruDiskCachePages = Utils.parseInt(diskCachePages, def, Utils::isNonNegative);
            }
        }

        if (debug) {
            log.debug(pfx + "lruPageBytes=" + Utils.kmg(lruPageBytes) + ", lruMemCachePages=" + lruMemCachePages +
                      ", lruDiskCachePages=" + lruDiskCachePages + ", lruDiskCacheDir=" + lruDiskCacheDir);
        }
    }

    public static void setLargeImageParams(String largeFile, String largeImage, String limitPDS, String limitNonPDS,
                                           String loadSmallPDSAsBufferedImage, String useTileWindow, String windowChunk,
                                           String minWindowChunk, String histoBins, String histoMode,
                                           String neverEstimateExtrema, String alwaysEstimateExtrema) {
        if (Utils.isAuto(largeFile)) {
            largeFileThreshold = Utils.parseBytes(DEF_LARGE_FILE_THRESHOLD, -1);
        } else if (largeFile.endsWith("%")) {
            double pct = Utils.parseFloat(largeFile.substring(0, largeFile.length() - 1), 0, Utils::isPercent);
            largeFileThreshold = (long)((pct / 100) * getMaxMemoryForAllTasks());
        } else {
            largeFileThreshold = Utils.parseBytes(largeFile, -1);
        }

        if (Utils.isAuto(largeImage)) {
            largeImageThreshold = Utils.parseBytes(DEF_LARGE_IMAGE_THRESHOLD, -1);
        } else if (largeImage.endsWith("%")) {
            double pct = Utils.parseFloat(largeImage.substring(0, largeImage.length() - 1), 0, Utils::isPercent);
            largeImageThreshold = (long)((pct / 100) * getMaxMemoryForAllTasks());
        } else {
            largeImageThreshold = Utils.parseBytes(largeImage, -1);
        }
        if (largeImageThreshold > 0) largeImageThreshold = Math.min(largeImageThreshold, Integer.MAX_VALUE);

        if (debug) {
            log.debug(pfx + "largeFileThreshold=" + Utils.kmg(largeFileThreshold) +
                      ", largeImageThreshold=" + Utils.kmg(largeImageThreshold));
        }

        limitPDSHeight = Utils.parseBool(limitPDS, DEF_LIMIT_PDS_HEIGHT);

        limitNonPDSHeight = Utils.parseBool(limitNonPDS, DEF_LIMIT_NON_PDS_HEIGHT);

        fullLoadSmallPDS = Utils.parseBool(loadSmallPDSAsBufferedImage, DEF_FULL_LOAD_SMALL_PDS);

        tileWindowLargeNonPDS = Utils.parseBool(useTileWindow, DEF_TILE_WINDOW_LARGE_NON_PDS);

        if (Utils.isAuto(windowChunk)) {
            windowChunk = DEF_TILE_WINDOW_CHUNK;
        }
        if (windowChunk.endsWith("%")) {
            double pct = Utils.parseFloat(windowChunk.substring(0, windowChunk.length() - 1), 0, Utils::isPercent);
            tileWindowChunk = (pct / 100) * getMaxMemoryForAllTasks();
        } else {
            tileWindowChunk = Utils.parseBytes(windowChunk, 0);
            if (tileWindowChunk <= 1) {
                tileWindowChunk = Utils.parseFloat(windowChunk, 0, Utils::isNonNegative);
                tileWindowChunk = Math.max(Math.min(tileWindowChunk, 1), 0);
            }
        }

        minTileWindowChunk = Utils.parseFloat(minWindowChunk, DEF_MIN_TILE_WINDOW_CHUNK, Utils::isAlpha);

        if (debug) {
            log.debug(pfx + "limitPDSHeight=" + limitPDSHeight + ", limitNonPDSHeight=" + limitNonPDSHeight +
                      ", fullLoadSmallPDS=" + fullLoadSmallPDS + ", tileWindowLargeNonPDS=" + tileWindowLargeNonPDS +
                      ", tileWindowChunk=" +
                      (tileWindowChunk > 1 ? Utils.kmg(tileWindowChunk) : Double.toString(tileWindowChunk)) +
                      ", minTileWindowChunk=" + minTileWindowChunk);
        }

        maxHistogramBins = Utils.parseInt(histoBins, DEF_MAX_HISTOGRAM_BINS, Utils::isPositive);
        histogramMode = Utils.parseEnum(histoMode, HistogramMode.class, DEF_HISTOGRAM_MODE);
        neverEstimateExtremaFromHistogram =
            Utils.parseBool(neverEstimateExtrema, DEF_NEVER_ESTIMATE_EXTREMA_FROM_HISTOGRAM);
        alwaysEstimateExtremaFromHistogram =
            Utils.parseBool(alwaysEstimateExtrema, DEF_ALWAYS_ESTIMATE_EXTREMA_FROM_HISTOGRAM);

        if (debug) {
            log.debug(pfx + "maxHistogramBins=" + maxHistogramBins + ", histogramMode=" + histogramMode +
                      ", neverEstimateExtremaFromHistogram=" + neverEstimateExtremaFromHistogram +
                      ", alwaysEstimateExtremaFromHistogram=" + alwaysEstimateExtremaFromHistogram);
        }
    }

    public static void setConfig(Function<String, String> getter, Map<String, String> overrides) {

        Function<String, String> get = (name) -> {
            return overrides != null && overrides.containsKey(name) ? overrides.get(name) : getter.apply(name);
        };

        verbose = Utils.parseBool(get.apply("VERBOSE_TILER"), false);

        debug = verbose || Utils.parseBool(get.apply("DEBUG_TILER"), false);

        if (debug) Utils.setLogLevel(log, "DEBUG");

        formatJson = Utils.parseBool(get.apply("FORMAT_JSON"), true);

        tuningHacks = Utils.parseBool(get.apply("TUNING_HACKS"), false);

        spewPID = Utils.parseBool(get.apply("SPEW_PID"), false);

        requestGC = Utils.parseBool(get.apply("REQUEST_GC"), false);

        setMission(get.apply("MISSION"));

        setDiskBudget(get.apply("DISK_BUDGET"), get.apply("LRU_DISK_CACHE_DIR"));

        setMemoryBudget(get.apply("MEMORY_BUDGET"));

        setIgnore(get.apply("IGNORE_SUBDIRS"), get.apply("IGNORE_EXTS"), get.apply("IGNORE_EXCEPTIONS"),
                  get.apply("MAX_RDR_BYTES"), get.apply("MAX_IMAGE_BYTES"));

        setPatterns(get.apply("RDR_URL_WHITELIST_PATTERNS"),
                    get.apply("CRISP_PATTERNS"), get.apply("CRISP_SUFFIXEN"), get.apply("ICM_PATTERNS"),
                    get.apply("QUICKLOOK_PATTERNS"), get.apply("USER_UPLOAD_PATTERNS"),
                    get.apply("FIFTEEN_BIT_PATTERNS"), get.apply("PREAPPLIED_GAMMA_PATTERNS"),
                    get.apply("PREAPPLIED_8BIT_GAMMA_PATTERNS"), get.apply("PREAPPLIED_INST_GAMMA_PATTERNS"));

        setDZIBuilderParams(get.apply("DZI_PARALLELISM"), get.apply("READ_CHUNK"), get.apply("MEM_CACHE_TILES"));

        setJAICache(get.apply("JAI_CACHE"), get.apply("PDS_TILE_WIDTH"), get.apply("PDS_TILE_HEIGHT"));

        setLRUCacheParams(get.apply("LRU_PAGE_BYTES"), get.apply("LRU_MEM_CACHE_PAGES"),
                          get.apply("LRU_DISK_CACHE_PAGES"), get.apply("LRU_DISK_CACHE_DIR"));

        setLargeImageParams(get.apply("LARGE_FILE_THRESHOLD"), get.apply("LARGE_IMAGE_THRESHOLD"),
                            get.apply("LIMIT_PDS_HEIGHT"), get.apply("LIMIT_NON_PDS_HEIGHT"), 
                            get.apply("FULL_LOAD_SMALL_PDS"), get.apply("TILE_WINDOW_LARGE_NON_PDS"),
                            get.apply("TILE_WINDOW_CHUNK"), get.apply("MIN_TILE_WINDOW_CHUNK"),
                            get.apply("MAX_HISTOGRAM_BINS"), get.apply("HISTOGRAM_MODE"),
                            get.apply("NEVER_ESTIMATE_EXTREMA_FROM_HISTOGRAM"),
                            get.apply("ALWAYS_ESTIMATE_EXTREMA_FROM_HISTOGRAM"));

        String mwt = get.apply("MAX_WAIT_TIME");
        maxWaitMS = 1000l * Utils.parseSeconds(mwt, Utils.parseSeconds(DEF_MAX_WAIT_TIME, -1));
        if (debug) log.debug(pfx + "max product wait time: " + Utils.hms(maxWaitMS));

        String mzt = get.apply("MAX_ZOMBIE_TIME");
        maxZombieMS = 1000l * Utils.parseSeconds(mzt, Utils.parseSeconds(DEF_MAX_ZOMBIE_TIME, -1));
        if (debug) log.debug(pfx + "max zombie time: " + Utils.hms(maxZombieMS));

        String tit = get.apply("TASK_INTERLOCK_TIME");
        taskInterlockMS = 1000l * Utils.parseSeconds(tit, Utils.parseSeconds(DEF_TASK_INTERLOCK_TIME, 10));
        if (debug) log.debug(pfx + "task interlock time: " + Utils.hms(taskInterlockMS));

        String miwt = get.apply("MAX_INTERLOCK_WAIT_TIME");
        maxInterlockWaitMS = 1000l * Utils.parseSeconds(miwt, Utils.parseSeconds(DEF_MAX_INTERLOCK_WAIT_TIME, -1));
        if (debug) log.debug(pfx + "max interlock wait time: " + Utils.hms(maxInterlockWaitMS));
    }

    public static void setConfig(Function<String, String> getter) {
        setConfig(getter, null);
    }

    public static long getJAICacheCapacity() {
        return JAI.getDefaultInstance().getTileCache().getMemoryCapacity();
    }

    public static void clearJAICache() {
        var tc = JAI.getDefaultInstance().getTileCache();
        long cap = tc.getMemoryCapacity();
        tc.setMemoryCapacity(0);
        tc.setMemoryCapacity(cap);
    }

    public static int getMaxConcurrentTasks(String maxConcurrentTasks) {
        if (Utils.isAuto(maxConcurrentTasks)) {
            return DEF_MAX_CONCURRENT_TASKS;
        } else if (maxConcurrentTasks.trim().endsWith("%")) {
            maxConcurrentTasks = maxConcurrentTasks.trim().substring(0, maxConcurrentTasks.length() - 1);
            float pct = Utils.parseFloat(maxConcurrentTasks, 100, Utils::isPercent);
            int mct = DEF_MAX_CONCURRENT_TASKS;
            if (lruPageBytes > 0) {
                if (!didSetMemoryBudget) throw new IllegalStateException("memory budget not set");
                long mb = memoryBudget < 0 ? Runtime.getRuntime().freeMemory() : memoryBudget;
                if (mb > 0 && lruMemCachePages > 0) {
                    mct = Math.max(1, (int)(((pct / 100) * getMaxMemoryForAllTasks()) /
                                            (lruPageBytes * lruMemCachePages + TASK_OVERHEAD_BYTES)));
                }
                if (lruDiskCacheDir != null && lruDiskCachePages > 0) {
                    if (!didSetDiskBudget) throw new IllegalStateException("disk budget not set");
                    long db =  diskBudget < 0 ? Utils.diskFreeBytesSafe(lruDiskCacheDir) : diskBudget;
                    if (db > 0) {
                        mct = Math.max(1, Math.min(mct, (int)(((pct / 100) * diskBudget) /
                                                              (lruPageBytes * lruDiskCachePages))));
                    }
                }
            }
            return mct;
        } else {
            return Utils.parseInt(maxConcurrentTasks, -1); //negative = unlimited
        }
    }

    public static long getMaxMemoryForAllTasks() {
        if (!didSetMemoryBudget) throw new IllegalStateException("memory budget not set");
        return memoryBudget >= 0 ?
            Math.max(0, memoryBudget - getJAICacheCapacity()) : Runtime.getRuntime().freeMemory();
    }

    public static long getMaxDiskForAllTasks() {
        if (!didSetDiskBudget) throw new IllegalStateException("disk budget not set");
        return diskBudget >= 0 ? diskBudget : lruDiskCacheDir != null ? Utils.diskFreeBytesSafe(lruDiskCacheDir) : 0;
    }

    private static String dumpHeap() {
        if (requestGC) System.gc();
        var rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long peak = peakMem.updateAndGet((v) -> Math.max(v, used));
        return "heap used/peak/alloc/max: " + Utils.kmg(used) + "/" + Utils.kmg(peak) +
            "/" + Utils.kmg(rt.totalMemory()) + "/" + Utils.kmg(rt.maxMemory()) + (requestGC ? " after gc" : "");
    }

    private static void dumpJAICache(String pfx) {
        var tc = JAI.getDefaultInstance().getTileCache();
        if (tc instanceof WrappedTileCache) {
            tc = ((WrappedTileCache)tc).inner;
        }
        long capacity = tc.getMemoryCapacity();
        if (tc instanceof SunTileCache) {
            try {
                long misses = Utils.getLongField(tc, "missCount");
                long hits = Utils.getLongField(tc, "hitCount");
                long tiles = Utils.getLongField(tc, "tileCount");
                long used = Utils.getLongField(tc, "memoryUsage");
                if (used > 0) {
                    log.debug(pfx + "JAI cache " + Utils.kmg(used) + "/" + Utils.kmg(capacity) + " used, " +
                              tiles + " tiles, " + Utils.kmg(hits) + " hits, " + Utils.kmg(misses) + " misses");
                }
                return;
            } catch (Exception ex) {}
        }
        log.debug(pfx + "JAI cache capacity " + Utils.kmg(capacity));
    }

    private static void reportProgressAndCheckLive() {
        if (!enableProgress || currentTasks.isEmpty()) return;
        float minPercent = 0, maxPercent = 0;
        long minMS = 0, maxMS = 0;
        long minRDR = 0, maxRDR = 0;
        long minMemCached = -1, maxMemCached = 0, totalMemCached = 0, totalMemCacheCapacity = 0;
        long minDiskCached = -1, maxDiskCached = 0, totalDiskCached = 0, totalDiskCacheCapacity = 0;
        long minLoaded = -1, maxLoaded = 0;
        int numTasks = 0;
        long now = System.currentTimeMillis();
        for (var entry : currentTasks.entrySet()) {
            numTasks++;
            var task = entry.getKey();
            var url = entry.getValue();
            float percent = task.getPercentComplete();
            minPercent = minPercent > 0 ? Math.min(minPercent, percent) : percent;
            maxPercent = Math.max(maxPercent, percent);
            long ms = now - task.startMS;
            minMS = minMS > 0 ? Math.min(minMS, ms) : ms;
            maxMS = Math.max(maxMS, ms);
            minRDR = minRDR > 0 ? Math.min(minRDR, task.rdrBytes) : task.rdrBytes;
            maxRDR = Math.max(maxRDR, task.rdrBytes);
            var tis = task.imageStream; //atomic read
            var lruc = tis instanceof LRUCacheImageInputStream ? ((LRUCacheImageInputStream)tis) : null;
            long mcb = lruc != null ? lruc.memCacheUsed() : 0;
            minMemCached = minMemCached >= 0 ? Math.min(minMemCached, mcb) : mcb;
            maxMemCached = Math.max(maxMemCached, mcb);
            totalMemCached += mcb;
            long dcb = lruc != null ? lruc.diskCacheUsed() : 0;
            minDiskCached = minDiskCached >= 0 ? Math.min(minDiskCached, dcb) : dcb;
            maxDiskCached = Math.max(maxDiskCached, dcb);
            totalDiskCached += dcb;
            totalMemCacheCapacity += task.maxActualTaskMemCacheBytes();
            totalDiskCacheCapacity += task.maxActualTaskDiskCacheBytes();
            long lb = lruc != null ? lruc.loadedBytes() : 0;
            minLoaded = minLoaded >= 0 ? Math.min(minLoaded, lb) : lb;
            maxLoaded = Math.max(maxLoaded, lb);
            String pfx = task.params.pfx;
            if (task.started) {
                if (task.archiver != null) {
                    if (debug) {
                        String na = "";
                        if (task.archiver != null) {
                            na = " " + task.archiver.numArchived() + "/" + task.expectedFiles + " files archived at " +
                                task.cacheUrl;
                        }
                        log.debug(pfx + String.format("%.2f", percent) + "% " + Utils.hms(ms) + na);
                    }
                    try {
                        if (!task.archiver.fileExists(task.pidFile) && !task.done && task.cancel("deleted")) {
                            log.warn(pfx + "cancelled, input deleted");
                        }
                    } catch (Exception ex) {
                        log.error(pfx + "error checking " + task.pidFile, ex);
                    }
                    if (!task.cancelled && !task.done) {
                        task.saveProgress(percent);
                        String abortPID = task.archiver.loadTextFileIfExists(ABORT_FILE);
                        if (abortPID != null && !task.pid.equals(abortPID) && task.cancel(abortPID.substring(0, 8))) {
                            log.warn(pfx + "cancelled by " + abortPID.substring(0, 8));
                        }
                    }
                }
                if (debug) {
                    log.debug(pfx + "task memory budget " + Utils.kmg(task.maxActualTaskMemory()) +
                              ", mem cache " + Utils.kmg(task.maxActualTaskMemCacheBytes()) +
                              ", overhead " + Utils.kmg(TASK_OVERHEAD_BYTES) +
                              ", disk cache " + Utils.kmg(task.maxActualTaskDiskCacheBytes()));
                }
            }
            if (debug) {
                if (lruc != null) lruc.dumpStats(log, pfx);
            }
        }
        if (numTasks > 0) {
            String pfx = getVersion() + " ";
            String mp = numTasks > 1 ? String.format("-%.2f%%", maxPercent) : "";
            String mt = numTasks > 1 ? ("-" + Utils.hms(maxMS)) : "";
            String mr = numTasks > 1 ? ("-" + Utils.kmg(maxRDR)) : "";
            String mc = numTasks > 1 ? ("-" + Utils.kmg(maxMemCached)) : "";
            String md = numTasks > 1 ? ("-" + Utils.kmg(maxDiskCached)) : "";
            String ml = numTasks > 1 ? ("-" + Utils.kmg(maxLoaded)) : "";
            String tm = Utils.kmg(totalMemCached) + "/" + Utils.kmg(totalMemCacheCapacity);
            String td = Utils.kmg(totalDiskCached) + "/" + Utils.kmg(totalDiskCacheCapacity);
            log.info(pfx + numTasks + " tasks, " + String.format("%.2f%%", minPercent) + mp + " " +
                     Utils.hms(minMS) + mt + ", " + Utils.kmg(minRDR) + mr + " source, " +
                     Utils.kmg(minMemCached) + mc + " mem cached (" + tm + " total), " +
                     Utils.kmg(minDiskCached) + md + " disk cached (" + td + " total), " +
                     Utils.kmg(minLoaded) + ml + " loaded; " +
                     "memory budget " + Utils.kmg(memoryBudget) + ", " + dumpHeap() +
                     ((diskBudget > 0 && lruDiskCacheDir != null) ?
                      (", disk budget " + Utils.kmg(diskBudget) +
                       ", disk free " + Utils.kmg(Utils.diskFreeBytesSafe(lruDiskCacheDir))) : ""));
            if (debug) dumpJAICache(pfx);
        }
    }

    static {

        //suppress voluminous log warnings when JAI native libs are not available
        //JAI natives are closed source, haven't been maintained in many years,
        //and may not be available for all platforms also some report that the pure java
        //implementation is now faster than some of the natives anyway
        final JAI jai = JAI.getDefaultInstance();
        if (jai == null) {
            log.error(pfx + "JAI not found");
        //} else if (jai.getImagingListener() != null) {
        //    log.error(pfx + "JAI already has listener");
        } else {
            jai.setImagingListener(new ImagingListener() {
                    @Override
                    public boolean errorOccurred(String message, Throwable thrown, Object where,
                                                 boolean isRetryable) throws RuntimeException {
                        if (message.toLowerCase().contains("continuing in pure java mode")) {
                            //log.debug(pfx + "JAI: " + message);
                        } else {
                            String msg = "JAI error: " + message;
                            log.error(pfx + msg, thrown);
                            for (var task : currentTasks.keySet()) {
                                task.cancel("jai", msg, thrown);
                            }
                        }
                        return false;
                    }
                });
        }

        ImageIO.setUseCache(false);

        var monitorThread = new Thread("DZI task progress and liveness") {
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(1000l * PROGRESS_PERIOD_SEC);
                        } catch (InterruptedException ex) {
                            break;
                        }
                        try {
                            reportProgressAndCheckLive();
                        } catch (Exception ex) {
                            log.error(pfx + "error reporting progress and checking liveness: " + ex.getMessage(), ex);
                        }
                    }
                }
            };
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    //most task state is either final or volatile so that it is safely published to monitorThread

    public final DziParams params;
    public final CachedArchiver archiver;
    public final String cacheDir;
    public final S3Helper inputS3;
    public final String pid;
    public final String pidFile;
    public final String cacheUrl;
    public final String actualDiskCacheDir;

    private volatile ImageInfo imageInfo;

    private volatile long rdrBytes;
    private volatile long imageBytes;
    private volatile long imagePixels;

    private volatile int limitHeight = -1;

    private volatile ImageInputStream imageStream;

    private volatile BufferedImage thumbnail;
    private volatile JsonObject metadataJSON;

    private volatile boolean started;
    private volatile long startMS;
    private volatile String cancelSource;
    private volatile boolean cancelled;
    private volatile CancellationException cancelException;
    private volatile boolean done;
    private volatile int histogramSteps, extremaSteps;
    private volatile float progressPercent;
    private volatile Exception error;

    private volatile int actualDziParallelism;
    private volatile double actualReadChunk; //fraction of image pixels
    private volatile double actualTileWindowChunk; //fraction of image pixels

    private volatile int actualMemCachePages;
    private volatile int fullLoadMemCachePages;
    private volatile int actualDiskCachePages;

    private volatile int actualPDSTileWidth;
    private volatile int actualPDSTileHeight;

    private volatile boolean loadAsBufferedImage;
    private volatile boolean loadAsTileWindowImage;

    private volatile int tileWindowTilesX, tileWindowTilesY, tileWindowMargin;

    private volatile long rdrTimestamp;
    private volatile boolean largeFile;
    private volatile boolean largeLocalFile;
    private volatile boolean largeImage;
    private volatile boolean randomReadsAreExpensive;

    private volatile int expectedHistogramSteps, expectedExtremaSteps, expectedFiles;

    private volatile boolean didPreRun; //preRun() is called from TileService, TileLambda, and run()

    private float adjStretchLowPercent = Float.NaN;
    private float adjStretchHighPercent = Float.NaN;

    public DziTask(DziParams params, ExtendedArchiver archiver, String cacheDir, S3Helper inputS3) {
        this.params = params;
        this.archiver = memCacheTiles ? new MemCachedArchiver(archiver) : new FileCachedArchiver(archiver);
        this.archiver.setLogger(log, params.pfx);
        this.cacheDir = cacheDir;
        this.inputS3 = inputS3;
        pid = UUID.randomUUID().toString();
        pidFile = pid + PID_FILE_EXT;
        cacheUrl = ((archiver instanceof S3Archiver) ? ("s3://" + ((S3Archiver)archiver).bucket + "/") : "") + cacheDir;
        actualDiskCacheDir = lruDiskCacheDir != null ? (lruDiskCacheDir + pid) : null;
        if (spewPID) params.pfx += pid.substring(0, 8) + " ";
    }

    public static class OversizeFileException extends IllegalArgumentException {
        public OversizeFileException(String message) {
            super(message);
        }
    }

    public void preRun() throws IOException {
        try {
            //register in currentTasks while doing preRun()
            //because loading image metadata can involve a full pass on the input file and thus take a long time
            //for some image types and in some deployment contexts
            if (didPreRun) return;
            startMS = System.currentTimeMillis();
            currentTasks.put(this, params.rdrUrl);
            preRunImpl();
            didPreRun = true;
        } finally {
            currentTasks.remove(this);
        }
    }

    private void preRunImpl() throws IOException {

        String pfx = params.pfx;

        if (params.skipCached && archiver.fileExists(METADATA_FILE)) {
            cancel("skip cached");
            return;
        }

        if (params.overlayable && params.stretchable) {
            throw new IllegalArgumentException("cannot be both overlayable and stretchable");
        }
        
        //check that source image exists
        if (!(params.rdrUrlIsS3 ? inputS3.doesObjectExist(params.rdrUrl) :
              params.rdrUrlIsHTTP ? HTTPHelper.exists(params.rdrUrl) :
              Files.exists(Paths.get(params.rdrUrl)))) {
            throw new FileNotFoundException
                ("input file not found on " +
                 (params.rdrUrlIsS3 ? "S3" : params.rdrUrlIsHTTP ? "HTTP(S)" : "filesystem"));
        }
        
        //check that source image is nonempty
        rdrBytes = params.rdrUrlIsS3 ? inputS3.getObjectSize(params.rdrUrl) :
            params.rdrUrlIsHTTP ? HTTPHelper.getSize(params.rdrUrl) :
            new File(params.rdrUrl).length();
        if (rdrBytes < 1) {
            throw new IllegalArgumentException("input file is empty");
        }

        if (maxRDRBytes > 0 && rdrBytes > maxRDRBytes) {
            throw new OversizeFileException("input file size " + Utils.kmg(rdrBytes) + " > " + Utils.kmg(maxRDRBytes));
        }

        largeFile = largeFileThreshold >= 0 && rdrBytes > largeFileThreshold;
        largeLocalFile = !params.rdrUrlIsS3 && !params.rdrUrlIsHTTP && largeFile;

        rdrTimestamp =
            params.rdrUrlIsS3 ? inputS3.getObjectLastModified(params.rdrUrl) :
            params.rdrUrlIsHTTP ? HTTPHelper.getLastModified(params.rdrUrl) :
            Utils.fileLastModified(params.rdrUrl);

        actualMemCachePages = 0;
        fullLoadMemCachePages = 0;
        long memBudget = getMaxMemoryForAllTasks() - TASK_OVERHEAD_BYTES;
        if (lruPageBytes > 0 && lruMemCachePages > 0) {
            memBudget = lruMemCachePages * lruPageBytes;
            actualMemCachePages = Math.min(lruMemCachePages, (int)Math.ceil(((double)rdrBytes) / lruPageBytes));
            fullLoadMemCachePages = Math.min(actualMemCachePages, FULL_LOAD_MAX_MEM_CACHE_PAGES);
        }

        actualDiskCachePages = 0;
        if (lruPageBytes > 0 && lruDiskCacheDir != null && lruDiskCachePages > 0) {
            if (params.rdrUrlIsS3 || params.rdrUrlIsHTTP || tuningHacks) {
                //reserve enough disk cache to hold the entire input even though there may be mem cache as well
                //because the mem cache uses SoftReference it could get fully wiped at any time
                actualDiskCachePages = Math.min(lruDiskCachePages, (int)Math.ceil(((double)rdrBytes) / lruPageBytes));
                if (tuningHacks && !params.rdrUrlIsS3 && !params.rdrUrlIsHTTP) {
                    log.warn(pfx + "TUNING HACKS: using disk cache for local file");
                }
            } else if (debug) {
                log.debug(pfx + "disabling disk cache for local file");
            }
        }

        //we can get here concurrently for multiple requests, e.g. if the frontend simultaneously requests the thumb,
        //metadata, and dzi for an uncached product (in theory in a load balanced deployment those requests could even
        //make their way to separate server instances, but just let that happen as it may; eventually in taskInterlock()
        //they will duke it out)
        final var err = new IOException[1];
        imageInfo = imageInfoCache.computeIfAbsent(params.rdrUrl + "-" + rdrTimestamp, (String key) -> {
                try {
                    return loadImageInfo();
                } catch (IOException ex) {
                    err[0] = ex;
                    return null;
                }
            });
        if (imageInfo == null || err[0] != null) {
            throw err[0] != null ? err[0] : new IOException("failed to get image metadata");
        }

        imageInfo.spew(log, pfx);

        int numColorChannels = imageInfo.hasAlpha ? (imageInfo.bands - 1) : imageInfo.bands;
        if (params.stretchable && !(numColorChannels == 1 || numColorChannels == 3)) {
            throw new IllegalArgumentException("cannot stretch " + numColorChannels +
                                               " color input image, 1 or 3 color channels required");
        }
            
        imageBytes = imageInfo.totalBytes();
        imagePixels = imageInfo.totalPixels();

        if (imagePixels == 0) {
            throw new IllegalArgumentException("cannot process " + imageInfo.width + "x" + imageInfo.height +
                                               " image: no pixels");
        }

        limitHeight = -1;

        if (params.isPDS && limitPDSHeight && imagePixels > Integer.MAX_VALUE) {
            limitHeight = (int)Math.floor(Integer.MAX_VALUE / imageInfo.width);
            log.warn(pfx + "reduced PDS/VICAR image height from " + imageInfo.height + " to " + limitHeight +
                     " to reduce total pixels from " + Utils.kmg(imagePixels) + " to " +
                     Utils.kmg(imageInfo.width * (long)limitHeight));
        }
            
        if (!params.isPDS && limitNonPDSHeight && imageBytes > Integer.MAX_VALUE) {
            limitHeight  = (int)Math.floor(Integer.MAX_VALUE / imageInfo.bytesPerLine());
            log.warn(pfx + "reduced non-PDS/VICAR image height from " + imageInfo.height + " to " + limitHeight +
                     " to reduce total bytes from " + Utils.kmg(imageBytes) + " to " +
                     Utils.kmg(imageInfo.bytesPerLine() * (long)limitHeight));
        }

        if (limitHeight == 0) {
            throw new OversizeFileException("image width " + imageInfo.width + " too large");
        }

        if (limitHeight > 0) {
            imageBytes = limitHeight * imageInfo.bytesPerLine();
            imagePixels = limitHeight * imageInfo.width;
        }

        if (maxImageBytes > 0 && imageBytes > maxImageBytes) {
            throw new OversizeFileException("image bytes " + Utils.kmg(imageBytes) + " > " + Utils.kmg(maxImageBytes));
        }

        if (!params.isPDS && imagePixels > Integer.MAX_VALUE) {
            throw new OversizeFileException("non-PDS/VICAR image pixels " + Utils.kmg(imagePixels) + " > " +
                                            Utils.kmg(Integer.MAX_VALUE));
        }

        largeImage = largeImageThreshold >= 0 && imageBytes > largeImageThreshold;

        if (lruPageBytes > 0 && lruMemCachePages > 0) {
            if (!largeImage && (!params.isPDS || fullLoadSmallPDS) &&
                (imageBytes +  fullLoadMemCachePages * lruPageBytes <= memBudget)) {
                loadAsBufferedImage = true;
                memBudget = (lruMemCachePages - fullLoadMemCachePages) * lruPageBytes;
            }
        } else {
            loadAsBufferedImage = !largeImage && (!params.isPDS || fullLoadSmallPDS) && imageBytes < memBudget;
        }

        if (!params.isPDS && !loadAsBufferedImage) {
            if (largeImage && tileWindowLargeNonPDS) {
                loadAsTileWindowImage = true;
            } else {
                if (imageBytes > Integer.MAX_VALUE) {
                    throw new OversizeFileException("non IMG/VIC decompressed size " + Utils.kmg(imageBytes) +
                                                    " > " + Utils.kmg(Integer.MAX_VALUE) +
                                                    "but height limiting and windowed read disabled");
                }
                //javax.imageio JPEG and PNG loaders load a whole BufferedImage even when requesting RenderedImage
                //javax.imageio TIFF loader does actually implement RenderedImage, but then loading individual tiles
                //from compressed TIFF data can be inefficient
                if (largeImage) {
                    log.warn(pfx + Utils.kmg(imageBytes) + " image exceeds " + Utils.kmg(largeImageThreshold));
                }
                loadAsBufferedImage = true;
            }
        }

        long maxWindowBytes = (long)Integer.MAX_VALUE * imageInfo.bytesPerPixel();

        if (loadAsTileWindowImage) {

            //if TILE_WINDOW_CHUNK was originally set with a % suffix
            //then setLargeImageParams() converted that to bytes (percent of max mem for all tasks)
            //so here it's either bytes > 1 or fraction of image <= 1
            
            actualTileWindowChunk = tileWindowChunk;
            if (actualTileWindowChunk > 1) { //convert bytes to fraction of image <= 1
                actualTileWindowChunk = Math.min(1, actualTileWindowChunk / imageBytes);
            }

            long wcb = (long)(actualTileWindowChunk * imageBytes);
            long wcl = Math.min(maxWindowBytes, memBudget);
            if (wcb > wcl) {
                double wc = Math.min(1, wcl / (double)imageBytes);
                log.warn(pfx + "reducing tile window chunk from " + actualTileWindowChunk +
                         " to " + wc + ", " + Utils.kmg(wcb) + "b > " + Utils.kmg(wcl) + "b");
                actualTileWindowChunk = wc;
            }

            if (actualTileWindowChunk == 1) {

                loadAsTileWindowImage = false;
                loadAsBufferedImage = true;

            } else {

                int w = imageInfo.width;
                int h = limitHeight > 0 ? limitHeight : imageInfo.height;
                int leafLevel = (int)Math.ceil(Math.log(Math.max(w, h)) / Math.log(2));
                tileWindowTilesX = 1;
                tileWindowTilesY = 1;
                tileWindowMargin = params.tileOverlap;
                
                long wcp = (long)(actualTileWindowChunk * imagePixels);
                int wcm = params.tileSize * params.tileSize;
                int tsto = params.tileSize + params.tileOverlap; //yes just one overlap here...
                if (wcp < wcm) {
                    log.warn(pfx + "invalid tile window chunk " + actualTileWindowChunk + ", " + Utils.kmg(wcp) +
                             "px < " + Utils.kmg(wcm) + "px, using window = one DZI tile");
                } else if (w < tsto || h < tsto) {
                    log.warn(pfx + "degenerate image size " + w + "x" + h + ", using window = one DZI tile");
                } else {
                    //trying to use same logic here as is used in gov.nist.isg.pyramidio.TileBuilder
                    //to compute chunk read level (which it calls "cache level")
                    int mcs = (int)Math.floor(Math.sqrt(wcp));
                    for (int level = 0; level <= leafLevel; level++) {
                        int f = 1 << (leafLevel - level);
                        int lw = (int)Math.ceil(((float)w) / f);
                        int lh = (int)Math.ceil(((float)h) / f);
                        if (lw < tsto || lh < tsto) {
                            continue;
                        }
                        int ltw = Math.min(w, tsto * f);
                        int lth = Math.min(h, tsto * f);
                        if (ltw <= mcs && lth <= mcs) { //this is the level that TileBuilder calls "cache level"
                            tileWindowMargin = f * params.tileOverlap; //b/c of how TileBuilder does chunk reads
                            //now redo computation using the actual tile size
                            ltw = Math.min(w, params.tileSize * f);
                            lth = Math.min(h, params.tileSize * f);
                            tileWindowTilesX = (int)Math.ceil(((float)ltw) / params.tileSize);
                            tileWindowTilesY = (int)Math.ceil(((float)lth) / params.tileSize);
                            break;
                        }
                    }
                }
                
                int wx = tileWindowTilesX * params.tileSize;
                int wy = tileWindowTilesY * params.tileSize;
                double wc = (double)(wx * wy) / imagePixels;
                log.info(pfx + "actual tile window chunk " + wc +
                         (wc != actualTileWindowChunk ? " (originally " + actualTileWindowChunk + ")" : "") +
                         ", window size " + tileWindowTilesX + "x" + tileWindowTilesY + " DZI tiles, "
                         + wx + "x" + wy + "px, " + tileWindowMargin + "px margin, " +
                         Utils.kmg(tileWindowBytes()) + "b");
                actualTileWindowChunk = wc;

                if (actualTileWindowChunk < minTileWindowChunk) {
                    throw new OversizeFileException("non IMG/VIC tile window chunk " + actualTileWindowChunk +
                                                    " < " + minTileWindowChunk);
                }
            }
        }

        if (loadAsBufferedImage && imageBytes > memBudget) {
            log.warn(pfx + "loading full " + Utils.kmg(imageBytes) + " image, memory budget " + Utils.kmg(memBudget) +
                     " exceeded");
        }

        actualReadChunk = readChunk;

        if (loadAsBufferedImage || loadAsTileWindowImage) {
            //disable chunk reads if loading as full buffered image
            //in that case random reads are never expensive so DZI parallelism will not be disabled
            //but leave chunk reads enabled if loading as tile window image
            //this will make an extra copy of the tile window image inside the DZI builder
            //but will enable DZI parallelism to remain enabled
            //because the DZI builder only runs tasks in parallel within each chunk in that situation
            //so reads will be serialized across tile windows
            boolean disableReadChunk = !loadAsTileWindowImage && actualReadChunk > 0;
            log.info(pfx +
                     "loading " + (loadAsBufferedImage ? "full" : "tile window") + " image" +
                     (disableReadChunk ? ", disabling chunk reads" : "")
                     + ((fullLoadMemCachePages != actualMemCachePages) ?
                        (", limiting mem cache pages from " + actualMemCachePages + " to " + fullLoadMemCachePages)
                        : ""));
            actualMemCachePages = fullLoadMemCachePages;
            if (disableReadChunk) actualReadChunk = 0;
        }

        if (actualReadChunk > 1) { //convert bytes to fraction
            actualReadChunk = Math.min(1, actualReadChunk / imageBytes);
        }

        if (actualReadChunk > 0) {
            long rcb = (long)((double)actualReadChunk * imageBytes);
            long rcl = Math.min(maxWindowBytes, memBudget);
            if (rcb > rcl) {
                double rc = (rcl - 1) / (double)imageBytes;
                log.warn(pfx + "reducing read chunk from " + actualReadChunk +
                         " to " + rc + ", " + Utils.kmg(rcb) + " > " + Utils.kmg(rcl));
                actualReadChunk = rc;
            }
            long rcp = (long)(actualReadChunk * imagePixels);
            long rcs = params.tileSize + 2 * params.tileOverlap;
            long rcm = rcs * rcs;
            if (rcp < rcm) {
                log.warn(pfx + "disabling read chunk " + actualReadChunk + ", " +
                         Utils.kmg(rcp) + "px < " + Utils.kmg(rcm) + "px");
                actualReadChunk = 0;
            }
        }

        if (loadAsTileWindowImage && actualReadChunk > actualTileWindowChunk) {
            log.warn(pfx + "clamping read chunk " + actualReadChunk + " to tile window chunk " + actualTileWindowChunk);
            actualReadChunk = actualTileWindowChunk;
        }

        int defPDSTileWidth = DEF_PDS_TILE_WIDTH <= 0 ? imageInfo.width : DEF_PDS_TILE_WIDTH;
        actualPDSTileWidth = Math.min(imageInfo.width, pdsTileWidth <= 0 ? defPDSTileWidth : pdsTileWidth);

        int defPDSTileHeight = DEF_PDS_TILE_HEIGHT <= 0 ? 1 : DEF_PDS_TILE_HEIGHT;
        actualPDSTileHeight = Math.min(limitHeight > 0 ? limitHeight : imageInfo.height,
                                       pdsTileHeight <= 0 ? defPDSTileHeight : pdsTileHeight);

        randomReadsAreExpensive = !loadAsBufferedImage && (params.rdrUrlIsS3 || params.rdrUrlIsHTTP) &&
            rdrBytes > Math.max(actualMemCachePages, actualDiskCachePages) * lruPageBytes;

        randomReadsAreExpensive |= loadAsTileWindowImage;
        
        if (tuningHacks && largeLocalFile && !loadAsBufferedImage) {
            log.warn(pfx + "TUNING HACKS: considering random reads expensive for large local file");
            randomReadsAreExpensive = true;
        }

        //single threaded access pattern has significantly better coherence
        //but when reading in chunks the DZI builder itself disables parallelism except within chunks
        actualDziParallelism = dziParallelism;
        if (randomReadsAreExpensive && (actualReadChunk == 0 || (actualReadChunk > actualTileWindowChunk))) {
            log.warn(pfx + "disabling parallelism, random reads may be expensive");
            actualDziParallelism = 1;
        }

        if (memCacheTiles) {
            log.warn(pfx + "DZI tile memory cache enabled but not accounted in memory budget");
        }
    }

    private ImageInfo loadImageInfo() throws IOException {
        String pfx = params.pfx;
        long maxWaitMS = params.maxWaitSec >= 0 ? (params.maxWaitSec * 1000l) : DziTask.maxWaitMS;
        try {
            if (!imageInfoSemaphore.tryAcquire(maxWaitMS, TimeUnit.MILLISECONDS)) {
                throw new IOException("timed out waiting " + Utils.hms(maxWaitMS) + " to read image metadata");
            }
        } catch (InterruptedException ex) {
            throw new IOException("interrupted waiting " + Utils.hms(maxWaitMS) + " to read image metadata");
        }
        //using fullLoadMemCachePages because we should only use a limited amount of memory here
        //this is called from preRun() which is before TileService has committed to actually allocating resources
        //and unfortunately we can't use the disk cache either
        try (var imageStream = new LRUCacheImageInputStream(params.rdrUrl, inputS3, lruPageBytes, fullLoadMemCachePages,
                                                            0, null)) {

            this.imageStream = imageStream; //for progress reporting

            if (debug || largeFile) log.info(pfx + "loading image metadata");

            if (Utils.hasExt(params.rdrUrl, PNG_EXTS)) {
                //try our own PNG metadata parser, it should work and it stops at the first IDAT chunk
                //unfortunately the imageio parser seems to read the whole image
                Exception err = null;
                try {
                    var info = (new PNGChunksExt(imageStream)).parseMetadata(params.maskBlack);
                    if (info != null) {
                        return info;
                    }
                } catch (Exception ex) {
                    err = ex;
                }
                log.warn(pfx + "failed to get PNG metadata with PNGChunks, falling back to imageio" +
                         (err != null ? (": " + err.getClass().getSimpleName() + " " + err.getMessage()) : ""));
            }

            //RGD sez that SAMPLE_BIT_MASK "really should be there"
            //but it's not in practice for a lot of M20 panos right now
            //so this heruistic seems to be the best stopgap
            //if we don't get the bit depth right the histogram and stretch will be fubared
            int defSigBits = params.isPDS ? (params.isDef15Bit ? 15 : DEF_PDS_BITS) : DEF_PLAIN_BITS;

            //vicario never performs any color conversions when loading an IMG/VIC
            //thus the actual colorspace and gamma of the image data will be whatever it is in the file
            //by default for 3 band images interpreted as RGB, assume the colorspace is linear (gamma=1)
            //exceptions are if there is a DERIVED_IMAGE_PARMS.ENCODED_DISPLAY_GAMMA that is not equal to 1
            //or if the product type is e.g. CPG
            //unfortunately vicario also usually doesn't assign a very useful ColorSpace
            //to the RenderedImage or BufferedImage that it returns
            //in the case of RenderedImage (VicarImageReader.readAsRenderedImage()) 3 band images will say they are sRGB
            //which is often a lie (they are often linear RGB, gamma=1)
            //in the case of BufferedImage (VicarImageReader.read()) 3 band images will have a 3-band BogusColorSpace
            //which is maybe a bit nicer because though it doesn't actually specify the colorspace or gamma
            //at least it's not a lie
            float defGamma = params.isPDS && params.isPreappliedGamma ? 0.45f : Float.NaN;

            var reader = getImageReader(imageStream);
            var metadata = reader.getImageMetadata(0);

            if (verbose) {
                if (metadata != null) {
                    ImageInfo.spewMetadata(metadata, log, pfx + "image metadata ");
                } else {
                    log.debug(pfx + "null image metadata");
                }
                if (debug) log.debug(pfx + "loading stream metadata");
                var smd = reader.getStreamMetadata();
                if (smd != null) {
                    ImageInfo.spewMetadata(smd, log, pfx + "stream metadata ");
                } else {
                    log.debug(pfx + "null stream metadata");
                }
            }

            var info = ImageInfo.parse(metadata, defSigBits, defGamma, params.maskBlack, log, pfx + "image metadata ");

            //for M20 ECM, ECV, ECZ, EDM, and ECR *might* be companded
            //currently in practice ECM and ECV are companded with gamma=1/2.2 iff they are 8 bit
            //note this is handled a bit different than preappliedGammaPatterns and preappliedInstGammaPatterns
            //because we only know if the image is 8 bit now that we've processed its metadata
            //(the image RDR type and instrument, othoh, are available in the filename for PDS images)
            if (params.isPDS && info.significantBits == 8 &&
                Utils.anyMatch(preapplied8BitGammaPatterns, params.productType)) {
                info = info.withGamma(0.45f);
            }

            return info;

        } finally {
            this.imageStream = null;
            imageInfoSemaphore.release();
        }
    }

    public long maxActualTaskMemCacheBytes() {
        return actualMemCachePages * lruPageBytes;
    }

    public long maxActualTaskDiskCacheBytes() {
        return actualDiskCachePages * lruPageBytes;
    }

    public long tileWindowBytes() {
        return imageInfo.bytesPerPixel() *
            (tileWindowTilesX * params.tileSize + 2 * tileWindowMargin) *
            (tileWindowTilesY * params.tileSize + 2 * tileWindowMargin);
    }

    public long maxActualTaskMemory() {
        long bytes = TASK_OVERHEAD_BYTES + maxActualTaskMemCacheBytes();
        if (imageInfo != null) {
            int ts = params.tileSize + 2 * params.tileOverlap;
            int bpp = imageInfo.bytesPerPixel();
            int maxChunkInstances = 2; //RenderedImageReader may make a copy
            bytes += (loadAsBufferedImage ? imageBytes : loadAsTileWindowImage ? tileWindowBytes() : 0) +
                (long)(Math.max(actualReadChunk * imageBytes, ts * ts * bpp) * maxChunkInstances);
        }
        return bytes;
    }

    public long rdrFileBytes() {
        return rdrBytes;
    }

    public long rdrImagePixels() {
        return imagePixels;
    }

    public long rdrImageBytes() {
        return imageBytes;
    }

    synchronized public boolean cancel(String source) {
        if (cancelled) return false;
        cancelSource = source;
        cancelled = true;
        archiver.cancel(source);
        return true;
    }

    synchronized public boolean cancel(String source, String msg, Throwable cause) {
        if (!cancel(source)) {
            return false;
        }
        cancelException = new CancellationException(source, msg, cause);
        return true;
    }

    synchronized private void saveProgress(float percent) {
        try {
            if (percent > progressPercent) {
                archiver.archiveText(String.format("%.2f", percent), PROGRESS_FILE);
                progressPercent = percent;
            }
        } catch (Exception ex) {
            log.error(pfx + "error saving " + PROGRESS_FILE, ex);
        }
    }

    //would be nicer if we could make this a checked exception
    //but we'd like to be able to throw it from CachedArchiver methods that override gov.nist.isg.archiver.FilesArchiver
    public static class CancellationException extends RuntimeException {
        public final String source;
        public CancellationException(String source) {
            this(source, "cancelled" + (source != null ? (" by " + source) : ""));
        }
        public CancellationException(String source, String msg) {
            super(msg);
            this.source = source;
        }
        public CancellationException(String source, String msg, Throwable cause) {
            super(msg, cause);
            this.source = source;
        }
    }

    public void checkCancelled() throws CancellationException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            if (cancelException != null) {
                throw cancelException;
            } else {
                throw new CancellationException(cancelSource);
            }
        }
    }

    private class PNGGammaTransformer implements ExtendedArchiver.Transformer {

        private final float gamma;

        public PNGGammaTransformer(float gamma) {
            this.gamma = gamma;
        }

        @Override
        public boolean shouldTransform(String path) {
            return path.toLowerCase().endsWith(".png");
        }

        @Override
        public void transform(InputStream in, OutputStream out) throws IOException {
            var chunks = new PNGChunksExt(in);
            var dropChunks = new ArrayList<String>();
            dropChunks.add("sRGB");
            dropChunks.add("cHRM");
            dropChunks.add("iCCP");
            chunks.filter(out, dropChunks, gamma);
        }
    }

    public void run() throws IOException, CancellationException {
        String pfx = params.pfx;
        try {
            
            preRun(); //noop if already called, otherwise might throw IllegalArgumentException

            currentTasks.put(this, params.rdrUrl);

            checkCancelled();

            taskInterlock();

            started = true;

            log.info(pfx + "START TASK: processing as " + params.rdrType + " at " + cacheUrl);
            log.info(pfx + "RDR timestamp " + Utils.toISO8601(rdrTimestamp) + ", " + Utils.kmg(rdrBytes) + " bytes" +
                     ", task mem cache " + Utils.kmg(maxActualTaskMemCacheBytes()) +
                     ", task disk cache " + Utils.kmg(maxActualTaskDiskCacheBytes()));

            if (debug) dumpSettings();

            try {
                log.debug(pfx + "deleting cache files");
                int nd = archiver.deleteFilesRecursive(f -> !f.endsWith(pidFile));
                if (nd > 0) {
                    log.debug(pfx + "deleted " + nd + " existing cache files at " + cacheUrl);
                }
            } catch (IOException ex) {
                log.warn(pfx + "error deleting existing cache at " + cacheUrl, ex);
            }

            checkCancelled();

            //write etag file first as sentinel that processing started
            //NOTE: as of 12/2020 S3 now provides strong consistency
            //https://aws.amazon.com/blogs/aws/amazon-s3-update-strong-read-after-write-consistency
            //https://docs.aws.amazon.com/AmazonS3/latest/dev/Introduction.html#ConsistencyModel
            String etag = "null";
            if (tuningHacks && largeLocalFile) {
                log.warn(pfx + "TUNING HACKS: skipping etag for large local file");
            } else {
                if (debug || largeLocalFile) log.debug(pfx + "getting etag/MD5");
                etag = params.rdrUrlIsS3 ? inputS3.getObjectETag(params.rdrUrl) :
                    params.rdrUrlIsHTTP ? HTTPHelper.getETag(params.rdrUrl) :
                    Utils.getFileMD5(params.rdrUrl);
                if (debug) log.debug(pfx + "etag: " + etag);
            }
            archiver.archiveText(etag, ETAG_FILE);

            checkCancelled();

            if (debug) log.debug(pfx + "opening input file");
            try (var imageStream = new LRUCacheImageInputStream(params.rdrUrl, inputS3, lruPageBytes,
                                                                actualMemCachePages, actualDiskCachePages,
                                                                actualDiskCacheDir)) {
                //new FileImageInputStream(new File(params.rdrUrl))) {

                this.imageStream = imageStream;

                RenderedOp inputImage = openImage(imageStream, params.rdrUrl);

                checkCancelled();

                boolean isFloat = ImageInfo.isFloat(inputImage);
                boolean estimateExtrema = !neverEstimateExtremaFromHistogram && params.stretchable && !isFloat &&
                    (randomReadsAreExpensive || alwaysEstimateExtremaFromHistogram);
                int numTiles = inputImage.getNumXTiles() * inputImage.getNumYTiles();
                boolean jaiHisto = histogramMode == HistogramMode.jai;
                expectedHistogramSteps = (!params.stretchable || jaiHisto) ? 0 : numTiles;
                expectedExtremaSteps = (!params.stretchable || jaiHisto || estimateExtrema) ? 0 : numTiles;
                expectedFiles = getExpectedFiles(inputImage.getWidth(), inputImage.getHeight());

                JsonObjectBuilder metadata = Json.createObjectBuilder();
                params.toJson(metadata, false, false);
                metadata.add("processing_timestamp", Utils.toISO8601(startMS));
                metadata.add("cache_hash", params.hashCode);
                metadata.add("custom", !params.isNominal);
                metadata.add("tiler_version", getVersion());
                metadata.add("rdr_bytes", rdrBytes);
                metadata.add("rdr_timestamp", Utils.toISO8601(rdrTimestamp));
                metadata.add("rdr_etag", etag);
                metadata.add("is_overlay", params.overlayable);
                metadata.add("width", inputImage.getWidth());
                metadata.add("height", inputImage.getHeight());
                metadata.add("original_is_float", ImageInfo.isFloat(inputImage));
                metadata.add("original_bits", imageInfo.significantBits);
                metadata.add("original_bands", inputImage.getNumBands());

                if (!Float.isNaN(imageInfo.gamma)) {
                    metadata.add("original_gamma", imageInfo.gamma);
                }

                //PDS images (img, vic) are always either overlayable or stretchable but never both

                //PDS images never have alpha afaik

                //PDS images that are not overlayable should have 1 or 3 bands (greyscale or RGB) but may be float or
                //int with various bit depths.  After stretch processing they should be 3 band 8 bit RGB.

                //PDS images that are overlayable may have any format initially but after overlay processing should be 3
                //band 8 bit RGB

                //plain images (png, jpg, tiff) are never overlayable but may be subject to maskBlack

                //plain images are always stretchable
                //but the default stretch for plain images is a noop

                //plain images may have alpha but typically don't

                //plain images may be 1 or 3 bands (greyscale or RGB)

                metadata.add("stretch_type", "none"); //may be overwritten

                RenderedImageReader reader = null;
                if (params.overlayable) {
                    if (debug) log.debug(pfx + "computing overlay");
                    RenderedOp overlay = computeOverlay(inputImage);
                    metadata.add("overlay_bands", overlay.getNumBands());
                    reader = new RenderedImageReader(overlay);
                } else {
                    reader = new RenderedImageReader(inputImage);
                }

                checkCancelled();

                reader.mask = computeMask(inputImage);
                reader.unmaskedAlpha = params.unmaskedAlpha;
                reader.maskedAlpha = params.maskedAlpha;

                checkCancelled();

                int inBits = params.isPDS ? imageInfo.significantBits : inputImage.getSampleModel().getSampleSize(0);

                if (debug) {
                    log.debug(pfx + "isPDS=" + params.isPDS + ", isFloat=" + isFloat + ", inBits=" + inBits);
                }

                Histogram histogram = null;
                //apply stretch even to 8 bit images because inBits is the number of *significant* bits
                //the actual data storage may be higher
                //also, stretching (at least custom stretching) should be applied to user uploads which are often png
                if (params.stretchable) {

                    double[][] extrema = null;
                    if (!estimateExtrema) {
                        if (debug || largeFile) log.info(pfx + "computing extrema");
                        extrema = computeExtrema(inputImage);
                        metadata.add("min_max_estimated", false);
                    }

                    checkCancelled();

                    if (debug || largeFile) log.info(pfx + "computing histogram, mode: " + histogramMode);
                    if (isFloat) { //implies extrema were already computed

                        reader.minBandValue = Float.POSITIVE_INFINITY;
                        for (double val : extrema[0]) reader.minBandValue = Math.min(reader.minBandValue, (float)val);
                        if (Float.isNaN(reader.minBandValue) || Float.isInfinite(reader.minBandValue)) {
                            throw new IllegalArgumentException("invalid min float input DN: " + reader.minBandValue);
                        }

                        reader.maxBandValue = Float.NEGATIVE_INFINITY;
                        for (double val : extrema[1]) reader.maxBandValue = Math.max(reader.maxBandValue, (float)val);
                        if (Float.isNaN(reader.maxBandValue) || Float.isInfinite(reader.maxBandValue)) {
                            throw new IllegalArgumentException("invalid max float input DN: " + reader.maxBandValue);
                        }

                        histogram = computeHistogram(inputImage, maxHistogramBins, reader.minBandValue,
                                                     reader.maxBandValue + ImageInfo.FLOAT_QUANTUM);
                    } else {
                        reader.minBandValue = 0;
                        reader.maxBandValue = (float)((1L << inBits) - 1);
                        histogram = computeHistogram(inputImage);
                    }

                    if (debug) {
                        log.debug(pfx + "input DN range: [" + reader.minBandValue + ", " + reader.maxBandValue + "]");
                    }

                    checkCancelled();

                    if (estimateExtrema) {
                        if (debug) log.info(pfx + "estimating extrema from histogram");
                        extrema = computeExtrema(histogram);
                        metadata.add("min_max_estimated", true);
                    }

                    checkCancelled();

                    var min = Json.createArrayBuilder();
                    for (double val : extrema[0]) {
                        if (!Double.isNaN(val) && !Double.isInfinite(val)) {
                            min.add(val);
                        } else {
                            min.add(Double.valueOf(val).toString());
                        }
                    }
                    metadata.add("unstretched_min", min);

                    var max = Json.createArrayBuilder();
                    for (double val : extrema[1]) {
                        if (!Double.isNaN(val) && !Double.isInfinite(val)) {
                            max.add(val);
                        } else {
                            max.add(Double.valueOf(val).toString());
                        }
                    }
                    metadata.add("unstretched_max", max);

                    if (params.stretchType == DziParams.StretchType.percent) {
                        //params.stretchLow and params.stretchHigh are guaranteed to be non-NaN for percent stretch
                        if (debug) {
                            log.debug(pfx + "percent stretching, " +
                                      "min: " + params.stretchLow + "%, max: " + params.stretchHigh + "%");
                        }
                        var range = computePercentStretch(inputImage, histogram, params.stretchLow, params.stretchHigh);
                        reader.stretchLow = range[0];
                        reader.stretchHigh = range[1];
                        metadata.add("stretch_type", "histogram_percent");
                        metadata.add("stretch_low_percent",
                                     !Float.isNaN(adjStretchLowPercent) ? adjStretchLowPercent : params.stretchLow);
                        metadata.add("stretch_high_percent",
                                     !Float.isNaN(adjStretchHighPercent) ? adjStretchHighPercent : params.stretchHigh);
                    } else if (params.stretchType == DziParams.StretchType.relative) {
                        if (debug) log.debug(pfx + "relative stretching, " +
                                             "min: " + params.stretchLow + ", max: " + params.stretchHigh);
                        var range = computeRelativeStretch(isFloat, extrema, reader.minBandValue, reader.maxBandValue,
                                                           params.stretchLow, params.stretchHigh);
                        reader.stretchLow = range[0];
                        reader.stretchHigh = range[1];
                        metadata.add("stretch_type", "relative");
                        metadata.add("stretch_low_relative",
                                     isFloat || Float.isNaN(params.stretchLow) ? 1f : params.stretchLow);
                        metadata.add("stretch_high_relative",
                                     isFloat || Float.isNaN(params.stretchHigh) ? 1f : params.stretchHigh);
                    } else if (params.stretchType == DziParams.StretchType.extrema) {
                        if (debug) log.debug(pfx + "extrema stretching");
                        reader.stretchLow = Float.isNaN(params.stretchLow) ? reader.minBandValue : params.stretchLow;
                        reader.stretchHigh = Float.isNaN(params.stretchHigh) ? reader.maxBandValue : params.stretchHigh;
                        metadata.add("stretch_type", "extrema");
                    } else {
                        if (debug) log.debug(pfx + "not stretching");
                        reader.stretchLow = reader.minBandValue;
                        reader.stretchHigh = reader.maxBandValue;
                        metadata.add("stretch_type", "none");
                    }

                    if (debug) {
                        log.debug(pfx + "stretch DN range: [" + reader.stretchLow + ", " + reader.stretchHigh + "]");
                    }

                    metadata.add("stretch_low", reader.stretchLow);
                    metadata.add("stretch_high", reader.stretchHigh);
                }

                //these are mutually exclusive
                boolean linearPDS = false, sRGBPDS = false, gammaPDS = false;
                if (params.isPDS && params.stretchable) {
                    linearPDS = Float.isNaN(imageInfo.gamma) || imageInfo.gamma == 1.0f;
                    if (!Float.isNaN(imageInfo.gamma)) {
                        sRGBPDS = Math.abs(imageInfo.gamma - 0.45f) < 0.01f;
                        gammaPDS = !sRGBPDS && !linearPDS;
                    }
                }

                if (gammaPDS) {
                    reader.overrideInputGamma = imageInfo.gamma;
                    if (debug) {
                        log.debug(pfx + "input PDS gamma=" + imageInfo.gamma);
                    }
                }

                switch (params.gammaMode) {
                   case none: {
                        reader.convertsRGBToLinear = false;
                        reader.convertLinearTosRGB = false;
                        reader.outputLinearRGB = false;
                        break;
                    }
                    case passthrough: {
                        reader.convertsRGBToLinear = false;
                        reader.convertLinearTosRGB = false;
                        reader.outputLinearRGB = linearPDS;
                        break;
                    }
                    case convert: {
                        reader.convertsRGBToLinear = sRGBPDS || gammaPDS;
                        reader.convertLinearTosRGB = sRGBPDS || gammaPDS || linearPDS;
                        reader.outputLinearRGB = false;
                        break;
                    }
                    case lineartosrgb: {
                        reader.convertsRGBToLinear = false;
                        reader.convertLinearTosRGB = true;
                        reader.outputLinearRGB = false;
                        break;
                    }
                    case srgbtolinear: {
                        reader.convertsRGBToLinear = true;
                        reader.convertLinearTosRGB = false;
                        reader.outputLinearRGB = true;
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("unknown gamma mode " + params.gammaMode);
                    }
                }

                if (debug) {
                    log.debug(pfx + "linearPDS=" + linearPDS + ", sRGBPDS=" + sRGBPDS + ", gammaPDS=" + gammaPDS);
                    log.debug(pfx + "convertsRGBToLinear=" + reader.convertsRGBToLinear +
                              ", convertLinearTosRGB=" + reader.convertLinearTosRGB +
                              ", outputLinearRGB=" + reader.outputLinearRGB +
                              ", overrideInputGamma=" + reader.overrideInputGamma);
                }

                metadata.add("linear_to_srgb", reader.convertLinearTosRGB);
                metadata.add("srgb_to_linear", reader.convertsRGBToLinear);

                boolean headerLinear = reader.outputLinearRGB && params.tileFormat.toLowerCase().equals("png");
                boolean dataLinear = (linearPDS && !reader.convertLinearTosRGB) || reader.outputLinearRGB;
                metadata.add("tile_header_gamma", headerLinear ? "linear" : "srgb");
                metadata.add("tile_data_gamma", dataLinear ? "linear" : "srgb");

                if (histogram != null) {
                    //add histogram metadata last as it can be bulky
                    metadata.add("histogram_bins", histogram.getNumBins(0));
                    metadata.add("histogram_low_inclusive", histogram.getLowValue(0));
                    metadata.add("histogram_high_exclusive", histogram.getHighValue(0));
                    var histogramMetadata = Json.createArrayBuilder();
                    int[][] bins = histogram.getBins();
                    for (int[] binsForBand : bins) {
                        var histogramBandMetadata = Json.createArrayBuilder();
                        for (int binVal : binsForBand) {
                            histogramBandMetadata.add(binVal);
                        }
                        histogramMetadata.add(histogramBandMetadata);
                    }
                    metadata.add("histogram", histogramMetadata);
                }

                //bake metadata now so that it can be served from ongoing task
                if (debug) log.debug(pfx + "saving metadata");
                metadataJSON = metadata.build();

                checkCancelled();

                if (reader.outputLinearRGB && params.tileFormat.toLowerCase().equals("png")) {
                    archiver.setTransformer(new PNGGammaTransformer(1.0f));
                    if (debug) log.debug(pfx + "forcing output PNG gamma=1");
                }

                if (params.doMonolithic) buildMonolithic(reader);

                if (!params.noDZI) buildDZI(reader);

                checkCancelled();

                //compute and save thumb
                if (!params.noDZI) {
                    if (debug) log.debug(pfx + "saving thumb");
                    thumbnail = computeThumbnail(inputImage.getWidth(), inputImage.getHeight());
                    archiver.archiveImage(thumbnail, THUMB_FORMAT, THUMB_FILE);
                }

                //write metadata file last as sentinel that processing finished
                //NOTE: as of 12/2020 S3 now provides strong consistency
                //https://aws.amazon.com/blogs/aws/amazon-s3-update-strong-read-after-write-consistency
                //https://docs.aws.amazon.com/AmazonS3/latest/dev/Introduction.html#ConsistencyModel
                archiver.archiveText(Utils.printJson(metadataJSON, formatJson), METADATA_FILE);
            
                if (debug) {
                    if (imageStream instanceof LRUCacheImageInputStream) {
                        var lruc = (LRUCacheImageInputStream)imageStream;
                        lruc.dumpStats(log, pfx);
                        //lruc.dumpPageTransfers(log, pfx);
                    }
                    //reader.dumpStats(log, pfx);
                    dumpJAICache(pfx);
                    log.debug(pfx + dumpHeap());
                }

                long ms = System.currentTimeMillis() - startMS;
                int na = archiver.numArchived();
                log.info(pfx + "processed in " + Utils.hms(ms) + ", saved " + na + " files at " + cacheUrl);
            }

        } catch (Exception ex) {

            error = ex;

            //error will be logged by caller

            try {
                archiver.archiveText(ex.getMessage(), ERROR_FILE);
            } catch (Exception ex2) {
                log.warn(pfx + "error saving " + ERROR_FILE + ": " + ex2.getMessage());
            }
            throw ex;

        } finally {

            done = true;

            currentTasks.remove(this);

            saveProgress(100);

            try {
                boolean deleted = archiver.deleteFile(pidFile);
                if (debug) log.debug(pfx + "PID file " + pidFile + (deleted ? " deleted" : " not found"));
            } catch (Exception ex) {
                log.warn(pfx + "error deleting pid file: " + ex.getMessage());
            }

            try {
                archiver.close();
            } catch (Exception ex) {
                log.warn(pfx + "error closing archiver: " + ex.getMessage());
            }

            log.info(pfx + "END TASK" + (cancelled ? ", cancelled" : "") +
                     (error != null ? (", error: " + error.getMessage()) : ""));
        }
    }

    private void taskInterlock() throws IOException, CancellationException {
        String pfx = params.pfx;

        if (debug) log.debug(pfx + "beginning task interlock");

        var now = new MutableLong();
        now.setValue(System.currentTimeMillis());

        Map<String, Long> existingPIDs = archiver.getFileTimes(f -> f.endsWith(PID_FILE_EXT), false);
        long numZombies = existingPIDs.values().stream().filter(t -> ((now.longValue() - t) > maxZombieMS)).count();
        long numLive = existingPIDs.size() - numZombies;

        if (debug) {
            log.debug(pfx + existingPIDs.size() + " existing PIDs, " + numZombies + " zombies, " + numLive + " live");
        }

        if (numLive > 0 && !params.force) {
            throw new CancellationException("lost-interlock", numLive + "/" + existingPIDs.size() +
                                            " tasks for same product still running");
        }

        //get here iff
        // * no existing PIDs
        // * or all existing PIDs are zombies
        // * or force=true
        
        if (existingPIDs.size() > 0) {
            log.info(pfx + "requesting " + existingPIDs.size() + " other tasks (" + numLive + " live) " +
                     "for same product to abort");
            archiver.archiveText(pid, ABORT_FILE);
        }

        if (numLive > 0 && maxInterlockWaitMS != 0) {

            log.info(pfx + "waiting " + (maxInterlockWaitMS > 0 ? ("up to " + Utils.hms(maxInterlockWaitMS)) : "") +
                     "for " + numLive + " other live tasks for same product to abort");

            now.setValue(System.currentTimeMillis());
            long deadlineMS = maxInterlockWaitMS > 0 ? now.longValue() + maxInterlockWaitMS : -1;
            while (numLive > 0) {
                if (deadlineMS > 0 && System.currentTimeMillis() > deadlineMS) {
                    throw new CancellationException("interlock", "timed out waiting " + Utils.hms(maxInterlockWaitMS) +
                                                    " for " + existingPIDs.size() + " other tasks to abort");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    throw new CancellationException("interlock", "interrupted waiting for " + existingPIDs.size() +
                                                    " other tasks to abort");
                }
                now.setValue(System.currentTimeMillis());
                existingPIDs = archiver.getFileTimes(f -> f.endsWith(PID_FILE_EXT), false);
                numZombies = existingPIDs.values().stream().filter(t -> ((now.longValue() - t) > maxZombieMS)).count();
                numLive = existingPIDs.size() - numZombies;
            }
        } else if (debug && numLive > 0) {
            log.debug(pfx + "interlock live task wait disabled");
        }

        if (debug) log.debug(pfx + "writing PID file " + pidFile);

        archiver.archiveText(pid, pidFile);

        if (taskInterlockMS > 0) {
            log.info(pfx + "task interlock waiting " + Utils.hms(taskInterlockMS));
            try {
                Thread.sleep(taskInterlockMS);
            } catch (InterruptedException ex) {
                throw new CancellationException("interlock", "interrupted waiting task interlock time " +
                                                Utils.hms(taskInterlockMS));
            }
        } else if (debug) {
            log.debug(pfx + "task interlock wait disabled");
        }

        existingPIDs = archiver.getFileTimes(f -> f.endsWith(PID_FILE_EXT), false);
        long maxTime = -1;
        long myTime = -1;
        String maxPID = null;
        for (var entry : existingPIDs.entrySet()) {
            String file = entry.getKey();
            long time = entry.getValue();
            if (file.endsWith(pidFile)) {
                myTime = time;
            }
            if (time > maxTime) {
                maxTime = time;
                maxPID = file;
                int lastSlash = Math.max(maxPID.lastIndexOf('/'), maxPID.lastIndexOf(File.separator));
                if (lastSlash > 0) {
                    maxPID = maxPID.substring(lastSlash + 1);
                }
                int lastDot = maxPID.lastIndexOf('.');
                if (lastDot > 0) {
                    maxPID = maxPID.substring(0, lastDot);
                }
            }
        }

        if (myTime < 0) {
            throw new CancellationException("interlock", "task PID file " + pidFile + " not found");
        }

        if (myTime < maxTime) {
            throw new CancellationException("lost-interlock", "task PID " + pid.substring(0, 8) +
                                            " (" + Utils.toISO8601(myTime) + ") " + "older than " +
                                            maxPID.substring(0, 8) + " (" + Utils.toISO8601(maxTime) + ")");
        }

        String abortPID = archiver.loadTextFileIfExists(ABORT_FILE);
        if (abortPID != null) {
            if (pid.equals(abortPID)) {
                boolean deleted = archiver.deleteFile(ABORT_FILE);
                if (debug) log.debug(pfx + "abort file " + ABORT_FILE + (deleted ? " deleted" : " not found"));
            } else {
                throw new CancellationException(abortPID.substring(0, 8));
            }
        }
    }

    private void buildMonolithic(RenderedImageReader reader) throws IOException {
        String pfx = params.pfx;
        int lastSlash = params.rdrUrl.lastIndexOf('/');
        int lastDot = params.rdrUrl.lastIndexOf('.');
        String fn = params.rdrUrl;
        if (lastDot > lastSlash) fn = fn.substring(0, lastDot);
        if (lastSlash >= 0 && lastSlash < fn.length() - 1) fn = fn.substring(lastSlash + 1);
        fn = fn + "." + params.tileFormat;
        log.info(pfx + "writing monolithic output image " + fn);
        archiver.archiveImage(reader.read(), params.tileFormat, fn);
    }

    private void buildDZI(RenderedImageReader reader) throws IOException {
        String pfx = params.pfx;
        if (debug || largeFile) {
            log.info(pfx + "building DZI, parallelism " + actualDziParallelism + ", read chunk " + actualReadChunk);
        }
        if (debug) {
            TileBuilder.debug = new TileBuilder.Debug() { public void log(String msg) { log.debug(pfx + msg); } };
        }
        var builder = new ScalablePyramidBuilder(params.tileSize, params.tileOverlap, params.tileFormat, "dzi");
        builder.buildPyramid(reader, "image", archiver, actualDziParallelism, (float)actualReadChunk);
        //archiver is always a CachedArchiver which supports interruption
    }

    @FunctionalInterface
    private interface Getter<T> {
        T get() throws IOException;
    }

    public static class TimeoutException extends IOException {
        public TimeoutException(String message) {
            super(message);
        }
    }

    private <T> T getBlocking(String what, Getter<T> getter) throws IOException {
        String pfx = params.pfx;
        long maxWaitMS = params.maxWaitSec >= 0 ? (params.maxWaitSec * 1000l) : DziTask.maxWaitMS;
        long deadlineMS = maxWaitMS > 0 ? System.currentTimeMillis() + maxWaitMS : -1;
        T val = null;
        for (val = getter.get(); val == null && !done && maxWaitMS != 0; val = getter.get()) {
            if (deadlineMS > 0 && System.currentTimeMillis() > deadlineMS) {
                throw new TimeoutException("timed out waiting " + Utils.hms(maxWaitMS) + " for " + what);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                throw new IOException("interrupted waiting for " + what);
            }
        }
        if (val == null) {
            if (!done) {
                throw new TimeoutException("not waiting for " + what);
            }
            if (error != null) {
                throw new IOException("error computing " + what, error);
            }
            throw new FileNotFoundException(what);
        }
        return val;
    }

    public Exception getErrorBlocking() throws IOException {
        return getBlocking(ERROR_FILE, () -> error);
    }

    public BufferedImage getThumbnailBlocking() throws IOException {
        return getBlocking(THUMB_FILE, () -> thumbnail);
    }
        
    public JsonObject getMetadataBlocking() throws IOException {
        return getBlocking(METADATA_FILE, () -> metadataJSON);
    }

    public String getDZIBlocking() throws IOException {
        return getBlocking(DZI_FILE, () -> archiver.getCachedDZI());
    }

    public InputStream getTileBlocking(String path) throws IOException {
        return getBlocking(path, () -> archiver.getCachedTile(path));
    }

    public float getPercentComplete() {
        if (done) {
            return 100;
        }
        float base = 0;
        int na = archiver != null ? archiver.numArchived() : 0;
        if (expectedHistogramSteps > 0) {
            if (na == 0 && histogramSteps < expectedHistogramSteps) {
                return 10 * ((float)histogramSteps) / expectedHistogramSteps;
            }
            base += 10;
        }
        if (expectedExtremaSteps > 0) {
            if (na == 0 && extremaSteps < expectedExtremaSteps) {
                return base + 10 * ((float)extremaSteps) / expectedExtremaSteps;
            }
            base += 10;
        }
        if (na == 0 || expectedFiles < 1) {
            return base;
        }
        return base + (100 - base) * Math.min(1, ((float)na) / expectedFiles);
    }

    private void dumpSettings() {
        String pfx = params.pfx;
        if (imageInfo != null) imageInfo.spew(log, pfx);
        log.debug(pfx + "mission=" + mission + ", rdrBytes=" + Utils.kmg(rdrBytes) +
                  ", rdrTimestamp=" + Utils.toISO8601(rdrTimestamp) +
                  ", largeFile=" + largeFile + ", largeLocalFile=" + largeLocalFile + ", largeImage=" + largeImage +
                  ", randomReadsAreExpensive=" + randomReadsAreExpensive + ", tuningHacks=" + tuningHacks);
        log.debug(pfx + "imageBytes=" + Utils.kmg(imageBytes) + ", imagePixels=" + Utils.kmg(imagePixels) +
                  ", limitHeight=" + limitHeight + ", loadAsBufferedImage=" + loadAsBufferedImage);
        log.debug(pfx + "loadAsTileWindowImage=" + loadAsTileWindowImage +
                  ", tileWindowChunk=" + actualTileWindowChunk + ", tileWindowMargin=" + tileWindowMargin +
                  ", tileWindowTilesX=" + tileWindowTilesX + ", tileWindowTilesY=" + tileWindowTilesY +
                  ", tileWindowBytes=" + Utils.kmg(tileWindowBytes()));
        log.debug(pfx + "largeFileThreshold=" + Utils.kmg(largeFileThreshold) +
                  ", largeImageThreshold=" + Utils.kmg(largeImageThreshold) +
                  ", FULL_LOAD_MAX_MEM_CACHE_PAGES=" + FULL_LOAD_MAX_MEM_CACHE_PAGES);
        log.debug(pfx + "jaiCacheCapacity=" + Utils.kmg(getJAICacheCapacity()) +
                  ", TASK_OVERHEAD_BYTES=" + Utils.kmg(TASK_OVERHEAD_BYTES) +
                  ", lruPageBytes=" + Utils.kmg(lruPageBytes) + 
                  ", maxActualTaskMemory=" + Utils.kmg(maxActualTaskMemory()));
        log.debug(pfx + "memoryBudget=" + Utils.kmg(memoryBudget) + ", actualMemCachePages=" + actualMemCachePages +
                  ", maxActualTaskMemCacheBytes=" + Utils.kmg(maxActualTaskMemCacheBytes()) +
                  " (limit " + Utils.kmg(lruPageBytes * lruMemCachePages) + ")");
        log.debug(pfx + "diskBudget=" + Utils.kmg(diskBudget) +
                  ", actualDiskCachePages=" + actualDiskCachePages +
                  ", maxActualTaskDiskCacheBytes=" + Utils.kmg(maxActualTaskDiskCacheBytes()) +
                  " (limit " + Utils.kmg(lruPageBytes * lruDiskCachePages) + ")" +
                  ", actualDiskCacheDir=" + actualDiskCacheDir);
        log.debug(pfx + "TileBuilder actualDziParallelism=" + actualDziParallelism + " (max " + dziParallelism + ")" +
                  ", actualReadChunk=" + actualReadChunk + ", memCacheTiles=" + memCacheTiles);
        log.debug(pfx + "tileFormat=" + params.tileFormat + ", tileSize=" + params.tileSize +
                  ", tileOverlap=" + params.tileOverlap + ", thumbHeight=" + params.thumbHeight);
        log.debug(pfx + "pdsTileWidth=" + actualPDSTileWidth + ", pdsTileHeight=" + actualPDSTileHeight);
        String osMsg = "";
        if (params.overlayable) {
            osMsg += "overlayPreferences=" + String.join(", ", params.overlayPreferences.toArray(new String[0]));
        } else {
            osMsg += "not overlayable";
        }
        osMsg += ", ";
        if (params.stretchable) {
            osMsg += "stretchType=" + params.stretchType +
                ", stretchLow=" + params.stretchLow + ", stretchHigh=" + params.stretchHigh;
        } else {
            osMsg+= "not stretchable";
        }
        log.debug(pfx + osMsg);
        log.debug(pfx + "maskBlack=" + params.maskBlack + ", unmaskedAlpha=" + params.unmaskedAlpha +
                  ", maskedAlpha=" + params.maskedAlpha + ", gammaMode=" + params.gammaMode);
    }

    private int getExpectedFiles(int width, int height) {
        int numFiles = 1; //archiver accounts DZI file and tile images, but not other things like thumb, metadata, etc
        while (width > 1 || height > 1) {
            int tileCols = (int)Math.ceil(((float)width) / params.tileSize);
            int tileRows = (int)Math.ceil(((float)height) / params.tileSize);
            numFiles += tileCols * tileRows;
            width /= 2;
            height /= 2;
        }
        return numFiles;
    }

    private ImageReader getImageReader(ImageInputStream iis) throws IOException {

        String pfx = params.pfx;

        var readers = new ArrayList<ImageReader>();
        for (Iterator it = ImageIO.getImageReaders(iis); it.hasNext(); ) {
            readers.add((ImageReader)it.next());
        }
        
        ImageReader reader = null;
        for (ImageReader ir : readers) {
            if (reader == null /* || (!reader.getClass().getName().contains("imageio") &&
                                   ir.getClass().getName().contains("imageio")) */) {
                reader = ir;
            }
        }

        if (reader == null) {
            throw new IOException("no compatible image reader");
        }
        
        if (debug) {
            Function<ImageReader, String> readerName = r -> {
                String fn = "";
                try {
                    fn = " (" + r.getFormatName() + ")"; 
                } catch (IOException ex) {
                    //ignore
                }
                return r.getClass().getName() + fn;
            };
            log.debug(pfx + "using " + readerName.apply(reader));
            if (readers.size() > 1) {
                log.debug(pfx + readers.size() + " readers available: " +
                          String.join(", ", readers.stream().map(readerName).toArray(n -> new String[n])));
            }
        }

        reader.setInput(iis);

        return reader;
    }

    //parses image header
    //
    //when large{File,Image}Threshold are not exceeded the image data will also be fully loaded here, otherwise
    //* for img/vic the image data will be loaded as JAI tiles are accessed
    //* for other formats the image will be read in subframes as tiles or Rasters are accessed
    //
    //url is only used for logging and to determine defaults depending on file extension
    private RenderedOp openImage(ImageInputStream iis, String url) throws IOException {
        try {
            
            String pfx = params.pfx;

            ImageReader reader = getImageReader(iis);
            String readerName = reader.getClass().getName() + " (" + reader.getFormatName() + ")";

            ImageReadParam param = reader.getDefaultReadParam();

            if (limitHeight > 0) {
                log.warn(pfx + "loading only " + limitHeight + "/" + imageInfo.height + " lines of source image");
                param.setSourceRegion(new Rectangle(0, 0, imageInfo.width, limitHeight));
            }

            RenderedImage ri = null;
            if (loadAsBufferedImage) {
                ri = reader.read(0, param);
            } else if (loadAsTileWindowImage) {
                var twi = new TileWindowImage(reader, 0, param, imageInfo.width,
                                              limitHeight > 0 ? limitHeight : imageInfo.height,
                                              tileWindowTilesX, tileWindowTilesY, params.tileSize, params.tileSize,
                                              tileWindowMargin);
                if (debug) twi.setLogger(log, pfx);
                twi.useJAICache(true);
                ri = twi;
            } else {
                ri = reader.readAsRenderedImage(0, param);
            }

            if (ri instanceof VicarRenderedImage) { //this covers both IMG and VIC
                var vri = (VicarRenderedImage) ri;
                vri.setTileWidth(actualPDSTileWidth);
                vri.setTileHeight(actualPDSTileHeight);
                vri.getNewSampleModel();
            } else if (Utils.hasExt(url, PDS_EXTS) && !(loadAsBufferedImage || loadAsTileWindowImage)) {
                log.warn(pfx + "did not load as a VICAR rendered image, reader: " + readerName);
            }

            RenderedOp image = NullDescriptor.create(ri, null); //RenderedImage -> RenderedOp

            if (debug) log.debug(pfx + "loaded " + ImageInfo.describeImage(image, ri.getClass()));
            
            return image;

        } catch (Exception ex) {
            throw new IOException("failed to load image " + url, ex);
        }
    }

    private Raster getTile(RenderedOp image, int tx, int ty) throws IOException {
        try {
            return image.getTile(tx, ty);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.startsWith("IOException")) { //freaking VICARIO
                throw new IOException(msg);
            }
            throw ex;
        }
    }

    private Histogram computeHistogram(RenderedOp image) throws IOException, CancellationException {
        //based on marsviewer RdrFormatImageContent.java and StretchManager.java
        image = removeAlphaChannel(image, "histogram");
        int bits = imageInfo.significantBits;
        int bins = maxHistogramBins;
        double lowInclusive = 0;
        double highExclusive = bins;
        if (ImageInfo.isFloat(image)) {
            //in some kinds of imaging float images that are to be considered color or grayscale are in range [0.0,1.0]
            //but actually for missions like MSL and M20 that's not typical
            //rather float images are generally in some kind of physical units like meters or lumens or whatever
            //for that reason in run() above we actually compute the extrema first for float images
            //and compute the histogram relative to those
            //(so in practice, should never get here)
            highExclusive = 1.0 + ImageInfo.FLOAT_QUANTUM;
        } else if (bits > 0) {
            if (bits <= 30) { //Integer.MAX_VALUE = 2^31 - 1
                int numValues = 1 << bits;
                if (numValues < bins)  bins = numValues; 
            }
            highExclusive = ((long)1) << bits;
        }
        var histo = computeHistogram(image, bins, lowInclusive, highExclusive);
        if (histogramSteps != expectedHistogramSteps) {
            throw new RuntimeException("total histogram steps " + histogramSteps + "!=" + expectedHistogramSteps);
        }
        return histo;
    }

    private Histogram computeHistogram(RenderedOp image, int bins, double lowInclusive, double highExclusive)
        throws IOException, CancellationException {
        histogramSteps = 0;
        switch (histogramMode) {
            case jai: {
                //this works fine but we can't control the access order
                //it appears to access tiles in row-major raster order starting from the top left
                RenderedOp histOp =
                    HistogramDescriptor.create(image, null, 1, 1, //roi, xPeriod, yPeriod
                                               new int[] { bins },
                                               new double[] { lowInclusive }, new double[] { highExclusive }, 
                                               null); //hints
                return (Histogram)(histOp.getProperty("histogram"));
            }
            case reverseRaster: {
                //this is better, reverses the access order
                //which is ~complimentary to the access order in gov.nist.isg.pyramidio.TileBuilder
                //which is much better for the LRU caches
                Histogram histogram = new Histogram(bins, lowInclusive, highExclusive, image.getNumBands());
                int minX = image.getMinTileX();
                int minY = image.getMinTileY();
                for (int ty = minY + image.getNumYTiles() - 1; ty >= minY; ty--) {
                    for (int tx = minX + image.getNumXTiles() - 1; tx >= minX; tx--) {
                        checkCancelled();
                        Raster tile = getTile(image, tx, ty);
                        var roi = new ROIShape(tile.getBounds());
                        histogram.countPixels(tile, roi, tile.getMinX(), tile.getMinY(), 1, 1);
                        histogramSteps++;
                    }
                }
                return histogram;
            }
            case reverseQuadtree: {
                //if the image tiles are square this more precisely reverses the access order in TileBuilder
                Histogram histogram = new Histogram(bins, lowInclusive, highExclusive, image.getNumBands());
                int minX = image.getMinTileX();
                int minY = image.getMinTileY();
                int maxX = minX + image.getNumXTiles() - 1;
                int maxY = minY + image.getNumYTiles() - 1;
                var roi = new ROIShape(image.getBounds());
                computeHistogramReverseQuadtree(image, roi, histogram, minX, maxX, minY, maxY);
                return histogram;
            }
            default: throw new IllegalArgumentException("unsupported histogram mode: " + histogramMode);
        }
    }

    private void computeHistogramReverseQuadtree(RenderedOp image, ROI roi, Histogram histo, int minX, int maxX,
                                                 int minY, int maxY) throws IOException, CancellationException {
        checkCancelled();

        if (maxX < minX || maxY < minY) {
            return;
        }

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;

        if (loadAsTileWindowImage && w <= tileWindowTilesX && h <= tileWindowTilesY) {
            for (int ty = maxY; ty >= minY; ty--) {
                for (int tx = maxX; tx >= minX; tx--) {
                    checkCancelled();
                    Raster tile = getTile(image, tx, ty);
                    histo.countPixels(tile, roi, tile.getMinX(), tile.getMinY(), 1, 1);
                    histogramSteps++;
                }
            }
            return;
        }

        if (minX == maxX && minY == maxY) {
            Raster tile = getTile(image, minX, minY);
            histo.countPixels(tile, roi, tile.getMinX(), tile.getMinY(), 1, 1);
            histogramSteps++;
            return;
        }
        
        //reverse order of computation in gov.nist.isg.pyramidio.TileBuilder
        //also tuned to minimize thrashing when using TileWindowImage
        int hw = closestPowerOfTwo(w);
        int hh = closestPowerOfTwo(h);
        if (loadAsTileWindowImage && h <= tileWindowTilesY && hw <= tileWindowTilesX) {
            computeHistogramReverseQuadtree(image, roi, histo, minX + hw, maxX         , minY     , maxY         ); //R
            computeHistogramReverseQuadtree(image, roi, histo, minX     , minX + hw - 1, minY     , maxY         ); //L
        } else if (loadAsTileWindowImage && w <= tileWindowTilesX && hh <= tileWindowTilesY) {
            computeHistogramReverseQuadtree(image, roi, histo, minX     , maxX         , minY + hh, maxY         ); //B
            computeHistogramReverseQuadtree(image, roi, histo, minX     , maxX         , minY     , minY + hh - 1); //T
        } else {
            computeHistogramReverseQuadtree(image, roi, histo, minX + hw, maxX         , minY + hh, maxY         ); //BR
            computeHistogramReverseQuadtree(image, roi, histo, minX     , minX + hw - 1, minY + hh, maxY         ); //BL
            computeHistogramReverseQuadtree(image, roi, histo, minX + hw, maxX         , minY     , minY + hh - 1); //TR
            computeHistogramReverseQuadtree(image, roi, histo, minX     , minX + hw - 1, minY     , minY + hh - 1); //TL
        }
    }

    private static int closestPowerOfTwo(int v) {
        if (v <= 1) {
            return v;
        }
        for (int i = 30; i >= 0; i--) {
            int pot = 1 << i;
            if (pot < v) {
                return pot;
            }
        }
        return v;
    }

    //extrema[0][band] = min pixel value in band
    //extrema[1][band] = max pixel value in band
    private double[][] computeExtrema(RenderedOp image) throws IOException, CancellationException {

        extremaSteps = 0;

        image = removeAlphaChannel(image, "extrema");

        double[][] extrema = null;
        int nb = image.getNumBands();

        if (histogramMode == HistogramMode.reverseRaster || histogramMode == HistogramMode.reverseQuadtree) {

            var px = new double[nb];
            extrema = new double[2][nb];
            for (int i = 0; i < nb; i++) {
                extrema[0][i] = Double.POSITIVE_INFINITY;
                extrema[1][i] = Double.NEGATIVE_INFINITY;
            }
            int minX = image.getMinTileX();
            int minY = image.getMinTileY();
            int maxX = minX + image.getNumXTiles() - 1;
            int maxY = minY + image.getNumYTiles() - 1;

            //opposite access order of histogram
            if (histogramMode == HistogramMode.reverseRaster) {
                for (int ty = minY; ty <= maxY; ty++) {
                    for (int tx = minX; tx <= maxX; tx++) {
                        accumulateExtrema(extrema, px, getTile(image, tx, ty));
                    }
                }
            } else {
                computeExtremaQuadtree(image, extrema, px, minX, maxX, minY, maxY);
            }

        } else {
            //this mostly works fine but we can't control the access order
            //it appears to access tiles in row-major raster order starting from the top left
            RenderedOp extremaOp = ExtremaDescriptor.create(image, null, 1, 1, //roi, xPeriod, yPeriod
                                                            false, 1, //saveLocations, maxRuns
                                                            null); //hints
            extrema = (double[][])extremaOp.getProperty("extrema");
        }

        for (int i = 0; i < nb; i++) {
            if ((Double.isNaN(extrema[0][i]) && Double.isNaN(extrema[1][i])) ||
                (Double.isInfinite(extrema[0][i]) && Double.isInfinite(extrema[1][i]))) {
                log.warn(pfx + "min band " + i + " value: " + extrema[0][i] + ", max value: " + extrema[1][i] +
                         ", treating both as 0");
                extrema[0][i] = extrema[1][i] = 0;
            } else if (Double.isNaN(extrema[0][i]) || Double.isInfinite(extrema[0][i])) {
                double fakeMin = extrema[1][i] - 100;
                log.warn(pfx + "min band " + i + " value: " + extrema[0][i] + ", max value: " + extrema[1][i] +
                         ", setting min=" + fakeMin);
                extrema[0][i] = fakeMin;
            } else if (Double.isNaN(extrema[1][i]) || Double.isInfinite(extrema[1][i])) {
                double fakeMax = extrema[0][i] + 100;
                log.warn(pfx + "min band " + i + " value: " + extrema[0][i] + ", max value: " + extrema[1][i] +
                         ", setting max=" + fakeMax);
                extrema[1][i] = fakeMax;
            } else if (extrema[0][i] > extrema[1][i]) { //I don't think this should be possible, but whatever
                double fakeMin = extrema[1][i] - 100;
                log.warn(pfx + "min band " + i + " value: " + extrema[0][i] + ", max value: " + extrema[1][i] +
                         ", setting min=" + fakeMin);
                extrema[0][i] = fakeMin;
            }
        }

        if (extremaSteps != expectedExtremaSteps) {
            throw new RuntimeException("total extrema steps " + extremaSteps + "!=" + expectedExtremaSteps);
        }

        return extrema;
    }

    private void computeExtremaQuadtree(RenderedOp image, double[][] extrema, double[] px, int minX, int maxX,
                                        int minY, int maxY) throws IOException, CancellationException {
        checkCancelled();

        if (maxX < minX || maxY < minY) {
            return;
        }
        
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;

        if (loadAsTileWindowImage && w <= tileWindowTilesX && h <= tileWindowTilesY) {
            for (int ty = minY; ty <= maxY; ty++) {
                for (int tx = minX; tx <= maxX; tx++) {
                    checkCancelled();
                    accumulateExtrema(extrema, px, getTile(image, tx, ty));
                }
            }
            return;
        }

        if (minX == maxX && minY == maxY) {
            accumulateExtrema(extrema, px, getTile(image, minX, minY));
            return;
        }

        int hw = closestPowerOfTwo(w);
        int hh = closestPowerOfTwo(h);
        if (loadAsTileWindowImage && h <= tileWindowTilesY && hw <= tileWindowTilesX) {
            computeExtremaQuadtree(image, extrema, px, minX     , minX + hw - 1, minY     , maxY         ); //L
            computeExtremaQuadtree(image, extrema, px, minX + hw, maxX         , minY     , maxY         ); //R
        } else if (loadAsTileWindowImage && w <= tileWindowTilesX && hh <= tileWindowTilesY) {
            computeExtremaQuadtree(image, extrema, px, minX     , maxX         , minY     , minY + hh - 1); //T
            computeExtremaQuadtree(image, extrema, px, minX     , maxX         , minY + hh, maxY         ); //B
        } else {
            computeExtremaQuadtree(image, extrema, px, minX     , minX + hw - 1, minY     , minY + hh - 1); //TL
            computeExtremaQuadtree(image, extrema, px, minX + hw, maxX         , minY     , minY + hh - 1); //TR
            computeExtremaQuadtree(image, extrema, px, minX     , minX + hw - 1, minY + hh, maxY         ); //BL
            computeExtremaQuadtree(image, extrema, px, minX + hw, maxX         , minY + hh, maxY         ); //BR
        }
    }

    private void accumulateExtrema(double[][] extrema, double[] px, Raster tile) {
        int lx = tile.getMinX();
        int ux = lx + tile.getWidth() - 1;
        int ly = tile.getMinY();
        int uy = ly + tile.getHeight() - 1;
        for (int y = ly; y <= uy; y++) {
            for (int x = lx; x <= ux; x++) {
                tile.getPixel(x, y, px);
                for (int b = 0; b < px.length; b++) {
                    if (px[b] < extrema[0][b]) extrema[0][b] = px[b];
                    if (px[b] > extrema[1][b]) extrema[1][b] = px[b];
                }
            }
        }
        extremaSteps++;
    }

    private double[][] computeExtrema(Histogram histogram) {
        int bands = histogram.getNumBands();
        double[] min = new double[bands];
        double[] max = new double[bands];
        int[][] bins = histogram.getBins();
        for (int band = 0; band < bands; band++) {
            int numBins = histogram.getNumBins(band);
            for (int bin = 0; bin < numBins; bin++) {
                if (bins[band][bin] > 0) {
                    min[band] = histogram.getBinLowValue(band, bin);
                    break;
                }
            }
            for (int bin = numBins - 1; bin >= 0; bin--) {
                if (bins[band][bin] > 0) {
                    max[band] = histogram.getBinLowValue(band, bin);
                    break;
                }
            }
        }
        return new double[][] { min, max };
    }

    private float[] computePercentStretch(RenderedOp image, Histogram histogram,
                                          float lowPercent, float highPercent)  {
        String pfx = params.pfx;

        //based on code in marsviewer StretchManager.java
        //we are trying to preserve basically the same "percent stretch" algorithm as in that code
        //however some differences have been added over time

        //There are various dragonnes here.
        //
        //Mission images unfortunately often flagrantly conflate black with invalid so there are images where for
        //various reasons significant fractions of the image are pure black (e.g. a CRISP overlay image at high res can
        //be large but with only a small subregion containing valid pixels).
        //
        //Because of the black = invalid thing, for overlays we will later typically be assigning some transparency to
        //black pixels.  It shouldn't be possible for this code to end up stretching pixels that were black to not
        //black, but it could stretch not black pixels to black.
        //
        //For very sparse images which are mostly black (e.g. that CRISP overlay case) typical settings of small stretch
        //percents, without the while loop below, will sometimes end up basically binarizing the image.  The while loop
        //is there to try to work around that kind of situation.

        //it may seem odd that we'd arbitrarily pick band 0 to get the low and high vals
        //but the histogram is assumed to have been created with the same low/high limits across all bands
        //and the same number of bins across all bands
        //and further we assume those limits are the value limits for the image data
        //with the low value inclusive and the high value exclusive
        //and the number of bins for integer images equals the number of possible values
        //e.g. for a typical 12 bit integer image we expect lowVal=0, highVal=4096, numBins=4096
        float lowVal = (float)(histogram.getLowValue(0)); //inclusive
        float highVal = (float)(histogram.getHighValue(0)); //exclusive
        float quantum = ImageInfo.getQuantum(image);

        int numBins = histogram.getNumBins(0);

        if (numBins < 2) {
            if (debug) log.debug(pfx + "degenerate stretch, numBins=" + numBins);
            return new float[] { lowVal, highVal - quantum };
        }

        int numBands = histogram.getNumBands();

        int[][] bins = histogram.getBins();

        float binWidth = (highVal - lowVal) / numBins;

        float lowFrac = Math.max(Math.min(lowPercent, 100), 0) / 100.0f;
        float highFrac = Math.max(Math.min(highPercent, 100), 0) / 100.0f;

        //the number of nonzero pixels in each band should all be about the same...
        int numPixels = histogram.getTotals()[0];
        long[] numNonzeroPixels = new long[numBands];
        for (int j = 0; j < numBands; j++) {
            numNonzeroPixels[j] = numPixels - bins[j][0];
            if (debug) log.debug(pfx + "band " + j + ": " +
                                 numNonzeroPixels[j] + " nonzero pixels, " + bins[j][0] + " zero pixels");
        }

        while (true) {

            if (lowFrac == 0 && highFrac == 0) {
                if (debug) log.debug(pfx + "degenerate stretch, low=high=0%");
                return new float[] { lowVal, highVal - quantum };
            }

            long maxMinOutliers = 0, maxMaxOutliers = 0;
            long[] numMinOutliers = new long[numBands];
            long[] numMaxOutliers = new long[numBands];
            for (int j = 0; j < numBands; j++) {
                numMinOutliers[j] = (long)(lowFrac * numNonzeroPixels[j]);
                numMaxOutliers[j] = (long)(highFrac * numNonzeroPixels[j]);
                maxMinOutliers = Math.max(maxMinOutliers, numMinOutliers[j]);
                maxMaxOutliers = Math.max(maxMaxOutliers, numMaxOutliers[j]);
            }

            float newMin = lowVal;
            if (maxMinOutliers > 0) {
                int[] count = new int[numBands];
                for (int i = 1; i < numBins; ++i) { //skip bin 0
                    boolean done = false;
                    for (int j = 0; j < numBands; ++j) {
                        count[j] += bins[j][i];             
                        if (count[j] >= numMinOutliers[j]) {
                            done = true;
                            break;
                        }
                    }
                    if (done || i == (numBins - 1)) {
                        newMin = lowVal + (i * binWidth);
                        break;
                    }
                }
            }
            
            float newMax = highVal - quantum;
            if (maxMaxOutliers > 0) {
                int[] count = new int[numBands];
                for (int i = numBins - 1; i > 0; --i) { //skip bin 0
                    boolean done = false;
                    for (int j = 0; j < numBands; ++j) {
                        count[j] += bins[j][i];
                        if (count[j] >= numMaxOutliers[j]) {
                            done = true;
                            break;
                        }
                    }
                    if (done || i == 1) {
                        newMax = lowVal + ((i + 1) * binWidth) - quantum;
                        break;
                    }
                }
            }
            
            if (newMin == newMax) { //handle saturated (single color) images
                if (newMin >= quantum) {
                    newMin = Math.max(0, newMin - quantum); //max should be redundant but accounts for numerical error
                } else {
                    newMax += quantum;
                }
            }

            if (newMax > newMin) {
                return new float[] {newMin, newMax};
            } else {
                //try again with smaller outlier counts
                float newLowFrac = lowFrac > 0.0005f ? (lowFrac * 0.5f) : 0;
                float newHighFrac = highFrac > 0.0005f ? (highFrac * 0.5f) : 0;
                if (debug) log.debug(pfx + "degenerate stretch, reducing from " +
                                     "low=" + (lowFrac * 100) + "%, high=" + (highFrac * 100) + "% " +
                                     "to low=" + (newLowFrac * 100) + "%, high=" + (newHighFrac * 100) + "%");
                lowFrac = newLowFrac;
                highFrac = newHighFrac;
                adjStretchLowPercent = lowFrac * 100;
                adjStretchHighPercent = highFrac * 100;
            }
        }
    }

    private float[] computeRelativeStretch(boolean isFloat, double[][] extrema, float min, float max,
                                           float low, float high) {
        if (isFloat) {
            return new float[] { min, max };
        }

        float minDN = Float.POSITIVE_INFINITY;
        for (double val : extrema[0]) minDN = Math.min(minDN, (float)val);
        float maxDN = Float.NEGATIVE_INFINITY;
        for (double val : extrema[1]) maxDN = Math.max(maxDN, (float)val);

        return new float[] { low * minDN, high * maxDN + (1 - high) * max };
    }

    //adapted from jpl.mipl.jade.LessSimpleOverlayer
    private RenderedOp computeOverlay(RenderedOp image) {
        String pfx = params.pfx;
        try {

            var model = new MarsImageViewModel();
            ApplicationPropertyManager.set(model);
            model.setProperty(Constants.OPTION_USE_PREFERENCES, true);
            model.setProperty(Constants.OPTION_FILE_FINDER, new SimpleImageFileFinder()); //NOT fileFinder
            model.setProperty(Constants.OPTION_STRETCH_TYPE, Constants.STRETCH_NONE);

            var op = params.overlayPreferences;
            if (op != null && !op.isEmpty()) {
                for (String p : op) {
                    if (!PREF_PATTERN.matcher(p).matches()) {
                        throw new IllegalArgumentException("invalid overlay preference: " + p);
                    }
                }
                var ip = (ImagePreferences)(model.getProperty(Constants.OPTION_IMAGE_PREFERENCES));
                (new ImagePreferencesParseUtil()).filterAndProcessArguments((String[])(op.toArray(new String[0])), ip);
            }

            var content = RdrImageContentFactory.createImage(image, null, params.rdrType, params.rdrType, model);
            if (content == null) {
                throw new Exception("unknown error");
            }

            content.checkPreferences(); //this actually applies the preferences

            var bgImg = ConstantDescriptor.create(Float.valueOf(image.getWidth()), Float.valueOf(image.getHeight()),
                                                  new Byte[] { (byte)128 }, null);
            content.setSourceImage(bgImg);

            content.enableOverlayMode(true);

            var overlay = (RenderedOp)(content.getImage());

            if (debug) {
                log.debug(pfx + "created " + content.getClass().getName() + " overlay for RDR type " + params.rdrType);
                log.debug(pfx + "source bands: " + image.getNumBands() + ", overlay bands: " + overlay.getNumBands());
                log.debug(pfx + "overlay is " + ImageInfo.describeImage(overlay));
            }

            return overlay;

        } catch (Exception ex) {
            throw new RuntimeException("error computing " + params.rdrType + " overlay: " + ex.getMessage(), ex);
        }
    }

    private RenderedOp removeAlphaChannel(RenderedOp image, String what, int bands, int colorBands) {
        if (colorBands >= bands) {
            return image;
        }
        if (debug) log.debug(params.pfx + what + ": removing alpha channel");
        int[] indices = new int[colorBands];
        for (int i = 0; i < colorBands; i++) {
            indices[i] = i;
        }
        return BandSelectDescriptor.create(image, indices, null);
    }

    private RenderedOp removeAlphaChannel(RenderedOp image, String what) {
        return removeAlphaChannel(image, what, image.getNumBands(), ImageInfo.getNumColorComponents(image));
    }

    private RenderedOp computeMask(RenderedOp image) throws IOException {

        String pfx = params.pfx;
        
        if (params.unmaskedAlpha >= 1 && params.maskedAlpha >= 1) {
            if (debug) log.debug(pfx + "not creating mask: alpha disabled");
            return null;
        }

        List<float[]> maskVals = imageInfo.invalid;
        if (maskVals == null || maskVals.isEmpty()) {
            if (debug) log.debug(pfx + "not creating mask: no mask values");
            return null;
        }

        boolean maskBlack = maskVals.stream().anyMatch((mv) -> Utils.doubleStream(mv).allMatch((v) -> v == 0));
        boolean maskNonBlack = maskVals.stream().anyMatch((mv) -> Utils.doubleStream(mv).anyMatch((v) -> v != 0));

        if (debug) log.debug(pfx + "creating mask: maskBlack=" + maskBlack + ", maskNonBlack=" + maskNonBlack);

        int bands = image.getNumBands();
        int colorBands = ImageInfo.getNumColorComponents(image);

        image = removeAlphaChannel(image, "creating mask", bands, colorBands);

        double[] black = new double[colorBands];
        Arrays.fill(black, 0);

        if (!maskBlack) { //rewrite black pixels to not black (uncommon)
            float[] nbf = new float[colorBands];
            Arrays.fill(nbf, 1);
            while (maskVals.stream().anyMatch((mv) -> Arrays.equals(mv, nbf))) {
                nbf[0] += 1;
            }
            double[] notBlack = Utils.doubleStream(nbf).toArray();
            if (debug) log.debug(pfx + "creating mask: rewriting black to " + Arrays.toString(notBlack));
            image = ThresholdDescriptor.create(image, black, black, notBlack, null); //black -> notBlack
        }

        if (maskNonBlack) { //rewrite pixels with non-black mask values to black (uncommon)
            for (float[] maskVal : maskVals) {
                if (Utils.doubleStream(maskVal).anyMatch((v) -> v != 0)) { //nonblack mask value
                    double[] dmv = Utils.doubleStream(maskVal).toArray();
                    if (debug) log.debug(pfx + "creating mask: rewriting " + Arrays.toString(dmv) + " to black");
                    image = ThresholdDescriptor.create(image, dmv, dmv, black, null); //maskVal -> black
                }
            }
        }

        //convert to 8 bit per band unsigned

        if (ImageInfo.isSigned(image)) {
            if (debug) log.debug(pfx + "creating mask: discarding sign");
            image = AbsoluteDescriptor.create(image, null);
        }

        if (ImageInfo.isFloat(image)) {
            if (debug) log.debug(pfx + "creating mask: scaling float from [0, 1] to [0, 255]");
            image = MultiplyConstDescriptor.create(image, new double[] { 255 }, null);
        }

        if (!ImageInfo.is8Bit(image)) {
            if (debug) log.debug(pfx + "creating mask: converting to 8 bit");
            image = FormatDescriptor.create(image, DataBuffer.TYPE_BYTE, null);
        }

        if (colorBands > 1) { //sum all color bands

            if (debug) log.debug(pfx + "creating mask: summing " + colorBands + " bands");

            var layout = new ImageLayout(image);
            layout.setColorModel(new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
                                                         false, false, //hasAlpha, isAlphaPremultiplied
                                                         Transparency.OPAQUE, DataBuffer.TYPE_BYTE));
            layout.setSampleModel(new BandedSampleModel(DataBuffer.TYPE_BYTE, image.getWidth(), image.getHeight(), 1));
            var hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, layout);
            
            double[][] matrix = new double[1][colorBands + 1];
            for (int i = 0; i < colorBands; i++) {
                matrix[0][i] = 1;
            }

            matrix[0][colorBands] = 0;

            image = BandCombineDescriptor.create(image, matrix, hints); //overflows will be clamped
        }

        //image = BinarizeDescriptor.create(image, 1, null); //1 byte per pixel binary image: 0 = masked, 1 = unmasked

        if (debug && (!randomReadsAreExpensive || tuningHacks)) {
            try {
                int[][] bins = computeHistogram(image, 255, 0, 256).getBins();
                int total = image.getWidth() * image.getHeight();
                log.debug(pfx + "created mask: " + Utils.kmg(bins[0][0]) + "/" + Utils.kmg(total) + " masked pixels");
            } catch (CancellationException ex) {
                Thread.currentThread().interrupt(); //reset flag
                log.warn(pfx + "interrupted counting masked pixels");
            }
        }

        return image; //1 byte per pixel binary image: 0 = masked, nonzero = unmasked
    }

    private BufferedImage loadCachedTile(int level, int col, int row) throws IOException {
        try (InputStream is = archiver.getCachedTile(level, col, row)) {
            return is != null ? ImageIO.read(is) : null;
        }
    }

    private BufferedImage computeThumbnail(int rdrWidth, int rdrHeight) throws IOException {

        String pfx = params.pfx;

        float aspect = ((float)rdrWidth) / rdrHeight;

        int thumbHeight = Math.max(1, Math.min(params.thumbHeight, rdrHeight));
        int thumbWidth = Math.max(1, (int)Math.round(thumbHeight * aspect));
        
        int srcLevel = 0; //highest level that has a single tile
        while (archiver.getNumCachedTilesAtLevel(srcLevel + 1) == 1) {
            srcLevel++;
        }
        var srcTile = loadCachedTile(srcLevel, 0, 0);
        int ns = 1;

        if ((srcTile.getHeight() < thumbHeight || srcTile.getWidth() < thumbWidth) &&
            archiver.getNumCachedTilesAtLevel(srcLevel + 1) > 1) {

            //root tile was too small, try its children

            srcLevel++;
            
            var ul = loadCachedTile(srcLevel, 0, 0);
            var ur = loadCachedTile(srcLevel, 1, 0);
            var ll = loadCachedTile(srcLevel, 0, 1);
            var lr = loadCachedTile(srcLevel, 1, 1);
            
            int w = ul.getWidth() + (ur == null ? 0 : ur.getWidth() - 2 * params.tileOverlap);
            int h = ul.getHeight() + (ll == null ? 0 : ll.getHeight() - 2 * params.tileOverlap);

            int rtx = params.tileSize - params.tileOverlap;
            int bty = params.tileSize - params.tileOverlap;
            
            srcTile = new BufferedImage(ul.getColorModel(), ul.getRaster().createCompatibleWritableRaster(w, h),
                                        ul.isAlphaPremultiplied(), null);
            
            var raster = srcTile.getRaster();
            raster.setRect(0, 0, ul.getRaster());
            if (ur != null) {
                raster.setRect(rtx, 0, ur.getRaster());
                ns++;
            }
            if (ll != null) {
                raster.setRect(0, bty, ll.getRaster());
                ns++;
            }
            if (lr != null) {
                raster.setRect(rtx, bty, lr.getRaster());
                ns++;
            }
        }

        aspect = ((float)srcTile.getWidth()) / srcTile.getHeight();

        thumbHeight = Math.max(1, Math.min(Math.min(params.thumbHeight, srcTile.getHeight()), rdrHeight));
        thumbWidth = Math.max(1, (int)Math.round(thumbHeight * aspect));

        int maxWidth = Math.min(srcTile.getWidth(), rdrWidth);
        if (thumbWidth > maxWidth) {
            thumbWidth = maxWidth;
            thumbHeight = (int)(thumbWidth / aspect);
        }

        if (debug) {
            log.debug(pfx + "creating " + thumbWidth + "x" + thumbHeight + " thumb for " +
                      rdrWidth + "x" + rdrHeight + " RDR from " + ns + " tile(s) at level " + srcLevel +
                      " (" + srcTile.getWidth() + "x" + srcTile.getHeight() + ")");
        }

        return Thumbnails.of(srcTile).size(thumbWidth, thumbHeight).asBufferedImage();
    }
}
