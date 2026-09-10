package jpl.mipl.mars.tile_service;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import javax.json.Json;
import javax.json.JsonObject;

import javax.imageio.spi.IIORegistry;
import java.lang.reflect.Field;

//though the aws-lambda-java-libs are still using the older com.amazonaws namespace
//they have been updated to use the AWS java SDK 2.x since major version 3
//https://github.com/aws/aws-lambda-java-libs
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import software.amazon.awssdk.eventnotifications.s3.model.S3EventNotification;
import software.amazon.awssdk.eventnotifications.s3.model.S3EventNotificationRecord;

//AWS v2 stuff
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stirling Algermissen
 * @author Marsette Vona
 */
public class TileLambda implements RequestHandler<SQSEvent, String>  {

    private static final Logger log = LoggerFactory.getLogger(TileLambda.class);

    public static final String AWS_REGION = "us-gov-west-1";

    public static final boolean DEF_ENABLE_SQS = false;
    public static final boolean DEF_ENABLE_SQS_HEARTBEAT = false;
    public static final int SQS_HEARTBEAT_PERIOD_SEC = 30;
    public static final int SQS_VISIBILITY_TIMEOUT_SEC = 60;

    private final S3Helper s3;
    private final String cacheBucket;
    private final String cacheLoc;

    private final boolean enableSqs;
    private final boolean enableSqsHeartbeat;
    private final SqsClient sqsClient;
    private final Thread sqsHeartbeatThread;
    private final ConcurrentHashMap<String, String> sqsReceipts = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, String> queueArnToUrl = new ConcurrentHashMap<String, String>();
    private final String tileServiceQueueName;
    private final String tileServiceQueueUrl;

    private final boolean debug;

    static {
        try {
            //work around
            //com.sun.media.imageioimpl.plugins.jpeg.CLibJPEGImageWriterSpi could not be instantiated
            //Caused by: java.lang.IllegalArgumentException: vendorName == null!
            Class<?> packageUtil = Class.forName("com.sun.media.imageioimpl.common.PackageUtil");
            Field vendorField = packageUtil.getDeclaredField("vendor");
            vendorField.setAccessible(true);
            vendorField.set(null, "Sun Microsystems, Inc.");
            Field versionField = packageUtil.getDeclaredField("version");
            versionField.setAccessible(true);
            versionField.set(null, "1.1");
            Field specTitleField = packageUtil.getDeclaredField("specTitle");
            specTitleField.setAccessible(true);
            specTitleField.set(null, "Java Advanced Imaging Image I/O Tools");
            System.out.println("Successfully patched JAI vendorName and version for AWS Lambda.");
        } catch (Exception e) {
            System.err.println("Failed to inject JAI vendor fallback: " + e.getMessage());
            e.printStackTrace();
        }
        try { IIORegistry.getDefaultInstance().registerApplicationClasspathSpis(); }
        catch (Exception e) { System.err.println("Failed to register SPIs: " + e.getMessage()); }
    }

