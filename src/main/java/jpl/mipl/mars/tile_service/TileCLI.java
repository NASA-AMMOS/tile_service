package jpl.mipl.mars.tile_service;

import java.io.File;
import java.io.StringReader;
import java.util.Map;
import java.util.HashMap;

import javax.json.Json;
import javax.json.JsonObject;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PatternOptionBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Marsette Vona
 */
public class TileCLI {

    public static final String DEF_READ_CHUNK = "25%";
    public static final String DEF_LARGE_IMAGE_THRESHOLD = "50%";

    private static final Logger log = LoggerFactory.getLogger(TileCLI.class);

    private boolean fsCache;
    private boolean inputIsS3, inputIsHTTP;
    private S3Helper s3;
    private String cacheBucket;
    private String cacheLoc;
    private boolean abortOnError;
    private boolean printCacheDir;

    public static void main(String[] args) {
        try {
            (new TileCLI()).run(args);
        } catch (Exception ex) {
            System.err.println(ex.toString());
            System.exit(1);
        }
    }

    private void run(String[] args) {

        Options options = new Options();

        Option inputOpt = new Option("i", "input", true, "s3:// folder URL, single file URL, or local file path");
        options.addOption(inputOpt);

        Option rdrTypeOpt = new Option("t", "rdr-type", true, 
                                       "Input RDR type (absent or \"auto\" to determine automatically)");
        options.addOption(rdrTypeOpt);
        
        Option fsCacheOpt = new Option("fc", "filesystem-cache", false,
                                       "Write output to cache location on the local filesystem instead of S3");
        options.addOption(fsCacheOpt);

        Option cacheLocOpt = new Option("cl", "cache-location", true, "Cache location, local or S3 folder");
        options.addOption(cacheLocOpt);

        Option cacheBucketOpt = new Option("cb", "cache-bucket", true, "S3 bucket if not using filesystem cache");
        options.addOption(cacheBucketOpt);

        Option tileFormatOpt = new Option("tf", "tile-format", true, "Tile format, default " +
                                          DziParams.DEF_TILE_FORMAT);
        options.addOption(tileFormatOpt);

        Option tileSizeOpt = new Option("ts", "tile-size", true,
                                        "Leaf tile size pixels, default " + DziParams.DEF_TILE_SIZE);
        tileSizeOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(tileSizeOpt);

        Option tileOverlapOpt = new Option("to", "tile-overlap", true,
                                           "Tile overlap pixels, default " + DziParams.DEF_TILE_OVERLAP);
        tileOverlapOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(tileOverlapOpt);

        Option thumbHeightOpt = new Option("th", "thumb-height", true,
                                           "Thumb height pixels, default " + DziParams.DEF_THUMB_HEIGHT);
        thumbHeightOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(thumbHeightOpt);

        Option stretchTypeOpt = new Option("st", "stretch-type", true,
                                           "Stretch type, percent/extrema/relative/none, default " +
                                           DziParams.DEF_STRETCH_TYPE);
        options.addOption(stretchTypeOpt);
        
        Option stretchLowOpt = new Option("sl", "stretch-low", true, "Stretch low, default " +
                                          DziParams.DEF_PERCENT_STRETCH_LOW + " for percent stretch");
        stretchLowOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(stretchLowOpt);

        Option stretchHighOpt = new Option("sh", "stretch-high", true, "Stretch high, default " +
                                           DziParams.DEF_PERCENT_STRETCH_HIGH + " for percent stretch");
        stretchHighOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(stretchHighOpt);

        Option overlayPreferencesOpt = new Option("op", "overlay-preferenes", true, "Overlay prefereces, " +
                                                  "one or more, whitespace separated, formatted like TYPE:NAME=VALUE");
        overlayPreferencesOpt.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(overlayPreferencesOpt);

        Option maskBlackOpt = new Option("mb", "mask-black", true, "Mask black pixels, true/false/auto, default auto");
        options.addOption(maskBlackOpt);

        Option overlayAlphaOpt = new Option("oa", "overlay-alpha", true, "Unmasked alpha for overlays, default " +
                                            DziParams.DEF_OVERLAY_ALPHA);
        overlayAlphaOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(overlayAlphaOpt);

        Option unmaskedAlphaOpt = new Option("ua", "unmasked-alpha", true,
                                             "Unmasked alpha, defaults to overlay alpha for overlay types, else 1");
        unmaskedAlphaOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(unmaskedAlphaOpt);

        Option maskedAlphaOpt = new Option("ma", "masked-alpha", true,
                                           "Masked alpha, default 0 for overlay types and CRISP images, else 1");
        maskedAlphaOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(maskedAlphaOpt);

        Option gammaModeOpt = new Option("gm", "gamma-mode", true,
                                         "Gamma mode, none/passthrough/autoconvert/lineartosrgb/srgbtolinear, " +
                                         "default " + DziParams.DEF_GAMMA_MODE);
        options.addOption(gammaModeOpt);

        Option dziParallelismOpt = new Option("dzip", "dzi-parallelism", true, "Number of threads when building DZI, " +
                                              "default " + DziTask.DEF_DZI_PARALLELISM);
        dziParallelismOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(dziParallelismOpt);

        Option readChunkOpt = new Option("rc", "read-chunk", true,
                                         "Fraction of source image to copy at a time when building DZI, " +
                                         "positive fraction or bytes with optional k/m/g/% suffix, default " +
                                         DEF_READ_CHUNK);
        options.addOption(readChunkOpt);

        Option memoryBudgetOpt = new Option("mu", "memory-budget", true,
                                            "Total memory budget, optional k/m/g/% suffix or auto, default auto");
        options.addOption(memoryBudgetOpt);

        Option diskBudgetOpt = new Option("db", "disk-budget", true,
                                          "Total disk budget, optional k/m/g/% suffix or auto, default auto");
        options.addOption(diskBudgetOpt);

        Option largeFileThresholdOpt = new Option("lft", "large-file-threshold", true,
                                                  "Large file threshold, optional k/m/g/% suffix or auto, default " +
                                                  DziTask.DEF_LARGE_FILE_THRESHOLD);
        options.addOption(largeFileThresholdOpt);

        Option largeImageThresholdOpt = new Option("lit", "large-image-threshold", true,
                                                  "Large image threshold, optional k/m/g/% suffix or auto, " +
                                                   "default " + DEF_LARGE_IMAGE_THRESHOLD);
        options.addOption(largeImageThresholdOpt);

        Option limitPDSHeightOpt = new Option("lph", "lmit-pds-height", false,
                                              "limit IMG/VIC rows to fit source image in 2G pixels");
        options.addOption(limitPDSHeightOpt);

        Option limitNonPDSHeightOpt = new Option("lnph", "lmit-non-pds-height", false,
                                                 "limit non-IMG/VIC rows to fit source image in 2G bytes");
        options.addOption(limitNonPDSHeightOpt);

        Option fullLoadSmallPDSOpt = new Option("flsp", "full-load-small-pds", false, "load small VIC/IMG fully");
        options.addOption(fullLoadSmallPDSOpt);

        Option tileWindowLargeNonPDSOpt = new Option("twlnp", "tile-window-large-non-pds", false,
                                                     "load large non-IMG/VIC in tile window chunks");
        options.addOption(tileWindowLargeNonPDSOpt);

        Option tileWindowChunkOpt = new Option("twc", "tile-window-chunk", true,
                                               "Fraction of source image when loading as tile window " +
                                               "positive fraction or bytes with optional k/m/g/% suffix, default " +
                                               DziTask.DEF_TILE_WINDOW_CHUNK);
        options.addOption(tileWindowChunkOpt);

        Option maxHistogramBinsOpt = new Option("mhb", "max-histogram-bins", true,
                                                "Max number of histogram bins, default " +
                                                DziTask.DEF_MAX_HISTOGRAM_BINS);
        maxHistogramBinsOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(maxHistogramBinsOpt);

        Option histogramModeOpt = new Option("hm", "histogram-mode", true,
                                             "histogram mode, one of jai, reverseRaster, reverseQuadtree, default " +
                                             DziTask.DEF_HISTOGRAM_MODE);
        options.addOption(histogramModeOpt);
                                             
        Option neverEstimateExtremaFromHistogramOpt = new Option("nee",
                                                                 "never-estimate-extrema-from-histogram", false,
                                                                 "never estimate extrema from histogram");
        options.addOption(neverEstimateExtremaFromHistogramOpt);

        Option alwaysEstimateExtremaFromHistogramOpt = new Option("aee",
                                                                  "always-estimate-extrema-from-histogram", false,
                                                                  "estimate extrema from histogram " +
                                                                  "even when random reads are not expensive");
        options.addOption(alwaysEstimateExtremaFromHistogramOpt);

        Option jaiCacheOpt = new Option("jc", "jai-cache", true, "JAI cache capacity, optional k/m/g/% suffix, " +
                                        "default " + DziTask.DEF_JAI_CACHE_CAPACITY);
        options.addOption(jaiCacheOpt);

        Option pdsTileWidthOpt = new Option("ptw", "pds-tile-width", true,
                                            "large IMG/VIC tile width, non-positive for auto, " +
                                            "default " + DziTask.DEF_PDS_TILE_WIDTH);
        options.addOption(pdsTileWidthOpt);

        Option pdsTileHeightOpt = new Option("pth", "pds-tile-height", true,
                                             "large IMG/VIC tile height, non-positive for auto," +
                                            " default " + DziTask.DEF_PDS_TILE_HEIGHT);
        options.addOption(pdsTileHeightOpt);

        Option maxRDRBytesOpt = new Option("mrb", "max-rdr-bytes", true, "Max RDR filesize, optional k/m/g/% " +
                                           "suffix, unlimited if non-positive, default unlimited");
        options.addOption(maxRDRBytesOpt);

        Option maxImageBytesOpt = new Option("mib", "max-image-bytes", true,
                                             "Max decompressed RDR image bytes, optional k/m/g/% suffix, " +
                                             "unlimited if non-positive, default unlimited");
        options.addOption(maxImageBytesOpt);

        Option lruPageBytesOpt = new Option("pb", "lru-page-bytes", true, "LRU cache page bytes, optional k/m/g " +
                                            "suffix, default " + DziTask.DEF_LRU_PAGE_BYTES);
        options.addOption(lruPageBytesOpt);

        Option lruMemCachePagesOpt = new Option("mcp", "lru-mem-cache-pages", true,
                                                "LRU mem cache pages, optional % suffix, default 100%");
        options.addOption(lruMemCachePagesOpt);

        Option lruDiskCachePagesOpt = new Option("dcp", "lru-disk-cache-pages", true,
                                                 "LRU disk cache pages, default " + DziTask.DEF_LRU_DISK_CACHE_PAGES);
        lruDiskCachePagesOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(lruDiskCachePagesOpt);

        Option lruDiskCacheDirOpt = new Option("dcd", "lru-disk-cache-dir", true,
                                               "LRU disk cache dir (default " + DziTask.DEF_LRU_DISK_CACHE_DIR + ")");
        options.addOption(lruDiskCacheDirOpt);

        Option ignoreSubdirsOpt = new Option("id", "ignore-subdirs", true,
                                             "Comma separated list of subdirectories to ignore (default empty)");
        options.addOption(ignoreSubdirsOpt);

        Option ignoreExtsOpt = new Option("ie", "ignore-exts", true,
                                          "Comma separated list of extensions to ignore (default empty)");
        options.addOption(ignoreExtsOpt);

        Option ignoreExceptionsOpt = new Option("ix", "ignore-exceptions", true,
                                                "Comma separated list of ignore exceptions (default empty)");
        options.addOption(ignoreExceptionsOpt);

        Option profileOpt = new Option("ap", "aws-profile", true, "Override AWS profile (default env var AWS_PROFILE)");
        options.addOption(profileOpt);

        Option regionOpt = new Option("ar", "aws-region", true, 
                                      "Override AWS region (default env var AWS_DEFAULT_REGION)");
        options.addOption(regionOpt);

        Option awsHeaderOption = new Option("xaws", "x-aws", true,
                                            "use AWS credentials from base64 encoded X-AWS header string, " +
                                            "mutually exclusive with --aws-profile and --aws-region");
        options.addOption(awsHeaderOption);

        Option missionOpt = new Option("m", "mission", true,
                                       "mission, one of " + String.join(",", DziParams.SUPPORTED_MISSIONS) +
                                       ", defaults to " + DziParams.DEFAULT_MISSION);
        options.addOption(missionOpt);

        Option zombieTimeOpt = new Option("zt", "zombie-time", true, "task zombie time or negative for auto, default " +
                                          DziTask.DEF_MAX_ZOMBIE_TIME);
        options.addOption(zombieTimeOpt);

        Option taskInterlockTimeOpt = new Option("it", "interlock-time", true,
                                                 "task interlock time or negative for auto, default " +
                                                 DziTask.DEF_TASK_INTERLOCK_TIME);
        options.addOption(taskInterlockTimeOpt);

        Option forceOpt = new Option("f", "force", false,
                                     "force processing even if other tasks for same product appear to be running");
        options.addOption(forceOpt);

        Option skipCachedOpt = new Option("sk", "skip-cached", false,
                                          "don't recompute if an existing tiling is already cached");
        options.addOption(skipCachedOpt);

        Option helpOpt = new Option("h", "help", false, "Display this help message and exit");
        options.addOption(helpOpt);

        Option debugOpt = new Option("d", "debug", false, "Debug mode");
        options.addOption(debugOpt);
        
        Option verboseOpt = new Option("vv", "verbose", false, "Verbose debug mode");
        options.addOption(verboseOpt);

        Option versionOpt = new Option("v", "version", false, "Show version");
        options.addOption(versionOpt);

        Option requestGCOpt = new Option("gc", "request-gc", false, "Request garbage collection before heap stats");
        options.addOption(requestGCOpt);

        Option tuningHacksOpt = new Option("uh", "tuning-hacks", false, "Turn on tuning hacks");
        options.addOption(tuningHacksOpt);

        Option doMonolithicOpt = new Option("dm", "do-monolithic", false, "Make monolithic image");
        options.addOption(doMonolithicOpt);

        Option noDZIOpt = new Option("ndzi", "no-dzi", false, "Skip DZI output");
        options.addOption(noDZIOpt);

        Option quietOpt = new Option("q", "quiet", false, "Quiet mode");
        options.addOption(quietOpt);

        Option abortOnErrorOpt = new Option("ae", "abort-on-error", false,
                                            "Abort processing other inputs if one fails");
        options.addOption(abortOnErrorOpt);

        Option printCacheDirOpt = new Option("pc", "print-cache-dir", false,
                                             "Just print cache directory (path or s3 URL)");
        options.addOption(printCacheDirOpt);

        Option deleteCacheOpt = new Option("dlc", "delete-cache", false, "Delete S3 cache at input URL");
        options.addOption(deleteCacheOpt);

        Option deleteCacheDryRunOpt = new Option("dlcd", "delete-cache-dry-run", false,
                                                 "Delete S3 cache at input URL (dry run)");
        options.addOption(deleteCacheDryRunOpt);

        Option testPRNGOpt = new Option("tprng", "test-prng", false, "Test pseudorandom number generator");
        options.addOption(testPRNGOpt);
        
        Option testS3Opt = new Option("ts3", "test-s3", true, "Test S3 (S3 URL to repeatedly get metadata)");
        options.addOption(testS3Opt);

        Option testThreadsOpt = new Option("tt", "test-threads", true, "Number of test threads for --tprng and --ts3" +
                                           ", default " + StressTests.DEF_TEST_THREADS);
        testThreadsOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(testThreadsOpt);

        Option testMercyMSOpt = new Option("tm", "test-mercy", true, "MS to delay between tests for --tprng and --ts3");
        testMercyMSOpt.setType(PatternOptionBuilder.NUMBER_VALUE);
        options.addOption(testMercyMSOpt);

        try {

            String pfx = DziTask.pfx;

            CommandLine cl = (new DefaultParser()).parse(options, args);

            boolean quiet = false;
            if (cl.hasOption(quietOpt.getOpt())) {
                Utils.setQuiet(log);
                Utils.setQuiet(DziTask.log);
                DziTask.enableProgress = false;
                quiet = true;
            } else if (cl.hasOption(verboseOpt.getOpt()) || cl.hasOption(debugOpt.getOpt())) {
                Utils.setLogLevel(log, "DEBUG");
                Utils.spewJVM(log, pfx);
                log.debug(pfx + "CLI args: " + String.join(" ", args));
            }

            if (cl.hasOption(helpOpt.getOpt())) {
                (new HelpFormatter()).printHelp("TileCLI", options);
                return;
            }

            if (cl.hasOption(versionOpt.getOpt())) {
                System.out.println("TileCLI " + Utils.getVersion(TileCLI.class));
                return;
            }

            if (cl.hasOption(testPRNGOpt.getOpt())) {
                int threads = Utils.parseInt(cl.getOptionValue(testThreadsOpt.getOpt()), StressTests.DEF_TEST_THREADS);
                int mercyMS =
                    Utils.parseInt(cl.getOptionValue(testMercyMSOpt.getOpt()), StressTests.DEF_TEST_PRNG_MERCY_MS);
                StressTests.testPRNG(threads, mercyMS, log);
                System.exit(0);
            }

            if (cl.hasOption(testS3Opt.getOpt())) {
                int threads = Utils.parseInt(cl.getOptionValue(testThreadsOpt.getOpt()), StressTests.DEF_TEST_THREADS);
                int mercyMS =
                    Utils.parseInt(cl.getOptionValue(testMercyMSOpt.getOpt()), StressTests.DEF_TEST_S3_MERCY_MS);
                String url = cl.getOptionValue(testS3Opt.getOpt());
                String awsProfile = cl.getOptionValue(profileOpt.getOpt());
                String awsRegion = cl.getOptionValue(regionOpt.getOpt());
                StressTests.testS3(threads, mercyMS, url, awsProfile, awsRegion, log);
                System.exit(0);
            }

            if (!cl.hasOption(inputOpt.getOpt())) {
                throw new IllegalArgumentException("input path must be specified with -i");
            }

            var dziOverrides = new HashMap<String, String>();
            if (!quiet && cl.hasOption(verboseOpt.getOpt())) dziOverrides.put("VERBOSE_TILER", "true");
            if (!quiet && cl.hasOption(debugOpt.getOpt())) dziOverrides.put("DEBUG_TILER", "true");
            if (cl.hasOption(tuningHacksOpt.getOpt())) dziOverrides.put("TUNING_HACKS", "true");
            if (cl.hasOption(requestGCOpt.getOpt())) dziOverrides.put("REQUEST_GC", "true");
            dziOverrides.put("MISSION", cl.getOptionValue(missionOpt.getOpt()));
            dziOverrides.put("IGNORE_SUBDIRS", cl.getOptionValue(ignoreSubdirsOpt.getOpt()));
            dziOverrides.put("IGNORE_EXTS", cl.getOptionValue(ignoreExtsOpt.getOpt()));
            dziOverrides.put("IGNORE_EXCEPTIONS", cl.getOptionValue(ignoreExceptionsOpt.getOpt()));
            dziOverrides.put("MAX_RDR_BYTES", cl.getOptionValue(maxRDRBytesOpt.getOpt()));
            dziOverrides.put("MAX_IMAGE_BYTES", cl.getOptionValue(maxImageBytesOpt.getOpt()));
            dziOverrides.put("DZI_PARALLELISM", cl.getOptionValue(dziParallelismOpt.getOpt()));
            dziOverrides.put("READ_CHUNK",
                             cl.hasOption(readChunkOpt.getOpt()) ?
                             cl.getOptionValue(readChunkOpt.getOpt()) : DEF_READ_CHUNK);
            dziOverrides.put("MEM_CACHE_TILES", "false"); //never makes sense to mem cache tiles for CLI
            dziOverrides.put("LRU_DISK_CACHE_DIR", cl.getOptionValue(lruDiskCacheDirOpt.getOpt()));
            dziOverrides.put("MEMORY_BUDGET", cl.getOptionValue(memoryBudgetOpt.getOpt()));
            dziOverrides.put("DISK_BUDGET", cl.getOptionValue(diskBudgetOpt.getOpt()));
            dziOverrides.put("LARGE_FILE_THRESHOLD", cl.getOptionValue(largeFileThresholdOpt.getOpt()));
            dziOverrides.put("LARGE_IMAGE_THRESHOLD",
                             cl.hasOption(largeImageThresholdOpt.getOpt()) ?
                             cl.getOptionValue(largeImageThresholdOpt.getOpt()) : DEF_LARGE_IMAGE_THRESHOLD);
            if (cl.hasOption(limitPDSHeightOpt.getOpt())) dziOverrides.put("LIMIT_PDS_HEIGHT", "true");
            if (cl.hasOption(limitNonPDSHeightOpt.getOpt())) dziOverrides.put("LIMIT_NON_PDS_HEIGHT", "true");
            if (cl.hasOption(fullLoadSmallPDSOpt.getOpt())) dziOverrides.put("FULL_LOAD_SMALL_PDS", "true");
            if (cl.hasOption(tileWindowLargeNonPDSOpt.getOpt())) dziOverrides.put("TILE_WINDOW_LARGE_NON_PDS", "true");
            dziOverrides.put("TILE_WINDOW_CHUNK", cl.getOptionValue(tileWindowChunkOpt.getOpt()));
            dziOverrides.put("MAX_HISTOGRAM_BINS", cl.getOptionValue(maxHistogramBinsOpt.getOpt()));
            dziOverrides.put("HISTOGRAM_MODE", cl.getOptionValue(histogramModeOpt.getOpt()));
            if (cl.hasOption(neverEstimateExtremaFromHistogramOpt.getOpt()))
                dziOverrides.put("NEVER_ESTIMATE_EXTREMA_FROM_HISTOGRAM", "true");
            if (cl.hasOption(alwaysEstimateExtremaFromHistogramOpt.getOpt()))
                dziOverrides.put("ALWAYS_ESTIMATE_EXTREMA_FROM_HISTOGRAM", "true");
            dziOverrides.put("JAI_CACHE", cl.getOptionValue(jaiCacheOpt.getOpt()));
            dziOverrides.put("PDS_TILE_WIDTH", cl.getOptionValue(pdsTileWidthOpt.getOpt()));
            dziOverrides.put("PDS_TILE_HEIGHT", cl.getOptionValue(pdsTileHeightOpt.getOpt()));
            dziOverrides.put("LRU_PAGE_BYTES", cl.getOptionValue(lruPageBytesOpt.getOpt()));
            dziOverrides.put("LRU_MEM_CACHE_PAGES", cl.getOptionValue(lruMemCachePagesOpt.getOpt()));
            dziOverrides.put("LRU_DISK_CACHE_PAGES", cl.getOptionValue(lruDiskCachePagesOpt.getOpt()));
            dziOverrides.put("MAX_ZOMBIE_TIME", cl.getOptionValue(zombieTimeOpt.getOpt()));
            dziOverrides.put("TASK_INTERLOCK_TIME", cl.getOptionValue(taskInterlockTimeOpt.getOpt()));

            var env = System.getenv();
            DziTask.setConfig(name -> env.get(name.toUpperCase()), dziOverrides);

            DziParams.setDefaults((name) -> env.get(name.toUpperCase()));

            Map<String, Object> dziArgs = new HashMap<String, Object>();
            dziArgs.put("rdr_type", cl.getOptionValue(rdrTypeOpt.getOpt()));
            dziArgs.put("tile_format", cl.getOptionValue(tileFormatOpt.getOpt()));
            dziArgs.put("tile_size", (Number)cl.getParsedOptionValue(tileSizeOpt.getOpt()));
            dziArgs.put("tile_overlap", (Number)cl.getParsedOptionValue(tileOverlapOpt.getOpt()));
            dziArgs.put("thumb_height", (Number)cl.getParsedOptionValue(thumbHeightOpt.getOpt()));
            dziArgs.put("stretch_type", cl.getOptionValue(stretchTypeOpt.getOpt())); 
            dziArgs.put("stretch_low", (Number)cl.getParsedOptionValue(stretchLowOpt.getOpt()));
            dziArgs.put("stretch_high", (Number)cl.getParsedOptionValue(stretchHighOpt.getOpt()));
            dziArgs.put("mask_black", cl.getOptionValue(maskBlackOpt.getOpt()));
            dziArgs.put("overlay_alpha", (Number)cl.getParsedOptionValue(overlayAlphaOpt.getOpt())); 
            dziArgs.put("unmasked_alpha", (Number)cl.getParsedOptionValue(unmaskedAlphaOpt.getOpt())); 
            dziArgs.put("masked_alpha", (Number)cl.getParsedOptionValue(maskedAlphaOpt.getOpt())); 
            dziArgs.put("gamma_mode", cl.getOptionValue(gammaModeOpt.getOpt())); 
            if (cl.hasOption(forceOpt.getOpt())) dziArgs.put("force", true);
            if (cl.hasOption(skipCachedOpt.getOpt())) dziArgs.put("skip_cached", true);
            if (cl.hasOption(doMonolithicOpt.getOpt())) dziArgs.put("do_monolithic", true);
            if (cl.hasOption(noDZIOpt.getOpt())) dziArgs.put("no_dzi", true);
            if (cl.hasOption(overlayPreferencesOpt.getOpt())) {
                dziArgs.put("overlay_preferences", cl.getOptionValues(overlayPreferencesOpt.getOpt()));
            }

            String input = cl.getOptionValue(inputOpt.getOpt());
            inputIsS3 = S3Helper.isS3Url(input); //check first to catch http[s]://BUCKET.s3-REGION.amazonaws.com/KEY
            inputIsHTTP = !inputIsS3 && HTTPHelper.isHTTPUrl(input);
            if (!inputIsS3 && !inputIsHTTP) {
                input = input.replace("/", File.separator);
            }
            if (DziTask.debug) {
                log.debug(pfx + (inputIsS3 ? "S3" : inputIsHTTP ? "HTTP(S)" : "filesystem") + " input: " + input);
            }
            
            fsCache = cl.hasOption(fsCacheOpt.getOpt());

            cacheLoc = Utils.parseString(cl.getOptionValue(cacheLocOpt.getOpt()), env.get("CACHE_LOC")); //null ok
            cacheLoc = Utils.ensureSeparators(cacheLoc, fsCache ? File.separator : "/");
            
            if (fsCache) {
                cacheBucket =  null;
                if (DziTask.debug) log.debug(pfx + "filesystem output: " + (cacheLoc.isEmpty() ? "." : cacheLoc));
            } else {
                cacheBucket =
                    Utils.parseString(cl.getOptionValue(cacheBucketOpt.getOpt()), env.get("CACHE_BUCKET_NAME"));
                if (cacheBucket == null) {
                    throw new IllegalArgumentException("missing output cache bucket");
                }
                if (DziTask.debug) log.debug(pfx + "S3 output: s3://" + cacheBucket + "/" + cacheLoc);
            }

            if (inputIsS3 || !fsCache) {
                boolean useAwsHeader = cl.hasOption(awsHeaderOption.getOpt());
                if (useAwsHeader) {
                    String hdr = cl.getOptionValue(awsHeaderOption.getOpt());
                    String headerJsonStr = new String(Base64.decodeBase64(hdr), "UTF-8");
                    JsonObject awsSessionConfig = Json.createReader(new StringReader(headerJsonStr)).readObject();
                    s3 = new S3Helper(awsSessionConfig);
                } else {
                    String awsProfile = cl.getOptionValue(profileOpt.getOpt());
                    String awsRegion = cl.getOptionValue(regionOpt.getOpt());
                    s3 = new S3Helper(awsProfile, awsRegion); //null ok
                }
                if (DziTask.debug) {
                    log.debug(pfx + (useAwsHeader ? "using X-AWS header" : ("AWS profile: " + s3.getAWSProfile())) +
                              ", region: " + s3.getAWSRegion());
                }
            }

            boolean deleteCache = cl.hasOption(deleteCacheOpt.getOpt());
            boolean deleteCacheDryRun = cl.hasOption(deleteCacheDryRunOpt.getOpt());

            abortOnError = cl.hasOption(abortOnErrorOpt.getOpt());

            printCacheDir = cl.hasOption(printCacheDirOpt.getOpt());

            if (deleteCache || deleteCacheDryRun) {
                if (!inputIsS3) {
                    throw new IllegalArgumentException("error deleting cache: input is not an S3 URL");
                }
                AmazonS3URI s3Uri = new AmazonS3URI(input);
                if (!s3Uri.getBucket().equals(cacheBucket)) {
                    throw new IllegalArgumentException("error deleting cache: input bucket " + s3Uri.getBucket() +
                                                       " is not cache bucket " + cacheBucket);
                }
                s3.deleteObjectsRecursive(s3Uri.getBucket(), s3Uri.getKey(), log, deleteCacheDryRun);
            } else if (inputIsS3) {
                AmazonS3URI s3Uri = new AmazonS3URI(input);
                for (var obj : s3.listObjects(input)) {
                    process("s3://" + s3Uri.getBucket() + "/" + obj.key(), dziArgs);
                }
            } else {
                DziTask.allowDotDotInput = true;
                process(input, dziArgs);
            }

        } catch (Exception ex) {
            log.error("TileCLI error", ex);
            System.exit(1);
        }
    }

