package jpl.mipl.mars.tile_service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.StringReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.NoSuchFileException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

import java.net.URL;
import java.net.URI;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import javax.json.Json;
import javax.json.JsonStructure;
import javax.json.JsonObject;
import javax.json.JsonString;

import org.apache.commons.io.IOUtils;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.http.conn.ConnectionPoolTimeoutException;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stirling Algermissen
 * @author Marsette Vona
 */
public class TileService extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(TileService.class);

    public static final boolean DEF_ENABLE_S3 = true;
    public static final boolean DEF_ENABLE_HTTP = true;
    public static final boolean DEF_ENABLE_SQS = false;
    public static final int DEF_TASK_POOL_WAIT_SEC = 30;
    public static final boolean DEF_CHECK_ETAG_VALUE = true;
    public static final boolean DEF_SERVE_TILES_FROM_TASKS = true;
    public static final boolean DEF_FILTER_IMAGE_URLS = false;
    public static final boolean DEF_REDIRECT_CACHED = true;
    public static final boolean DEF_ALLOW_MULTIHOP_REDIRECT = false;
    public static final boolean DEF_VERIFY_CLIENT_CREDENTIALS = true;
    public static final boolean DEF_ENABLE_FINDER = false;
    public static final int DEF_SQS_RETRIES = 3;

    public static final int AWAIT_TERMINATION_SEC = 60;

    public static final String DEF_AWS_REGION = "us-gov-west-1";
    public static final int SQS_HEARTBEAT_PERIOD_SEC = 30;
    public static final int SQS_VISIBILITY_TIMEOUT_SEC = 60;
    public static final int SQS_POLL_SEC = 10;

    public static final String VIEWER_TEMPLATE = "/resources/viewer-template.html";

    // /HASH/[PROXY_proxy_]image_files/LEVEL/COL_ROW.ext (note that HASH can contain slashes)
    public static final Pattern TILE_PATTERN =
        Pattern.compile("^/(.*)/(?:([^_]+)_proxy_)?image_files/" + CachedArchiver.TILE_PATTERN.pattern());

    public static final String IMAGE_DESCRIPTIONS_PATH = "/jpl/mipl/mars/viewer/image/desc/image_descriptions.xml";
    public static final String IMAGE_CONFIG_PATH       = "/jpl/mipl/mars/viewer/image/config/image_config.xml";

    public static final String OPENSEADRAGON_PATH = "/resources/openseadragon.min.js";

    private final ConcurrentHashMap<String, DziTask> ongoingProcessing = new ConcurrentHashMap<String, DziTask>();

    private final ExecutorService backgroundProcessing = Executors.newCachedThreadPool(runnable -> {
            var thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setDaemon(true);
            thread.setName("tile service task " + thread.getName());
            return thread;
        });

    //most stuff is either final or volatile
    //because (afaik) Tomcat may call doGet() from multiple threads
    //most of this is actually set by init() and immutable thereafter
    //but unfortunately we can't use final because init() is not a constructor
    //so using volatile just to ensure safe publication of the values from the init() thread to the doGet() threads

    private volatile boolean debug;

    private volatile String awsProfile;
    private volatile String awsRegion;
    private volatile S3Helper.UseAWSHeader useAWSHeader;
    private volatile boolean enableS3;
    private volatile boolean enableHTTP;
    private volatile S3Helper s3;
    private volatile boolean verifyClientCredentials;

    private volatile String dataUrl;
    private volatile String tileUrl;

    private volatile String cacheLoc;
    private volatile String cacheBucket;
    private volatile boolean cacheIsS3;

    private volatile int maxConcurrentRequests;
    private volatile int maxConcurrentTasks;
    private volatile int concurrentRequests;
    private final Object requestCountLock = new Object();

    private volatile long maxTaskPoolWaitMS;
    private volatile long maxLambdaWaitMS;

    private volatile long maxLambdaRDRBytes;
    private volatile long maxLambdaImageBytes;

    private volatile boolean serveTilesFromTasks;
    private volatile boolean filterImageURLs;

    private volatile boolean checkEtagValue;

    private volatile boolean redirectCached;
    private volatile boolean allowMultihopRedirect;

    private volatile boolean enableFinder;

    private volatile String dziProtocolOverride;
    private volatile String dziHostOverride;
    private volatile int dziPortOverride;
    private volatile String dziPathOverride;
    private volatile String dziSuffixOverride;

    private volatile long maxMemory;
    private volatile long maxDisk;

    private volatile String inputQueueName;
    private volatile String inputQueueUrl;
    private volatile String failQueueName;
    private volatile String failQueueUrl;

    private volatile boolean enableSqs;
    private volatile SqsClient sqsClient;
    private volatile Thread sqsServiceThread;
    private volatile Thread sqsHeartbeatThread;
    private volatile ConcurrentHashMap<String, DziRequest> sqsReceipts = new ConcurrentHashMap<String, DziRequest>();
    private volatile int sqsMaxRetries;

    //we might be able to get by with volatile instead of atomic here
    //(or neither if we lock all accesses)
    //but atomic shouldn't hurt much and at one point volatile accesses to 64 bit quantities could be subject to tearing
    private final AtomicLong memRemaining = new AtomicLong();
    private final AtomicLong diskRemaining = new AtomicLong();

    private final Object taskResourceLock = new Object();

    @Override
    public void init() throws ServletException {

        //mars.jar contains a META-INF/services/javax.xml.parsers.DocumentBuilderFactory
        //that specifies an old xerces implementation for javax.xml.parsers.DocumentBuilderFactory
        //we remove that file from image_tiler.jar in our pom.xml
        //but it appears more tricky to do same for image_tiler.war
        //because in that case the whole mars.jar is included as is
        //so for the war we override the services file here
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory",
                           "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");

        String pfx = DziTask.pfx;

        Utils.spewJVM(log, pfx);

        String profile = getInitParameter("aws_profile");
        awsProfile = profile != null && !profile.trim().isEmpty() ? profile.trim() : "null";

        String region = getInitParameter("aws_region");
        awsRegion = region != null && !region.trim().isEmpty() ? region.trim() : DEF_AWS_REGION;

        //turn on debug always during DZI config
        DziTask.setConfig((name) -> getInitParameter(name.toLowerCase()),
                          Map.of("DEBUG_TILER", "true", "SPEW_PID", "true"));

        debug = DziTask.debug = Utils.parseBool(getInitParameter("debug_tiler"), false);
        Utils.setLogLevel(DziTask.log, debug ? "DEBUG" : "INFO");
        if (debug) Utils.setLogLevel(log, "DEBUG");

        DziParams.setDefaults((name) -> getInitParameter(name.toLowerCase()));

        useAWSHeader = Utils.parseEnum(getInitParameter("use_aws_header"), S3Helper.UseAWSHeader.class,
                                       S3Helper.UseAWSHeader.prefer);
        log.info(pfx + "use AWS header: " + useAWSHeader);

        enableS3  = Utils.parseBool(getInitParameter("enable_s3"), DEF_ENABLE_S3);
        log.info(pfx + "enable S3: " + enableS3);

        if (enableS3) {
            initS3(pfx);
        }

        enableHTTP  = Utils.parseBool(getInitParameter("enable_http"), DEF_ENABLE_HTTP);
        log.info(pfx + "enable HTTP: " + enableHTTP);

        dataUrl = Utils.ensureSeparators(getInitParameter("data_url"), "/").trim(); //null ok
        log.info(pfx + "data proxy URL: " + dataUrl);

        tileUrl = Utils.ensureSeparators(getInitParameter("tile_url"), "/").trim(); //null ok
        log.info(pfx + "image tile service URL: " + tileUrl);

        String wd = "";
        try {
            wd = Utils.ensureSeparators(Paths.get("").toAbsolutePath().toString(), File.separator);
        } catch (Exception ex) {
        }

        String dr = DziParams.fsInputDir = Utils.ensureSeparators(getInitParameter("fs_input_dir"), "/");
        log.info(pfx + "filesystem doc root: " + (Paths.get(dr).isAbsolute() ? dr : wd + dr));

        String cacheType = getInitParameter("cache_type");
        if (cacheType == null) {
            cacheType = "s3";
        } else {
            cacheType = cacheType.trim().toLowerCase();
            if (cacheType.equals("auto")) {
                cacheType = "s3";
            }
        }
        switch (cacheType) {
            case "s3": {
                cacheIsS3 = true;
                if (!enableS3)
                {
                    throw new ServletException(pfx + "cache_type=s3 requires enable_s3=true");
                }
                cacheLoc = Utils.ensureSeparators(getInitParameter("s3_cache_loc"), "/").trim();
                cacheBucket = getInitParameter("s3_cache_bucket_name").trim();
                if (cacheBucket == null || cacheBucket.isEmpty()) {
                    throw new ServletException(pfx + "cache_type=s3 requires s3_cache_bucket_name");
                }
                log.info(pfx + "S3 cache: s3://" + cacheBucket + "/" + cacheLoc);
                break;
            }
            case "fs": {
                cacheIsS3 = false;
                cacheLoc = Utils.ensureSeparators(getInitParameter("fs_cache_loc"), File.separator).trim();
                log.info(pfx + "filesystem cache: " + (Paths.get(cacheLoc).isAbsolute() ? cacheLoc : wd + cacheLoc));
                break;
            }
            default: {
                throw new ServletException(pfx + "unsupported cache type (must be s3 or fs): " + cacheType);
            }
        }

        enableSqs = Utils.parseBool(getInitParameter("enable_sqs"), DEF_ENABLE_SQS);
        if (enableSqs) {
            initSqs();
        } else {
            log.info(pfx + "SQS disabled");
        }

        maxConcurrentRequests = Utils.parseInt(getInitParameter("tiler_max_concurrent_requests"), -1);
        log.info(pfx + "max concurrent requests: " + maxConcurrentRequests);

        maxConcurrentTasks = DziTask.getMaxConcurrentTasks(getInitParameter("max_concurrent_tasks"));
        log.info(pfx + "max concurrent tasks: " + maxConcurrentTasks);

        maxMemory = DziTask.getMaxMemoryForAllTasks();
        memRemaining.set(maxMemory);
        log.info(pfx + "max memory for all tasks: " + Utils.kmg(maxMemory));

        maxDisk = DziTask.getMaxDiskForAllTasks();
        diskRemaining.set(maxDisk);
        log.info(pfx + "max disk for all tasks: " + Utils.kmg(maxDisk));

        maxTaskPoolWaitMS = 1000L * Utils.parseSeconds(getInitParameter("task_pool_wait_time"), DEF_TASK_POOL_WAIT_SEC);
        log.info(pfx + "max task pool wait time: " + Utils.hms(maxTaskPoolWaitMS));

        maxLambdaWaitMS = 1000L * Utils.parseSeconds(getInitParameter("lambda_wait_time"), 0);
        log.info(pfx + "max Lambda wait time: " + Utils.hms(maxLambdaWaitMS));

        maxLambdaRDRBytes = Utils.parseBytes(getInitParameter("lambda_max_rdr_bytes"), -1);
        log.info(pfx + "max Lambda RDR file bytes: " + Utils.kmg(maxLambdaRDRBytes));

        maxLambdaImageBytes = Utils.parseBytes(getInitParameter("lambda_max_image_bytes"), -1);
        log.info(pfx + "max Lambda RDR image bytes: " + Utils.kmg(maxLambdaImageBytes));

        serveTilesFromTasks = Utils.parseBool(getInitParameter("serve_tiles_from_tasks"), DEF_SERVE_TILES_FROM_TASKS);
        log.info(pfx + "serve tiles from tasks: " + serveTilesFromTasks);

        filterImageURLs = Utils.parseBool(getInitParameter("filter_image_urls"), DEF_FILTER_IMAGE_URLS);
        log.info(pfx + "filter image URLs: " + filterImageURLs);

        checkEtagValue = Utils.parseBool(getInitParameter("check_etag_value"), DEF_CHECK_ETAG_VALUE);
        log.info(pfx + "check eTag value: " + checkEtagValue);

        redirectCached = Utils.parseBool(getInitParameter("redirect_cached"), DEF_REDIRECT_CACHED);
        log.info(pfx + "redirect cached: " + redirectCached);

        allowMultihopRedirect =
            Utils.parseBool(getInitParameter("allow_multihop_redirect"), DEF_ALLOW_MULTIHOP_REDIRECT);
        log.info(pfx + "allow multihop redirect: " + allowMultihopRedirect);

        enableFinder = Utils.parseBool(getInitParameter("enable_finder"), DEF_ENABLE_FINDER);
        if (enableFinder && (DziParams.fsInputDir == null || DziParams.fsInputDir.length() == 0)) {
            log.warn(pfx + "finder API disabled, fs_input_dir not set");
            enableFinder = false;
        }
        if (enableFinder && !Finder.missionSupported(DziTask.mission)) {
            log.warn(pfx + "finder API not implemented for mission \"" + DziTask.mission + "\"");
            enableFinder = false;
        }
        log.info(pfx + "enable finder: " + enableFinder);

        Finder.maxSolRangeAge = Utils.parseSeconds(getInitParameter("finder_max_sol_range_age"),
                                                   Finder.DEF_MAX_SOL_RANGE_AGE_SEC);
        log.info(pfx + "finder max sol range age: " + Utils.hms(Finder.maxSolRangeAge * 1e3));

        dziProtocolOverride = Utils.parseString(getInitParameter("dzi_protocol_override").trim(), null);
        log.info(pfx + "DZI protocol override: " + dziProtocolOverride);

        dziHostOverride = Utils.parseString(getInitParameter("dzi_host_override").trim(), null);
        log.info(pfx + "DZI host override: " + dziHostOverride);

        dziPortOverride = Utils.parseInt(getInitParameter("dzi_port_override"), -2); //use request port
        log.info(pfx + "DZI port override: " + dziPortOverride);

        dziPathOverride = Utils.parseString(getInitParameter("dzi_path_override").trim(), null);
        if (dziPathOverride != null && !dziPathOverride.isEmpty()) {
            if (!dziPathOverride.startsWith("/")) dziPathOverride = "/" + dziPathOverride;
            while (dziPathOverride.endsWith("/")) {
                dziPathOverride = dziPathOverride.substring(0, dziPathOverride.length() - 1);
            }
        }
        log.info(pfx + "DZI path override: " + dziPathOverride);

        dziSuffixOverride = Utils.parseString(getInitParameter("dzi_suffix_override").trim(), null);
        if (dziSuffixOverride != null && !dziSuffixOverride.isEmpty()) {
            if (!dziSuffixOverride.startsWith("/")) dziSuffixOverride = "/" + dziSuffixOverride;
            while (dziSuffixOverride.endsWith("/")) {
                dziSuffixOverride = dziSuffixOverride.substring(0, dziSuffixOverride.length() - 1);
            }
        }
        log.info(pfx + "DZI suffix override: " + dziSuffixOverride);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        handleRequest(request, response);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String pfx = logPrefix(request);
        if ("application/json".equals(request.getContentType())) {
            handleRequest(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            log.error(pfx + "bad request, POST must be application/json");
        }
    }

    @Override
    public void destroy() {
        String pfx = DziTask.pfx;
        backgroundProcessing.shutdownNow();
        try {
            backgroundProcessing.awaitTermination(AWAIT_TERMINATION_SEC, TimeUnit.SECONDS);
        } catch(InterruptedException ex) {
            log.warn(pfx + "interrupted waiting for background processing tasks to shut down");
        }
        if (sqsHeartbeatThread != null) {
            sqsHeartbeatThread.interrupt();
        }
        if (sqsServiceThread != null) {
            sqsServiceThread.interrupt();
        }
        if (enableS3) {
            try {
                S3Helper.destroyCache();
            } catch (InterruptedException ex) {
                log.error(pfx + "interrupted waiting for S3 credential cache cleanup");
            }
        }
    }

    private void handleRequest(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        final String pfx = logPrefix(request);
        DziRequest req = null;
        Function<DziRequest, String> bestPfx = (r) -> (r != null ? (r.task != null ? r.task.pfx : r.params.pfx) : pfx);
        try {

            //https://stackoverflow.com/a/5563656
            boolean overload = false;
            synchronized (requestCountLock) {
                if (maxConcurrentRequests > 0 && concurrentRequests >= maxConcurrentRequests) {
                    overload = true;
                } else {
                    concurrentRequests++;
                }
            }
            if (overload) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                log.error(pfx + "rejected request, processing >= " + maxConcurrentRequests + " requests");
                return;
            }

            if (debug) {
                log.debug(pfx + "processing " + concurrentRequests + " requests, " +
                          ongoingProcessing.size() + " tasks");
            }

            String requestPath = request.getPathInfo(); //starts with slash, but may be null
            Matcher matcher = requestPath != null ? TILE_PATTERN.matcher(requestPath) : null;
            String rdrUrl = request.getParameter("image"); //already URL decoded

            if (matcher != null && matcher.matches()) { // serve a tile image

                String hashCode = matcher.group(1);
                String proxy = matcher.group(2);
                String tilePath = "image_files/"
                    + matcher.group(3) + "/" + matcher.group(4) + "_" + matcher.group(5) + "." + matcher.group(6);
                String tileFormat = matcher.group(6).toLowerCase(); //will be validated by DziTask.formatToMimeType()

                serveTileImage(request, response, hashCode, proxy, tilePath, tileFormat);

            } else if ((requestPath == null || requestPath.equals("") || requestPath.equals("/")) && rdrUrl != null) {

                req = serveDZI(request, response, rdrUrl, pfx);

            } else if (requestPath != null && requestPath.toLowerCase().equals("/image_descriptions.xml")) {

                serveResource(IMAGE_DESCRIPTIONS_PATH, "text/xml", response);

            } else if (requestPath != null && requestPath.toLowerCase().equals("/image_config.xml")) {

                serveResource(IMAGE_CONFIG_PATH, "text/xml", response);

            } else if (requestPath != null && requestPath.toLowerCase().equals("/openseadragon.min.js")) {

                serveResource(OPENSEADRAGON_PATH, "application/x-javascript", response);

            } else if (requestPath != null && requestPath.toLowerCase().equals("/version")) {

                serveText(Utils.getVersion(getClass()), response);

            } else if (requestPath != null && requestPath.toLowerCase().startsWith("/finder")) {

                serveFinder(request, response);

            } else {
                throw new IllegalArgumentException("unrecognized request");
            }

        } catch (IllegalArgumentException ex) {

            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            log.error(bestPfx.apply(req) + "bad request: " + ex.getMessage());

        } catch (ClientAbortException ex) {

            log.info(bestPfx.apply(req) + "connection closed by client");

        } catch (S3Exception ex) {

            int code = ex.statusCode();
            response.setStatus(code >= 400 ? code : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error(bestPfx.apply(req) + "S3 error: " + ex.getMessage());

        } catch (SdkClientException | IllegalStateException ex) {

            String cpm = null;
            if (ex.getCause() instanceof ConnectionPoolTimeoutException) {
                //might be due to exhaustion of available entries in the S3 client connection pool
                //we are supposed to be closing every S3 connection for sure
                //but just in case, this could help keep the server running
                //also see comments in S3Helper constructor
                cpm = ex.getCause().getMessage();
                if (cpm == null) {
                    cpm = "ConnectionPoolTimeoutException";
                }
            }
            //java.lang.IllegalStateException: Connection pool shut down
            if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("connection pool")) {
                cpm = ex.getMessage();
            }
            if (ex.getCause() != null && ex.getCause().getMessage() != null &&
                ex.getCause().getMessage().toLowerCase().contains("connection pool")) {
                cpm = ex.getCause().getMessage();
            }
            if (cpm != null && enableS3) {
                log.warn(bestPfx.apply(req) + cpm + ", replacing S3 client");
                try {
                    //depending on the internal implementation of the AWS S3 client this could still be a resource leak
                    //because the old S3 client's stale http connections might still be consuming resources
                    //this can also happen in low memory situations because apache thinks all they can do is punt
                    //https://github.com/apache/httpcomponents-client/commit/ca98ad69adad79de57d8b944ba524f7267a795cb
                    //https://github.com/aws/aws-sdk-java/issues/2337
                    initS3(DziTask.pfx);
                } catch (Exception ex2) {
                    //already logged
                }
            }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error(bestPfx.apply(req) + "error handling request", ex);

        } catch (DziTask.CancellationException ex) {

            if ("lost-interlock".equals(ex.source)) {
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
                log.info(bestPfx.apply(req) + "lost interlock: " + ex.getMessage());
            } else {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                log.error(bestPfx.apply(req) + ex.getMessage(), ex);
            }

        } catch (IOException ex) {
            
            while (ex.getCause() instanceof IOException) {
                ex = (IOException)(ex.getCause());
            }

            if (ex instanceof DziTask.TimeoutException) {
                
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
                log.info(bestPfx.apply(req) + ex.getMessage());
                
            } else if (ex instanceof FileNotFoundException || ex instanceof NoSuchFileException) {
                
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                log.error(bestPfx.apply(req) + "file not found: " + ex.getMessage());
                
            } else if (ex instanceof EOFException) {

                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                log.error(bestPfx.apply(req) + "unexpected end of file");

            } else if (ex.getCause() instanceof IllegalArgumentException) {

                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                log.error(bestPfx.apply(req) + "bad request: " + ex.getMessage() + "; " + ex.getCause().getMessage(),
                          ex.getCause());

            } else if (ex.getCause() instanceof DziTask.CancellationException) {

                var ce = (DziTask.CancellationException)(ex.getCause());
                if ("lost-interlock".equals(ce.source)) {
                    response.setStatus(HttpServletResponse.SC_ACCEPTED);
                    log.info(bestPfx.apply(req) + "lost interlock: " + ce.getMessage());
                } else {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    log.error(bestPfx.apply(req) + ce.getMessage(), ex);
                }

            } else if (ex.getMessage().contains("no compatible image reader")) {

                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                log.error(bestPfx.apply(req) + "bad request: " + ex.getMessage());

            } else {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                log.error(bestPfx.apply(req) + "I/O error handling request", ex);
            }

        } catch (Exception ex) {

            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error(bestPfx.apply(req) + "error handling request", ex);

        } catch (LinkageError ex) {

            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error(bestPfx.apply(req) +
                      "error handling request: linkage error, provenance " + Utils.fromWhence(ex), ex);

        } finally {
            if (req != null && req.s3 != null && req.s3 != s3 && !req.taskWillCloseS3) {
                req.s3.close();
            }
            synchronized (requestCountLock) {
                concurrentRequests--;
                if (concurrentRequests < 0) {
                    concurrentRequests = 0;
                }
            }
        }
    }

    private void initS3(String pfx) {
        int s3CacheSize = Utils.parseInt(getInitParameter("cache_s3_credentials"), S3Helper.DEF_CACHE_SIZE);
        log.info(pfx + "S3 credential cache size: " + s3CacheSize);
        
        long s3CacheMS =
            1000L * Utils.parseSeconds(getInitParameter("max_s3_credentials_age"), S3Helper.DEF_MAX_CACHE_AGE_SEC);
        log.info(pfx + "S3 credential cache timeout: " + Utils.hms(s3CacheMS));
        
        verifyClientCredentials = Utils.parseBool(getInitParameter("verify_client_credentials"),
                                                  DEF_VERIFY_CLIENT_CREDENTIALS);
        log.info(pfx + "verify client S3 credentials: " + verifyClientCredentials);
        
        S3Helper.setupCache(s3CacheSize, s3CacheMS, log, pfx, debug);
        
        try {
            s3 = new S3Helper(getInitParameter("aws_profile"), awsRegion); //null ok
            log.info(pfx + "AWS profile: " + s3.getAWSProfile() + ", region: " + s3.getAWSRegion());
        } catch (Exception ex) {
            log.error(pfx + "error connecting to Amazon S3", ex);
            throw ex;
        }
    }

    private String logPrefix(HttpServletRequest request) {
        String requestPath = request.getPathInfo(); //starts with slash, but may be null
        String requestQuery = request.getQueryString(); //may be null
        return DziTask.getVersion() + " [" +
            (requestPath != null ? requestPath : "") +
            (requestQuery != null ? ((requestPath != null ? "?" : "") + requestQuery) : "") + "] ";
    }

    private void serveTileImage(HttpServletRequest request, HttpServletResponse response,
                                String hashCode, String proxy, String tilePath, String tileFormat) throws IOException {

        String pfx = logPrefix(request);

        if (!DziParams.isHashCode(hashCode)) { //avoid tricks like ../../../pwned
            throw new IllegalArgumentException("invalid hash code");
        }
        
        String cachePath = getCachePath(hashCode, tilePath);
        
        DziTask task = ongoingProcessing.get(hashCode);

        proxy = effectiveProxy(getProxy(request, (proxy != null && !proxy.isEmpty()) ? proxy : "default"));
        boolean isCached = cacheFileExists(cachePath);
        boolean serveFromTask = task != null && serveTilesFromTasks;
        boolean isRedirect = !serveFromTask && isCached && doRedirect(proxy);

        if (cacheIsS3 && useAWSHeader != S3Helper.UseAWSHeader.never && verifyClientCredentials &&
            (!isRedirect || proxy.equals("s3"))) {
            boolean allowAccess = true;
            S3Helper reqS3 = S3Helper.getRequestS3(request, useAWSHeader, debug ? log : null, pfx);
            if (reqS3 != null) {
                //we are supposed to use the client's AWS credentials for S3 access
                //but we will serve the tile image from cache using the server's AWS credentials
                //(because the client AWS credentials don't necessary give read access to the cache bucket)
                //so try to figure out what the original RDR URL was
                //and verify that the client AWS credentials can read it
                String err = null;
                String rdrUrl = null;
                if (task != null) {
                    rdrUrl = task.params.rdrUrl;
                } else {
                    String metadataPath = getCachePath(hashCode, DziTask.METADATA_FILE);
                    try {
                        if (cacheFileExists(metadataPath)) {
                            String mdText = loadCacheText(metadataPath);
                            JsonObject mdJson = Json.createReader(new StringReader(mdText)).readObject();
                            JsonString rdrUrlJson = mdJson.getJsonString("rdr_url");
                            if (rdrUrlJson != null) {
                                rdrUrl = rdrUrlJson.getString();
                            } else {
                                err = "rdr_url not found in " + metadataPath;
                            }
                        } else {
                            err = metadataPath + " not found";
                        }
                    } catch (Exception ex) {
                        err = "error getting rdr_url from " + metadataPath + ": " + ex.toString();
                    }
                }
                if (rdrUrl != null && S3Helper.isS3Url(rdrUrl)) { //we don't check HTTP(s) rdrUrl for access here
                    try {
                        allowAccess = reqS3.doesObjectExist(rdrUrl);
                    } catch (Exception ex) {
                        err = "error checking access to " + rdrUrl + ": " + ex.toString();
                    }
                }
                reqS3.close();
                if (err != null) {
                    if (useAWSHeader == S3Helper.UseAWSHeader.always) {
                        throw new IOException(err);
                    } else {
                        log.warn(pfx + "error checking read access using client credentials: " + err);
                    }
                } else if (!allowAccess) {
                    throw S3Exception.builder()
                        .statusCode(403)
                        .message("image tile " + cachePath +
                                 " available but client credentials do not allow access to " + rdrUrl)
                        .build();
                }
            }
        }
        
        if (serveFromTask) {

            if (debug) log.debug(pfx + "serving from task");

            //now that we use FileCachedArchiver, task will just serve from cache anyway
            //but this codepath will wait MAX_WAIT_TIME for the tile to be computed
            //this can be valuable because normally the frontend DZI viewer (OpenSeaDragon) will not retry failed tiles
            //and using the data proxy to request tiles that aren't computed yet will fail fast with 404

            //there is a possibility that at some point the frontend may get the ability to nicely retry failed tiles
            //(which would also address failure due to wait times that exceed the csso proxy timeout)
            //in that case we may turn this off by default and always just serve tiles from the data proxy

            //using direct pre-signed s3 urls for tiles is also an alternative to the data proxy
            //but will also fail fast with 404 for tiles that aren't computed yet
            
            //it's unlikely but perhaps possible if we get there that the dzi might have already been served from cache
            //see https://openseadragon.github.io/examples/tilesource-image/
            response.setHeader("crossOriginPolicy", "Anonymous");

            serveImage(task.getTileBlocking(request.getPathInfo()), tileFormat, response);

        } else if (isCached) {
            serveCacheFile(cachePath, Utils.formatToMimeType(tileFormat), proxy, pfx, response);
        } else {
            throw new FileNotFoundException(cachePath);
        }
    }

    private DziRequest serveDZI(HttpServletRequest request, HttpServletResponse response, String rdrUrl, String pfx)
        throws IOException, DziTask.CancellationException {
        
        DziRequest req = new DziRequest(request, response, s3, pfx, filterImageURLs);
        
        if (req.params.rdrUrlIsHTTP && !enableHTTP) {
            throw new IllegalArgumentException("HTTP[S] image URLs disabled");
        }

        if (req.params.rdrUrlIsS3 && !enableS3) {
            throw new IllegalArgumentException("S3 image URLs disabled");
        }
        
        if (!req.params.rdrUrlIsS3 && !req.params.rdrUrlIsHTTP &&
            (DziParams.fsInputDir == null || DziParams.fsInputDir.length() == 0)) {
            throw new IllegalArgumentException("non-S3 image URLs disabled");
        }

        if (!DziTask.filterUrl(rdrUrl, !req.filter)) {
            throw new IllegalArgumentException("image URL rejected by filter");
        }
        
        String proxy = effectiveProxy(req.proxy);

        //can't call isCached() yet because it may need req.s3 to be properly set
        //still, it'd be nice to avoid creating the client s3 if it's not needed
        //mostly it seems we can't avoid creating it in production
        //because for security purposes we need to at least verify that the client credentials can read the source RDR
        //one path that we can optimize is requests for auxiliary files (e.g. thumbnails) when effective proxy is "data"
        //but that will also be uncommon because typically allowMultihopRedirect is false

        if (enableS3 && req.params.rdrUrlIsS3 && useAWSHeader != S3Helper.UseAWSHeader.never &&
            !(req.isAuxiliary() && doRedirect(proxy) && proxy.equals("data"))) {
            S3Helper reqS3 = S3Helper.getRequestS3(request, useAWSHeader, debug ? log : null, pfx);
            if (reqS3 != null) {
                if (!reqS3.doesObjectExist(rdrUrl)) {
                    reqS3.close();
                    throw S3Exception.builder()
                        .statusCode(403)
                        .message("client credentials do not allow access to " + rdrUrl)
                        .build();
                }
                if (s3.doesObjectExist(rdrUrl)) {
                    reqS3.close(); //server credentials can read the input RDR
                } else { 
                    req.s3 = reqS3;
                    log.info(pfx + "end-user credentials required to read " + rdrUrl);
                }
            }
        }

        boolean isCached = req.isAuxiliary() || (!req.params.force && isCached(req, false));

        req.task = ongoingProcessing.get(req.params.hashCode);
        
        if (req.serveViewer) {
            log.info(pfx + "serving viewer");
            serveViewer(req);
        } else if (req.task != null) {
            log.info(pfx + "serving from task");
            serveFromTask(req);
        } else if (isCached || req.isAuxiliary()) {
            log.info(pfx + "serving from cache");
            serveFromCache(req);
        } else {
            log.info(pfx + "no ongoing task, " + (!isCached ? "cache miss" : "force recompute"));
            boolean waitForLambda = true;
            boolean enableFailQueue = true;
            boolean background = true;
            processDZI(req, waitForLambda, enableFailQueue, background);
        }

        return req;
    }

    private boolean isCached(DziRequest req, boolean expectUncached) throws IOException {
        //etag file is written first, metadata file is written last
        //older versions of the service did not write etag files though
        String pfx = req.pfx + (cacheIsS3 ? ("s3://" + cacheBucket + "/") : "");
        if (cacheFileExists(req.metadataPath)) {
            return true; //processing complete
        }
        if (cacheFileExists(req.errorPath)) {
            if (debug || !expectUncached) log.debug(pfx + req.errorPath + " incomplete cache entry: processing error");
            return false;
        }
        if (!cacheFileExists(req.etagPath)) {
            if (debug) log.debug(pfx + req.etagPath + " no cached etag");
            return false; //processing not started
        }
        //get here iff processing started but not complete
        long etagTimestamp = cacheFileTime(req.etagPath);
        long now = System.currentTimeMillis();
        if (DziTask.maxZombieMS > 0) {
            long zombieMS = now - (cacheFileExists(req.progressPath) ? cacheFileTime(req.progressPath) : etagTimestamp);
            if (zombieMS > DziTask.maxZombieMS) {
                if (debug || !expectUncached) {
                    log.info(pfx + req.etagPath + " incomplete cache entry: zombie time " +
                             Utils.hms(zombieMS) + " > " + Utils.hms(DziTask.maxZombieMS));
                }
                return false;
            }
        }
        //get here iff it seems that another component (e.g. the lambda, another server instance, or
        //someone manually running the CLI direct to the same cache bucket) is computing this product and is still alive
        //we may still want to consider it "uncached" and recompute it if it appears the source file has changed
        //since processing started (this should be rare because mission filenames generally include a version number)
        long rdrTimestamp = fileTime(req.params.rdrUrl, req.s3);
        if (etagTimestamp < rdrTimestamp || rdrTimestamp < 0) {
            if (debug || !expectUncached) {
                log.debug(pfx + req.etagPath + " stale cache entry, eTag timestamp " +
                          (etagTimestamp >= 0 ? Utils.toISO8601(etagTimestamp) : "unknown") + " < RDR timestamp " +
                          (rdrTimestamp >= 0 ? Utils.toISO8601(rdrTimestamp) : "unknown"));
            }
            return false;
        }
        if (checkEtagValue && !fileETag(req.params.rdrUrl, req.s3).equals(loadCacheText(req.etagPath))) {
            if (debug || !expectUncached) log.info(pfx + req.etagPath + " stale cache entry: eTag value mismatch");
            return false;
        }
        return true; //don't recompute the product, another product is computing it
    }

    private void serveFromCache(DziRequest req) throws IOException {
        String pfx = req.pfx;
        if (req.serveError) {
            String error = loadCacheText(req.errorPath); //maybe file not found
            log.info(pfx + req.errorPath + " serving error from cache: " + error);
            serveText(error, req.response);
        } else if (req.serveProgress) {
            if (cacheFileExists(req.metadataPath)) {
                String progress = "100.00";
                log.info(pfx + req.metadataPath + " serving progress for completed task: " + progress);
                serveText(progress, req.response);
            } else {
                String progress = loadCacheText(req.progressPath); //mabye file not found
                log.info(pfx + req.progressPath + " serving progress from cache: " + progress);
                serveText(progress, req.response);
            }
        } else if (req.serveThumb) {
            if (waitForCache(req, req.thumbPath)) {
                serveCacheFile(req.thumbPath, Utils.formatToMimeType(DziTask.THUMB_FORMAT), effectiveProxy(req.proxy),
                               pfx, req.response);
            }
        } else if (req.serveMetadata) {
            if (waitForCache(req, req.metadataPath)) {
                serveCacheFile(req.metadataPath, "application/json", effectiveProxy(req.proxy), pfx, req.response);
            }
        } else {
            if (waitForCache(req, req.dziPath)) {
                log.info(pfx + req.dziPath + " serving cached dzi");
                setUrlAndServeDZI(loadCacheText(req.dziPath), req, false);
            }
        }
    }

    private boolean waitForCache(DziRequest req, String cachePath) throws IOException {
        if (cacheFileExists(cachePath)) {
            return true;
        }
        long maxWaitMS = req.params.maxWaitSec >= 0 ? (req.params.maxWaitSec * 1000l) : DziTask.maxWaitMS;
        if (maxWaitMS > 0) {
            long deadlineMS = System.currentTimeMillis() + maxWaitMS;
            while (System.currentTimeMillis() < deadlineMS) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ex) {
                    throw new IOException("interrupted waiting for " + cachePath);
                }
                if (cacheFileExists(cachePath)) {
                    return true;
                }
            }
        }
        log.info(req.pfx + cachePath + " not yet available" +
                 (maxWaitMS > 0 ? (", waited " + Utils.hms(maxWaitMS)) : ""));
        req.response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return false;
    }

    private void serveFromTask(DziRequest req) throws IOException {
        String pfx = req.pfx;
        if (req.serveError) {
            log.info(pfx + "serving error for running task");
            Exception error = req.task.getErrorBlocking(); //file not found if timeout or no error
            serveText(error.getMessage(), req.response);
        } else if (req.serveProgress) {
            String progress = String.format("%.2f", req.task.getPercentComplete());
            log.info(pfx + "serving progress from task: " + progress);
            serveText(progress, req.response);
        } else if (req.serveThumb) {
            log.info(pfx + "serving thumbnail from task");
            serveImage(req.task.getThumbnailBlocking(), DziTask.THUMB_FORMAT, req.response);
        } else if (req.serveMetadata) {
            log.info(pfx + "serving metadata from task");
            serveJson(req.task.getMetadataBlocking(), req.response);
        } else {
            log.info(pfx + "serving dzi from task");
            setUrlAndServeDZI(req.task.getDZIBlocking(), req, true);
        }
    }

    private long nowMS() {
        return System.currentTimeMillis();
    }

    private boolean checkTaskResources(long taskMem, long taskDisk) {
        return ((maxMemory < 0 || memRemaining.get() > taskMem) && (maxDisk < 0 || diskRemaining.get() > taskDisk));
    }

    private String taskResourceMsg(long taskMem, long taskDisk) {
        String mm = maxMemory >= 0 ? ("max " + Utils.kmg(maxMemory)) : "unlimited";
        String md = maxDisk >= 0 ? ("max " + Utils.kmg(maxDisk)) : "unlimited";
        String mr = maxMemory >= 0 ? (", " + Utils.kmg(memRemaining.get()) + " available") : "";
        String dr = maxDisk >= 0 ? (", " + Utils.kmg(diskRemaining.get()) + " available") : "";
        return "task mem " + Utils.kmg(taskMem) + " (" + mm + mr + "), " +
            "task disk " + Utils.kmg(taskDisk) + " (" + md + dr + ")";
    }

    private boolean checkExisting(DziRequest req) throws IOException {
        String pfx = req.pfx;
        if (req.params.force) {
            return false;
        }
        if (isCached(req, true)) {
            log.info(pfx + "already cached");
            if (req.response != null) {
                serveFromCache(req);
            }
            finishedRequest(req);
            return true;
        }
        DziTask existingTask = ongoingProcessing.get(req.params.hashCode);
        if (existingTask != null) {
            if (req.response != null) {
                log.info(pfx + "serving from existing task");
                req.task = existingTask;
                serveFromTask(req);
            }
            finishedRequest(req);
            return true;
        }
        return false;
    }

    private void processDZI(DziRequest req, boolean waitForLambda, boolean enableFailQueue, boolean background)
        throws IOException, DziTask.CancellationException {

        long startMS = nowMS();

        String pfx = req.pfx;

        String hashPath = cacheIsS3 ? req.params.hashCode : req.params.hashCode.replace("/", File.separator);
        String cacheDir = cacheLoc + hashPath;
        ExtendedArchiver archiver = cacheIsS3
            ? new S3Archiver(cacheBucket, cacheDir, s3) //always use server credentials to access cache
            : new ExtendedDirectoryArchiver(new File(cacheDir));
        archiver.setLogger(log, pfx);
        req.task = new DziTask(req.params, archiver, cacheDir, req.s3);

        //get source file size, check it, compute resource requirements
        //might throw IOException or IllegalArgumentException, but those are handled properly by our caller
        req.task.preRun();

        if (waitForLambda && maxLambdaWaitMS > 0 && req.params.isNominal && !req.params.force &&
            (maxLambdaRDRBytes <= 0 || req.task.rdrFileBytes() < maxLambdaRDRBytes) &&
            (maxLambdaImageBytes <= 0 || req.task.rdrImageBytes() < maxLambdaImageBytes)) {
            log.info(pfx + "waiting up to " + Utils.hms(maxLambdaWaitMS) + " for Lambda to process " +
                     Utils.kmg(req.task.rdrFileBytes()) + " (" + Utils.kmg(req.task.rdrImageBytes()) +
                     " decompressed) RDR, max " +
                     (maxLambdaRDRBytes > 0 ? Utils.kmg(maxLambdaRDRBytes) : "unlimited") + ", " +
                     (maxLambdaImageBytes > 0 ? Utils.kmg(maxLambdaImageBytes) : "unlimited") + " decompressed");
            while ((nowMS() - startMS) < maxLambdaWaitMS && !ongoingProcessing.containsKey(req.params.hashCode)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    if (req.response != null) {
                        req.response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    }
                    log.error(pfx + "rejected request, interrupted waiting for Lambda");
                    failedRequest(req, enableFailQueue);
                    return;
                }
                if (checkExisting(req)) {
                    return;
                }
            }
        }

        long taskMem = req.task.maxActualTaskMemory();
        long taskDisk = req.task.maxActualTaskDiskCacheBytes();

        boolean interrupted = false, announced = false;
        startMS = nowMS();
        while ((maxConcurrentTasks > 0 && ongoingProcessing.size() >= maxConcurrentTasks) ||
               !checkTaskResources(taskMem, taskDisk)) {
            if (interrupted || maxTaskPoolWaitMS <= 0 || (nowMS() - startMS) > maxTaskPoolWaitMS) {
                if (req.response != null) {
                    req.response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                }
                log.error(pfx + "rejected request, processing " + ongoingProcessing.size() + " tasks (" +
                          (maxConcurrentTasks > 0 ? ("max " + maxConcurrentTasks) : "unlimited") + "), " +
                          taskResourceMsg(taskMem, taskDisk));
                failedRequest(req, enableFailQueue);
                return;
            }
            if (checkExisting(req)) {
                return;
            }
            if (!announced) {
                log.info(pfx + "waiting up to " + Utils.hms(maxTaskPoolWaitMS) + " for task resource availability");
                announced = true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }

        //there are a couple of possible races here
        //
        //1) If multiple requests came in at about the same time while the server was at maxConcurrentTasks then they
        //would have all ended up in the wait loop above, and now more than one of them may be getting out of that loop
        //at about the same time.  If there are enough of them, and/or if there are still a lot of ongoing tasks, then
        //we could possibly exceed maxConcurrentTasks if they all launch new tasks now.  We'll check for that below and
        //abort any new task that would cause the task table size to exceed maxConcurrentTasks. (This
        //implementation is not intended to implement any kind of fairness in what tasks get accepted or rejected.)
        //
        //2) This should be unlikely, but it's possible that if multiple requests came in for the same data product and
        //all got a cache miss that one of them could have already come and gone and computed the product before the
        //others even got to this point.  (Though the timing would require the computation to finish after our last call
        //to isCached() above, which is a narrow window.)  There is one more isCached() check below to handle taht.

        DziTask existingTask = ongoingProcessing.putIfAbsent(req.params.hashCode, req.task);

        if (existingTask != null) {
            if (req.params.force) {
                log.info(pfx + "cancelling existing task " + existingTask.pid.substring(0, 8));
                existingTask.cancel(req.task.pid);
                //the new task will wait for all other ongoing tasks to abort
                //including not only existingTask but also any others that may be ongoing
                //including by the lambda or other server instances
            } else {
                if (req.response != null) {
                    log.info(pfx + "serving from existing task");
                    req.task = existingTask;
                    serveFromTask(req);
                }
                finishedRequest(req);
                return;
            }
        }

        String resourceMsg = null;
        int numTasks = 0;
        boolean reject = false;
        synchronized (taskResourceLock) {
            resourceMsg = taskResourceMsg(taskMem, taskDisk);
            numTasks = ongoingProcessing.size();
            if ((maxConcurrentTasks > 0 && numTasks > maxConcurrentTasks) ||
                !checkTaskResources(taskMem, taskDisk)) {
                reject = true;
            } else {
                if (maxMemory > 0 && taskMem > 0) {
                    memRemaining.addAndGet(-taskMem);
                }
                if (maxDisk > 0 && taskDisk > 0) {
                    diskRemaining.addAndGet(-taskDisk);
                }
            }
        }
        if (reject) {
            ongoingProcessing.remove(req.params.hashCode);
            if (req.response != null) {
                req.response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
            log.error(pfx + "rejected request, " + resourceMsg);
            failedRequest(req, enableFailQueue);
            return;
        }

        log.info(pfx + "launching task, processing " + numTasks + " tasks (" +
                 (maxConcurrentTasks > 0 ? ("max " + maxConcurrentTasks) : "unlimited") + "), " + resourceMsg);

        var alreadyCached = new AtomicBoolean();
        var callable = new Callable<Boolean>() {
                @Override
                public Boolean call() throws IOException, DziTask.CancellationException {
                    try {
                        if (!req.params.force && isCached(req, true)) {
                            log.info(pfx + "already cached");
                            alreadyCached.set(true);
                        } else {
                            req.task.run();
                        }
                        finishedRequest(req);
                        return true;
                    } catch (DziTask.CancellationException ex) {
                        log.warn(pfx + "task cancelled: " + ex.getMessage());
                        if (!"lost-interlock".equals(ex.source)) {
                            failedRequest(req, enableFailQueue);
                        }
                        throw ex;
                    } catch (Exception ex) {
                        log.error(pfx + "task error", ex);
                        failedRequest(req, enableFailQueue);
                        throw ex;
                    } finally {
                        ongoingProcessing.remove(req.params.hashCode);
                        long mr = -1, dr = -1;
                        synchronized (taskResourceLock) {
                            if (maxMemory > 0 && taskMem > 0) {
                                mr = Math.max(0, memRemaining.addAndGet(taskMem));
                            }
                            if (maxDisk > 0 && taskDisk > 0) {
                                dr = Math.max(0, diskRemaining.addAndGet(taskDisk));
                            }
                        }
                        if (req.s3 != s3) {
                            req.s3.close();
                        }
                        log.info(pfx + "task complete" + (alreadyCached.get() ? " (already cached)" : "") +
                                 (mr >= 0 ? (", " + Utils.kmg(mr) + " memory available") : "") +
                                 (dr >= 0 ? (", " + Utils.kmg(dr) + " disk available") : ""));
                    }
                }
            };

        req.taskWillCloseS3 = true;

        if (background) {
            log.info(pfx + "running task in background");
            backgroundProcessing.submit(callable);
        } else {
            log.info(pfx + "running task in foreground");
            callable.call();
            log.info(pfx + "foreground task complete");
        }
        
        if (req.response != null) {
            if (alreadyCached.get()) {
                serveFromCache(req);
            } else {
                serveFromTask(req);
            }
        }
    }

    private void finishedRequest(DziRequest req) {
        String pfx = req.pfx;
        if (sqsClient != null && inputQueueUrl != null && req.sqsReceiptHandle != null) {
            log.info(pfx + "deleting SQS message from input queue " + inputQueueName);
            try {
                var dmr = DeleteMessageRequest.builder()
                    .queueUrl(inputQueueUrl)
                    .receiptHandle(req.sqsReceiptHandle)
                    .build();
                sqsClient.deleteMessage(dmr);
                req.sqsReceiptHandle = null;
            } catch (Exception ex) {
                log.error(pfx + "error deleting SQS message " + req.sqsReceiptHandle.substring(0, 8) +
                          " from input queue "+ inputQueueName + ": " + ex.getMessage(), ex);
            }
        }
    }

    private void failedRequest(DziRequest req, boolean enableFailQueue) {
        String pfx = req.pfx;
        if (enableFailQueue && sqsClient != null && failQueueUrl != null && !req.isAuxiliary()) {
            finishedRequest(req);
            log.info(pfx + "adding to fail queue " + failQueueName);
            try {
                var smr = SendMessageRequest.builder()
                    .queueUrl(failQueueUrl)
                    .messageBody(req.params.toJson())
                    .build();
                sqsClient.sendMessage(smr);
            } catch (Exception ex) {
                log.error(pfx + "error adding to fail queue " + failQueueName + ": " +
                          ex.getMessage(), ex);
            }
        }
    }

    private String getProxy(HttpServletRequest request, String def) {
        String p = request.getParameter("proxy");
        if (p != null) {
            p = p.toLowerCase();
            if (!p.equals("https") && !p.equals("http") && !p.equals("data") && !p.equals("s3") && !p.equals("tile")) {
                p = null;
            }
        }
        return p != null ? p : def;
    }

    private boolean doRedirect(String proxy) {
        return redirectCached && cacheIsS3 && !(proxy.startsWith("http") || proxy.equals("tile"));
    }

    private String effectiveProxy(String proxy) {
        if (proxy != null && !proxy.equals("default")) return proxy;
        if (tileUrl != null && !tileUrl.isEmpty()) return "tile";
        if (cacheIsS3 && redirectCached) {
            return (dataUrl != null && !dataUrl.isEmpty() && allowMultihopRedirect) ? "data" : "s3";
        }
        return "https";
    }

    // sets the Url attribute of the Image node in the dzi xml
    // this is the base Url from which the frontend will fetch image files
    private void setUrlAndServeDZI(String dziXml, DziRequest req, boolean stillProcessing)
        throws IOException {
        try {
            InputStream xmlStream = new ByteArrayInputStream(dziXml.getBytes("utf-8"));
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlStream);

            Element imageNode = doc.getDocumentElement();

            if (!"Image".equals(imageNode.getNodeName())) {
                throw new IOException("error parsing dzi file");
            }

            String proxy = req.proxy;
            if (proxy.equals("default") && !(stillProcessing && serveTilesFromTasks)) {
                //proxy = effectiveProxy(proxy);
                //for now we don't apply allowMultihopRedirect here
                //because in in this case the client should hit the data proxy directly
                //and get redirected at most once
                //though as of 6/29/23 I'm seeing some evidence that whatever version of the PEP data proxy is
                //currently deployed in dev may now itself be doing a multihop redirect, so we may need to change this
                if (tileUrl != null && !tileUrl.isEmpty()) proxy = "tile";
                else if (cacheIsS3 && redirectCached) proxy = (dataUrl != null && !dataUrl.isEmpty()) ? "data" : "s3";
                else proxy = "https";
            }

            // note that HASH can contain slashes
            String imgUrl;
            if (proxy.equals("tile")) { //https://some.server.com/foo/bar/HASH/image_files/
                imgUrl = tileUrl.replace("/HASH/", "/" + req.params.hashCode + "/");
            } else if (proxy.equals("data")) {
                //https://DATAPROXY/cacheBucket/cacheLoc/HASH/image_files/
                imgUrl = dataUrl + cacheBucket + "/" + cacheLoc + req.params.hashCode + "/image_files/";
            } else { //proxy is "http", "https", "s3", or "default"
                //http[s]://SELF[:PORT]/image_tiler/HASH/PROXY_proxy_image_files/
                URL reqUrl = new URL(req.request.getRequestURL().toString());
                String protocol = proxy.startsWith("http") ? proxy : "https";
                if (dziProtocolOverride != null) {
                    log.debug(req.pfx +
                              "overriding protocol in DZI image url from " + protocol + " to " + dziProtocolOverride);
                    protocol = dziProtocolOverride;
                }
                String host = reqUrl.getHost();
                if (dziHostOverride != null) {
                    log.debug(req.pfx + "overriding host in DZI image url from " + host + " to " + dziHostOverride);
                    host = dziHostOverride;
                }
                int port = reqUrl.getPort();
                if (dziPortOverride >= -1) { //-1 means no explicit port, -2 or lower means use request port
                    log.debug(req.pfx + "overriding port in DZI image url from " + port + " to " + dziPortOverride);
                    port = dziPortOverride;
                }
                String path = reqUrl.getPath();
                if (dziPathOverride != null) {
                    log.debug(req.pfx + "overriding path in DZI image url from " + path + " to " + dziPathOverride);
                    path = dziPathOverride; //already ensured this starts with / and does not end with /
                }
                String sfx = "/" + proxy + "_proxy_image_files";
                if (dziSuffixOverride != null) {
                    log.debug(req.pfx + "overriding suffix in DZI image url from " + sfx + " to " + dziSuffixOverride);
                    sfx = dziSuffixOverride; //already ensured this starts with / and does not end with /
                }
                String httpsUrl = new URL(protocol, host, port, path).toString();
                imgUrl = httpsUrl + "/" + req.params.hashCode + sfx + "/";
            }

            log.info(req.pfx + "serving DZI, image URL " + imgUrl);

            imageNode.setAttribute("Url", imgUrl);

            req.response.setContentType("text/xml");
            try (OutputStream out = req.response.getOutputStream()) {
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.transform(new DOMSource(doc), new StreamResult(out));
            }

        } catch (Exception ex) {
            for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof IOException) {
                    throw ((IOException)cause); //includes ClientAbortException
                }
            }
            throw new IOException("error setting Url in dzi: " + ex.getMessage());
        }
    }

    private void serveViewer(DziRequest req) throws IOException {
        URI uri = null;
        try {
            var url = getClass().getResource(VIEWER_TEMPLATE);
            if (url != null) {
                uri = url.toURI();
            }
        } catch (Exception ex) {
            throw new IOException("error loading viewer template", ex);
        }
        if (uri == null) {
            throw new FileNotFoundException(VIEWER_TEMPLATE);
        }
        String template = Files.readString(Paths.get(uri));
        String query = req.request.getQueryString();
        String params = query.replaceAll("(?i)&viewer=true", "").replaceAll("(?i)viewer=true&", "");
        String path = (new URL(req.request.getRequestURL().toString())).getPath();
        req.response.setContentType("text/html");
        try (OutputStream out = req.response.getOutputStream()) {
            out.write(template.replace("{{PATH}}", path).replace("{{PARAMS}}", params).getBytes("UTF-8"));
        }
    }

    private void serveImage(BufferedImage image, String format, HttpServletResponse response) throws IOException {
        response.setContentType(Utils.formatToMimeType(format));
        try (OutputStream out = response.getOutputStream()) {
            if (!ImageIO.write(image, format, out)) {
                throw new IOException("no " + format + " image writer");
            }
        }
    }

    private void serveImage(InputStream imageStream, String format, HttpServletResponse response) throws IOException {
        try (OutputStream out = response.getOutputStream()) {
            response.setContentType(Utils.formatToMimeType(format));
            IOUtils.copy(imageStream, out);
        } finally {
            imageStream.close();
        }
    }

    private void serveJson(JsonStructure json, HttpServletResponse response) throws IOException {
        serveJson(Utils.printJson(json, DziTask.formatJson), response);
    }

    private void serveJson(String json, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        try (OutputStream out = response.getOutputStream()) {
            out.write(json.getBytes("UTF-8"));
        }
    }

    private void serveText(String text, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        try (OutputStream out = response.getOutputStream()) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private void serveCacheFile(String path, String contentType, String proxy, String pfx, HttpServletResponse response)
        throws IOException {
        if (doRedirect(proxy)) {
            String url = null;
            if (proxy.equals("data")) {
                url = dataUrl + cacheBucket + "/" + path;
            } else {
                url = s3.getPresignedS3UrlAsHttps(cacheBucket, path); //use server S3 credentials for cache bucket
            }
            if (debug) {
                log.debug(pfx + "redirecting s3://" + cacheBucket + path + " to " +
                          url.replaceAll("X-Amz-Security-Token=[^&]+", "X-Amz-Security-Token=XXX"));
            }
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.addHeader("Location", url);
        } else {
            log.info(pfx + "proxying cached file " + (cacheIsS3 ? ("s3://" + cacheBucket) : "") + path);
            response.setContentType(contentType);
            try (InputStream in = cacheIsS3 ? s3.getStream(cacheBucket, path) : new FileInputStream(path);
                 OutputStream out = response.getOutputStream()) {
                IOUtils.copy(in, out);
            }
        }
    }

    private void serveResource(String path, String contentType, HttpServletResponse response) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) { //null ok
            if (in == null) {
                throw new FileNotFoundException(path);
            }
            response.setContentType(contentType);
            try (OutputStream out = response.getOutputStream()) {
                IOUtils.copy(in, out);
            }
        }
    }

    private static String checkExists(HttpServletRequest request, String param) throws IOException {
        String relPath = request.getParameter(param);
        if (relPath == null) {
            throw new IllegalArgumentException("missing " + param + " parameter");
        }
        var fullPath = Paths.get(DziParams.fsInputDir, relPath);
        if (!Files.exists(fullPath)) {
            throw new FileNotFoundException(fullPath.toString());
        }
        return relPath;
    }

    private void serveFinder(HttpServletRequest request, HttpServletResponse response) throws IOException {

        String requestPath = request.getPathInfo().toLowerCase(); //starts with slash, already verified not null

        var finder = new Finder(DziTask.mission, DziParams.fsInputDir);

        switch (requestPath) {
            case "/finder/solrange": { // /finder/solrange -> { start: M, end: N }
                serveJson(finder.getSolRange(), response);
                break;
            }
            case "/finder/edrs": { // /finder/edrs?sol=SOL -> [ { edr: PATH, metadata: { ... } }, ... ]
                int sol = Utils.parseInt(request.getParameter("sol"), -1);
                if (sol < 0) {
                    throw new IllegalArgumentException("invalid sol parameter: " + sol);
                }
                serveJson(finder.getEDRs(sol), response);
                break;
            }
            case "/finder/edr": { // /finder/edr?image=PATH -> { edr: PATH, metadata: { ... } }
                serveJson(finder.getEDR(checkExists(request, "image")), response);
                break;
            }
            case "/finder/rdrs": { // /finder/rdrs?edr=PATH -> [ { rdr: PATH, metadata: { ... } }, ... ]
                serveJson(finder.getRDRs(checkExists(request, "edr")), response);
                break;
            }
            case "/finder/rdr": { // /finder/rdr?image=PATH -> { rdr: PATH, metadata: { ... } }
                serveJson(finder.getRDR(checkExists(request, "image")), response);
                break;
            }
            case "/finder/label": {
                // /finder/label?image=PATH -> { "system": { ... }, "properties": { ... }, "tasks": [ ... ] }
                serveJson(finder.getVicarLabel(checkExists(request, "image")), response);
                break;
            }
            default: {
                throw new IllegalArgumentException("unrecognized request");
            }
        }
    }

    private String loadCacheText(String path) throws IOException {
        return cacheIsS3 ? s3.getTextFile(cacheBucket, path) : Files.readString(Paths.get(path));
    }

    private boolean cacheFileExists(String path) throws IOException {
        return cacheIsS3 ? s3.doesObjectExist(cacheBucket, path) : Files.exists(Paths.get(path));
    }

    private long cacheFileTime(String path) throws IOException {
        return cacheIsS3 ? s3.getObjectLastModified(cacheBucket, path) : Utils.fileLastModified(path);
    }

    private long fileTime(String url, S3Helper s3) throws IOException {
        return S3Helper.isS3Url(url) ? s3.getObjectLastModified(url) :
            HTTPHelper.isHTTPUrl(url) ? HTTPHelper.getLastModified(url) :
            Utils.fileLastModified(url);
    }

    private String fileETag(String url, S3Helper s3) throws IOException {
        return S3Helper.isS3Url(url) ? s3.getObjectETag(url) :
            HTTPHelper.isHTTPUrl(url) ? HTTPHelper.getETag(url) :
            Utils.getFileMD5(url);
    }

    private String getCachePath(String hashCode, String path) {
        String s = File.separator;
        return cacheLoc + (cacheIsS3 ? (hashCode + "/" + path) : (hashCode.replace("/", s) + s + path.replace("/", s)));
    }

    private void initSqs() {

        String pfx = DziTask.pfx;

        String iqn = getInitParameter("tile_service_queue_name");
        inputQueueName = iqn != null ? iqn.trim() : null;
        log.info(pfx + "input queue name " + inputQueueName);

        String fqn = getInitParameter("fail_queue_name");
        failQueueName = fqn != null ? fqn.trim() : null;
        log.info(pfx + "fail queue name " + failQueueName);

        if (inputQueueName != null || failQueueName != null) {
            try {
                var clientBuilder = SqsClient.builder();
                clientBuilder.region(Region.of(awsRegion));
                String profileMsg = "";
                if (awsProfile != null && !awsProfile.toLowerCase().equals("null")) {
                    profileMsg = " using profile " + awsProfile;
                    clientBuilder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
                }
                log.info(pfx + "creating SQS client" + profileMsg);
                sqsClient = clientBuilder.build();
            } catch (Exception ex) {
                log.error(pfx + "error creating SQS client: " + ex.getMessage());
            }
        }

        if (sqsClient != null && inputQueueName != null) {
            try {
                sqsMaxRetries = Utils.parseInt(getInitParameter("sqs_retries"), DEF_SQS_RETRIES);
                log.info(pfx + "SQS max retries " + sqsMaxRetries);
                inputQueueUrl =
                    sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(inputQueueName).build()).queueUrl();
                log.info(pfx + "input queue URL " + inputQueueUrl);
                startSqsHeartbeat();
                startSqsService();
            } catch (Exception ex) {
                log.error(pfx + "error getting URL for input queue: " + ex.getMessage());
            }
        }

        if (sqsClient != null && failQueueName != null) {
            try {
                failQueueUrl =
                    sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(failQueueName).build()).queueUrl();
                log.info(pfx + "fail queue URL " + failQueueUrl);
            } catch (Exception ex) {
                log.error(pfx + "error getting URL for fail queue: " + ex.getMessage());
            }
        }
    }

    private void startSqsHeartbeat() {
        String pfx = DziTask.pfx;
        sqsHeartbeatThread = new Thread("SQS heartbeat") {
                public void run() {
                    log.info(pfx + "starting SQS heartbeat thread, period " + SQS_HEARTBEAT_PERIOD_SEC + "s");
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(1000l * SQS_HEARTBEAT_PERIOD_SEC);
                        } catch (InterruptedException ex) {
                            break;
                        }
                        try {
                            if (sqsReceipts.size() > 0) {
                                log.info(pfx + "SQS heartbeat: " + sqsReceipts.size() + " active messages");
                            }
                            for (var entry : sqsReceipts.entrySet()) {
                                setVisibilityTimeout(entry.getKey(), entry.getValue());
                            }
                        } catch (Exception ex) {
                            log.error(pfx + "error in SQS heartbeat: " + ex.getMessage(), ex);
                        }
                    }
                    log.info(pfx + "SQS heartbeat thread exit");
                }
            };
        sqsHeartbeatThread.setDaemon(true);
        sqsHeartbeatThread.start();
    }

    private void startSqsService() {
        String pfx = DziTask.pfx;
        sqsServiceThread = new Thread("SQS service") {
                public void run() {
                    log.info(pfx + "SQS service thread start");
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            //this will block up to SQS_POLL_SEC
                            //if a message was available, it will then further block until the message is processed
                            //it would be possible to adjust not to block during message processing
                            //enabling multiple SQS messages to be processed concurrently
                            //however for now let's just process one at a time
                            //the REST service can still processed other tasks concurrently
                            //and its main job is to serve end users
                            //also in deployments there can be a load balanced group of REST servers
                            //so each one of those can process up to one SQS message at a time
                            var rmr = ReceiveMessageRequest.builder()
                                .queueUrl(inputQueueUrl)
                                .maxNumberOfMessages(1)
                                .visibilityTimeout(SQS_VISIBILITY_TIMEOUT_SEC)
                                .waitTimeSeconds(SQS_POLL_SEC)
                                .attributeNamesWithStrings("All")
                                .build();
                            var resp = sqsClient.receiveMessage(rmr);
                            if (resp.hasMessages()) {
                                for (var msg : resp.messages()) {
                                    var req = new DziRequest(msg.body(), s3);
                                    req.sqsReceiptHandle = msg.receiptHandle();
                                    sqsReceipts.put(req.sqsReceiptHandle, req);
                                    int receiveCount = -1;
                                    try {
                                        var attribs = msg.attributesAsStrings();
                                        if (debug) {
                                            for (var attrib : attribs.entrySet()) {
                                                log.debug(pfx + "SQS message attribute: " +
                                                          attrib.getKey() + "=" + attrib.getValue());
                                            }
                                        }
                                        if (attribs.containsKey("ApproximateReceiveCount")) {
                                            receiveCount =
                                                Integer.parseInt(attribs.get("ApproximateReceiveCount"));
                                        }
                                    } catch (Exception ex) {
                                        log.warn(req.pfx + "error getting receive count from SQS message " +
                                                 req.sqsReceiptHandle.substring(0, 8) + ": " + ex.getMessage());
                                    }
                                    boolean waitForLambda = false;
                                    boolean enableFailQueue = receiveCount < 0 || receiveCount >= sqsMaxRetries;
                                    boolean background = false;
                                    log.info(req.pfx + "handling SQS message on queue " + inputQueueName);
                                    if (debug) log.debug(req.pfx + " SQS receipt " +
                                                         req.sqsReceiptHandle.substring(0, 8) +
                                                         ", receive count " + receiveCount +
                                                         ", enable fail queue: " + enableFailQueue);
                                    try {
                                        processDZI(req, waitForLambda, enableFailQueue, background);
                                    } catch (Exception ex) {
                                        if ((ex instanceof DziTask.CancellationException) &&
                                            "lost-interlock".equals(((DziTask.CancellationException)ex).source)) {
                                            log.info(req.pfx + "lost interlock handling SQS message on queue " +
                                                     inputQueueName + ": " + ex.getMessage());
                                        } else {
                                            log.error(req.pfx + "SQS task error", ex);
                                            //failedRequest() has already been called in processDZI()
                                        }
                                    } finally {
                                        sqsReceipts.remove(req.sqsReceiptHandle);
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log.error(pfx + "error in SQS service: " + ex.getMessage());
                            try {
                                Thread.sleep(SQS_POLL_SEC * 1000); //throttle retries
                            } catch (InterruptedException ie) { /* ignore */ }
                        }
                    }
                    log.info(pfx + "SQS service thread exit");
                }
            };
        sqsServiceThread.setDaemon(true);
        sqsServiceThread.start();
    }

    private void setVisibilityTimeout(String receiptHandle, DziRequest dziReq) {
        try {
            if (debug) log.debug(dziReq.pfx + "setting visibility timeout " + SQS_VISIBILITY_TIMEOUT_SEC + "s " +
                                 "on queue " + inputQueueName + " for receipt " + receiptHandle.substring(0, 8));
            var req = ChangeMessageVisibilityRequest.builder()
                .queueUrl(inputQueueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout(SQS_VISIBILITY_TIMEOUT_SEC)
                .build();
            sqsClient.changeMessageVisibility(req);
        } catch (Exception ex) {
            log.warn(dziReq.pfx + "error setting SQS message visibility timeout: " + ex.getMessage());
        }
    }

    private class DziRequest {

        public String sqsReceiptHandle;
        public DziTask task; 

        public final DziParams params;
        public final String pfx;

        public final String dziPath;
        public final String thumbPath;
        public final String metadataPath;
        public final String etagPath;
        public final String errorPath;
        public final String progressPath;

        public final HttpServletRequest request;
        public final HttpServletResponse response;

        public final boolean serveThumb;
        public final boolean serveMetadata;
        public final boolean serveViewer;
        public final boolean serveProgress;
        public final boolean serveError;

        public final boolean filter;

        public final String proxy;

        public volatile S3Helper s3;
        public volatile boolean taskWillCloseS3;

        public DziRequest(HttpServletRequest request, HttpServletResponse response, S3Helper s3, String pfx,
                          boolean filterImageURLs)
            throws IOException {
            params = new DziParams(request);
            this.pfx = pfx;
            this.s3 = s3;
            dziPath = getCachePath(params.hashCode, DziTask.DZI_FILE);
            thumbPath = getCachePath(params.hashCode, DziTask.THUMB_FILE);
            metadataPath = getCachePath(params.hashCode, DziTask.METADATA_FILE);
            etagPath = getCachePath(params.hashCode, DziTask.ETAG_FILE);
            errorPath = getCachePath(params.hashCode, DziTask.ERROR_FILE);
            progressPath = getCachePath(params.hashCode, DziTask.PROGRESS_FILE);
            this.request = request;
            this.response = response;
            serveThumb = Utils.parseBool(request.getParameter("thumb"), false);
            serveMetadata = Utils.parseBool(request.getParameter("metadata"), false);
            serveViewer = Utils.parseBool(request.getParameter("viewer"), false);
            serveProgress = Utils.parseBool(request.getParameter("progress"), false);
            serveError = Utils.parseBool(request.getParameter("error"), false);
            filter = Utils.parseBool(request.getParameter("filter"), filterImageURLs);
            proxy = getProxy(request, "default");
            params.force &= !isAuxiliary();
        }

        public DziRequest(String paramsJson, S3Helper s3) {
            params = DziParams.fromJson(paramsJson);
            pfx = params.pfx;
            this.s3 = s3;
            dziPath = getCachePath(params.hashCode, DziTask.DZI_FILE);
            thumbPath = getCachePath(params.hashCode, DziTask.THUMB_FILE);
            metadataPath = getCachePath(params.hashCode, DziTask.METADATA_FILE);
            etagPath = getCachePath(params.hashCode, DziTask.ETAG_FILE);
            errorPath = getCachePath(params.hashCode, DziTask.ERROR_FILE);
            progressPath = getCachePath(params.hashCode, DziTask.PROGRESS_FILE);
            request = null;
            response = null;
            serveThumb = serveMetadata = serveViewer = serveProgress = serveError = false;
            filter = false;
            proxy = "default";
        }

        public boolean isAuxiliary() {
            return serveThumb || serveMetadata || serveViewer || serveProgress || serveError;
        }
    }
}