    public TileLambda() {

        String pfx = DziTask.pfx;

        Utils.spewJVM(log, pfx);

        var env = System.getenv();
        var overrides = new HashMap<String, String>();
        overrides.put("MEM_CACHE_TILES", "false");
        overrides.put("LRU_MEM_CACHE_PAGES", "100%");
        overrides.put("LRU_DISK_CACHE_PAGES", "0");
        overrides.put("DISK_BUDGET", "0");
        overrides.put("SPEW_PID", "true");

        var lmb = env.get("LAMBDA_MAX_RDR_BYTES");
        if (lmb != null && !lmb.trim().isEmpty()) {
            overrides.put("MAX_RDR_BYTES", lmb);
        }

        var lmi = env.get("LAMBDA_MAX_IMAGE_BYTES");
        if (lmi != null && !lmi.trim().isEmpty()) {
            overrides.put("MAX_IMAGE_BYTES", lmi);
        }

        overrides.put("DEBUG_TILER", "true"); //turn on debug always during DZI config

        DziTask.setConfig((name) -> env.get(name.toUpperCase()), overrides);

        debug = DziTask.debug = Utils.parseBool(env.get("DEBUG_TILER"), false);
        Utils.setLogLevel(DziTask.log, debug ? "DEBUG" : "INFO");
        if (debug) Utils.setLogLevel(log, "DEBUG");

        DziParams.setDefaults((name) -> env.get(name.toUpperCase()));

        s3 = new S3Helper();
        log.info(pfx + "AWS profile: " + s3.getAWSProfile() + ", region: " + s3.getAWSRegion());

        cacheBucket = env.get("S3_CACHE_BUCKET_NAME");
        if (cacheBucket == null || cacheBucket.isEmpty()) {
            log.error(pfx + "S3_CACHE_BUCKET_NAME must be set");
            System.exit(1);
        }

        cacheLoc = Utils.ensureSeparators(env.get("S3_CACHE_LOC"), "/");

        log.info(pfx + "cache: s3://" + cacheBucket + "/" + cacheLoc);

        enableSqs = Utils.parseBool(env.get("ENABLE_SQS"), DEF_ENABLE_SQS);
        if (enableSqs) {

            enableSqsHeartbeat = Utils.parseBool(env.get("ENABLE_SQS_HEARTBEAT"), DEF_ENABLE_SQS_HEARTBEAT);
            
            String fqn = env.get("TILE_SERVICE_QUEUE_NAME");
            tileServiceQueueName = fqn != null ? fqn.trim() : null;
            log.info(pfx + "tile service queue name " + tileServiceQueueName);
            
            SqsClient client = null;
            if (enableSqsHeartbeat || tileServiceQueueName != null) {
                try {
                    var clientBuilder = SqsClient.builder();
                    clientBuilder.region(Region.of(AWS_REGION));
                    client = clientBuilder.build();
                } catch (Exception ex) {
                    log.error(pfx + "error creating SQS client: " + ex.getMessage());
                }
            }
            sqsClient = client;
            
            String fqu = null;
            if (sqsClient != null && tileServiceQueueName != null) {
                try {
                    fqu = sqsClient
                        .getQueueUrl(GetQueueUrlRequest.builder().queueName(tileServiceQueueName).build())
                        .queueUrl();
                    log.info(pfx + "forwarding queue URL " + fqu);
                } catch (Exception ex) {
                    log.error(pfx + "error getting URL for forwarding queue: " + ex.getMessage());
                }
            }
            tileServiceQueueUrl = fqu;
            
            if (sqsClient != null && enableSqsHeartbeat) {
                sqsHeartbeatThread = new Thread("SQS heartbeat") {
                        public void run() {
                            while (true) {
                                try {
                                    Thread.sleep(1000l * SQS_HEARTBEAT_PERIOD_SEC);
                                } catch (InterruptedException ex) {
                                    break;
                                }
                                try {
                                    for (var entry : sqsReceipts.entrySet()) {
                                        setVisibilityTimeout(entry.getKey(), entry.getValue());
                                    }
                                } catch (Exception ex) {
                                    log.error(pfx + "error in SQS heartbeat: " + ex.getMessage());
                                }
                            }
                        }
                    };
                sqsHeartbeatThread.setDaemon(true);
                sqsHeartbeatThread.start();
            } else {
                sqsHeartbeatThread = null;
            }
        } else {
            enableSqsHeartbeat = false;
            sqsClient = null;
            sqsHeartbeatThread = null;
            tileServiceQueueName = tileServiceQueueUrl = null;
            log.info(pfx + "SQS disabled");
        }
    }