    private void process(String rdrUrl, Map<String, Object> dziArgs) {
        String pfx = DziTask.pfx + "[" + rdrUrl + "] ";
        try {

            var params = new DziParams(rdrUrl, dziArgs);

            String cacheDir = cacheLoc + (fsCache ? params.hashCode.replace("/", File.separator) : params.hashCode);

            if (printCacheDir) {
                System.out.println((fsCache ? "" : ("s3://" + cacheBucket + "/")) +
                                   cacheDir.replace(File.separator, "/")); //print like a URL for use with viewer.html
            } else {
                ExtendedArchiver archiver = fsCache
                    ? new ExtendedDirectoryArchiver(new File(cacheDir))
                    : new S3Archiver(cacheBucket, cacheDir, s3);
                archiver.setLogger(log, pfx);
                DziTask task = new DziTask(params, archiver, cacheDir, s3);
                try {
                    task.preRun();
                } catch (IllegalArgumentException ex) {
                    log.warn(pfx + "unsupported input file: " + ex.getMessage());
                    return;
                }
                task.run();
            }

        } catch (IllegalArgumentException ex) {
            log.warn(pfx + ex.getMessage());
            if (abortOnError) {
                System.exit(1);
            }
        } catch (Exception ex) {
            log.error(pfx + "task error", ex);
            if (abortOnError) {
                System.exit(1);
            }
        }
    }
}