    @Override
    public String handleRequest(SQSEvent event, Context context) {

        String pfx = DziTask.getVersion() + " ";

        var sqsRecords = event.getRecords();
        if (sqsRecords == null || sqsRecords.size() == 0) {
            log.warn(pfx + "SQS message has no records");
        }

        int numProcessed = 0, numEvents = 0, numCancellations = 0, numRejected = 0, numForwarded = 0;
        for (var sqsRecord : sqsRecords) {

            String receiptHandle = sqsRecord.getReceiptHandle();
            String queueARN = sqsRecord.getEventSourceArn();
            String queueName = getQueueNameFromArn(queueARN, false);
            String receiptMsg = "handling SQS receipt " + receiptHandle.substring(0, 8) + " on queue " + queueName;

            try {

                if (debug) log.debug(pfx + receiptMsg);

                if (enableSqsHeartbeat) {
                    setVisibilityTimeout(receiptHandle, queueARN);
                    sqsReceipts.put(receiptHandle, queueARN);
                }

                String sqsJSON = sqsRecord.getBody();
                JsonObject wrapper = Json.createReader(new StringReader(sqsJSON)).readObject();
                String s3JSON = wrapper.containsKey("Message") ? wrapper.getString("Message")
                    : wrapper.containsKey("Records") ? sqsJSON : null;
                
                if (s3JSON == null) {
                    log.error(pfx + "unrecognized SQS message: " + sqsJSON);
                    continue;
                }
                
                S3EventNotification s3EventNotification = null;
                try {
                    s3EventNotification = S3EventNotification.fromJson(s3JSON);
                } catch (Exception ex) {
                    log.error(pfx + "error parsing SQS message as S3 event notification: " + s3JSON, ex);
                    continue;
                }
                
                var records = s3EventNotification.getRecords();
                if (records == null || records.size() == 0) {
                    log.warn(pfx + "S3 event notification has no records: " + s3JSON);
                    continue;
                }
                
                for (S3EventNotificationRecord s3Record : records) {
                    
                    numEvents++;
                    
                    String bucket = s3Record.getS3().getBucket().getName();
                    String key = URLDecoder.decode(s3Record.getS3().getObject().getKey().replace('+', ' '),
                                                   StandardCharsets.UTF_8);
                    
                    String rdrUrl = "s3://" + bucket + "/" + key;
                    String hashCode = DziParams.getHashCode(rdrUrl);
                    String cacheDir = cacheLoc + hashCode;
                    String cacheUrl = "s3://" + cacheBucket + "/" + cacheDir;
                    
                    pfx = DziTask.getVersion() + " [" + rdrUrl + "] ";
                    
                    if (DziTask.filterUrl(rdrUrl)) {
                        try {
                            String s3Event = s3Record.getEventName();
                            if (DziTask.debug) log.debug(pfx + s3Event); 
                            if (s3Event.contains("ObjectCreated")) {
                                DziParams params = null;
                                try {
                                    params = new DziParams(rdrUrl);
                                } catch (IllegalArgumentException iae) {
                                    log.error(pfx + "unsupported input file: " + iae.getMessage());
                                    numRejected++;
                                }
                                DziTask task = null;
                                if (params != null) {
                                    var archiver = new S3Archiver(cacheBucket, cacheDir, s3);
                                    archiver.setLogger(log, pfx);
                                    task = new DziTask(params, archiver, cacheDir, s3);
                                }
                                try {
                                    if (task != null) {
                                        task.preRun();
                                    }
                                } catch (IllegalArgumentException iae) {
                                    log.error(pfx + "unsupported input file: " + iae.getMessage());
                                    numRejected++;
                                    task = null;
                                    if ((iae instanceof DziTask.OversizeFileException) &&
                                        sqsClient != null && tileServiceQueueUrl != null) {
                                        log.info(pfx + "forwarding to " + tileServiceQueueName);
                                        try {
                                            var req = SendMessageRequest.builder()
                                                .queueUrl(tileServiceQueueUrl)
                                                .messageBody(params.toJson())
                                                .build();
                                            sqsClient.sendMessage(req);
                                            numForwarded++;
                                        } catch (Exception ex) {
                                            log.error(pfx + "error forwarding to " + tileServiceQueueName + ": " +
                                                      ex.getMessage(), ex);
                                        }
                                    }
                                }
                                if (task != null) {
                                    task.run(); //let exceptions percolate out
                                    numProcessed++;
                                }
                            } else if (s3Event.contains("ObjectRemoved")) {
                                if (s3.doesPrefixExist(cacheBucket, cacheDir + "/")) {
                                    log.info(pfx + "deleting cache");
                                    int num = s3.deleteObjectsRecursive(cacheBucket, cacheDir);
                                    log.info(pfx + " deleted " + num + " files");
                                    numProcessed++;
                                } else {
                                    log.info(pfx + "no cache");
                                }
                            } else {
                                log.info(pfx + "ignoring " + s3Event);
                            }
                        } catch (CancellationException ex) {
                            //don't throw: if all the errors in this batch are unrecoverable then we want the message
                            //to *not* stay in the SQS input queue where it will get retried
                            log.error(pfx + "task cancelled");
                            numCancellations++;
                        } catch (Exception ex) {
                            if (ex instanceof IOException && ex.getMessage().contains("no compatible image reader")) {
                                //don't throw
                                log.error(pfx + "task error: " + ex.getMessage());
                            } else {
                                //do throw: the SQS message will stay in the input queue and get retried
                                //if this happens enough times it will eventually get shunted to the DLQ
                                log.error(pfx + "task error", ex);
                                throw new RuntimeException(ex);
                            }
                        } catch (LinkageError ex) {
                            //do throw, see above
                            log.error(pfx + "linkage error, provenance " + Utils.fromWhence(ex), ex);
                            throw ex;
                        }
                    } else {
                        log.info(pfx + "unsupported input URL");
                        numRejected++;
                    }
                }
            } finally {
                if (debug) log.debug(pfx + "done " + receiptMsg);
                if (enableSqsHeartbeat) {
                    sqsReceipts.remove(receiptHandle);
                }
            }
        }

        return "processed " + numProcessed + " images or deletions for " + numEvents + " S3 events, " +
            numCancellations + " tasks cancelled, " + numRejected + " rejected, " + numForwarded + " forwarded";
    }

    private void setVisibilityTimeout(String receiptHandle, String queueARN) {
        if (sqsClient == null) {
            throw new IllegalStateException("SQS client required to set visibility timeout");
        }
        String pfx = DziTask.pfx;
        try {
            String queueUrl = queueArnToUrl.compute(queueARN, (key, url) -> {
                    if (url != null) return url;
                    String queueName = getQueueNameFromArn(queueARN, true);
                    return sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
                });
            if (debug) log.debug(pfx + "setting visibility timeout " + SQS_VISIBILITY_TIMEOUT_SEC + "s " +
                                 "for SQS receipt " + receiptHandle.substring(0, 8) + " on queue " + queueUrl);
            var req = ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout(SQS_VISIBILITY_TIMEOUT_SEC)
                .build();
            sqsClient.changeMessageVisibility(req);
        } catch (Exception ex) {
            log.error(pfx + "error setting SQS message visibility timeout: " + ex.getMessage());
        }
    }

    private String getQueueNameFromArn(String arn, boolean throwOnError) {
        int sep = arn != null ? Math.max(arn.lastIndexOf(':'), arn.lastIndexOf('/')) : -1;
        if (sep >= 0 && sep < arn.length() - 1) {
            return arn.substring(sep + 1);
        } else if (throwOnError) {
            throw new IllegalArgumentException("failed to get SQS queue name from ARN " + arn);
        } else {
            return "ARN=" + arn;
        }
    }
}
