package jpl.mipl.mars.tile_service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import java.lang.ref.SoftReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.StringReader;

import java.time.Duration;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonString;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.binary.Base64;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import org.slf4j.Logger;

/**
 *
 * @author Stirling Algermissen
 * @author Marsette Vona
 */
public class S3Helper implements AutoCloseable {
    
    public enum UseAWSHeader { prefer, always, never };

    public static final int DEF_CACHE_SIZE = 64;
    public static final int DEF_MAX_CACHE_AGE_SEC = 60 * 15;

    private final S3Client client;
    private final S3Utilities utilities;
    private final S3Presigner presigner;

    private final String profile;
    private final String region;

    private static volatile int cacheSize = DEF_CACHE_SIZE;
    private static volatile long maxCacheAgeMS = DEF_MAX_CACHE_AGE_SEC * 1000L;
    
    private static class CacheEntry {

        public final SoftReference<S3Helper> s3;

        private volatile long expirationTimeMS;
        private volatile int refCount;

        public CacheEntry(S3Helper s3) {
            this.s3 = new SoftReference<S3Helper>(s3);
            touch();
            incrementRefCount();
        }

        public synchronized void touch() {
            expirationTimeMS = S3Helper.maxCacheAgeMS > 0 ? (System.currentTimeMillis() + S3Helper.maxCacheAgeMS) : -1;
        }

        public synchronized void incrementRefCount() {
            refCount++;
        }

        public synchronized void decrementRefCount() {
            if (refCount > 0) {
                refCount--;
                //don't immediately close() if refCount == 0 now
                //rather, wait for the next cleanupCache() once this entry becomes expired
            }
        }

        public synchronized boolean isValid() {
            return s3.get() != null;
        }

        public synchronized boolean inUse() {
            return refCount > 0;
        }

        public synchronized boolean isExpired() {
            return (expirationTimeMS >= 0) && (System.currentTimeMillis() > expirationTimeMS);
        }

        public synchronized void close() {
            var s3 = this.s3.get();
            if (s3 != null) {
                s3.closeImpl();
            }
            refCount = 0;
        }
    }

    private volatile boolean closed; 
    private CacheEntry cacheEntry;

    //synchronization order when holding multiple locks is always cache before entry, entry before s3
    private static final LinkedHashMap<String, CacheEntry> cache =
        new LinkedHashMap<String, CacheEntry>(DEF_CACHE_SIZE, 0.75f, /* accessOrder */ true);

    private static volatile Thread cacheReaperThread;

    private static Logger cacheLog;
    private static String cacheLogPfx;
    private static boolean debugCache;

    public static void setupCache(int maxSize, long maxAgeMS, Logger log, String pfx, boolean debug) {
        synchronized (cache) {
            
            cache.clear();

            cacheSize = maxSize;
            maxCacheAgeMS = maxAgeMS;
            cacheLog = log;
            cacheLogPfx = pfx;
            debugCache = debug;

            if (maxSize > 0 && maxAgeMS > 0 && cacheReaperThread == null) {
                cacheReaperThread = new Thread("S3 credential reaper") {
                        public void run() {
                            while (true) {
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException ex) {
                                    break;
                                }
                                try {
                                    synchronized (cache) {
                                        if (cacheSize <= 0 || maxCacheAgeMS <= 0) {
                                            if (debugCache && cacheLog != null) {
                                                cacheLog.debug(pfx + "S3 credential cache reaper thread stopped");
                                            }
                                            cacheReaperThread = null;
                                            break;
                                        }
                                        cleanupCache();
                                    }
                                } catch (Exception ex) {
                                    if (cacheLog != null) {
                                        cacheLog.error(pfx + "S3 credential cache error reaping credentials: " +
                                                       ex.getMessage(), ex);
                                    }
                                }
                            }
                        }
                    };
                cacheReaperThread.setDaemon(true);
                cacheReaperThread.start();
                if (debugCache && cacheLog != null) {
                    cacheLog.debug(pfx + "S3 credential cache reaper thread started");
                }
            }
        }
    }

    public static boolean hasCache() {
        synchronized (cache) {
            return cacheSize > 0 && maxCacheAgeMS > 0;
        }
    }

    public static void destroyCache(long maxWaitMS) throws InterruptedException {
        setupCache(0, 0, null, "", false);
        if (maxWaitMS > 0) {
            long deadline = System.currentTimeMillis() + maxWaitMS;
            while (cacheReaperThread != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
        }
    }

    public static void destroyCache() throws InterruptedException {
        destroyCache(2000);
    }
        
    private static void cleanupCache() {
        synchronized (cache) {
            int nc = cache.size();
            int nr = 0;
            for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
                var cached = it.next().getValue();
                synchronized (cached) {
                    if (!cached.isValid() || cached.isExpired()) {
                        if (!cached.inUse()) {
                            //1) if invalid close() is harmless but has basically no effect (does zero ref count)
                            //if expired then the entry may or may not still be in use
                            //2) it could be in use if there is a long running task
                            //   or if there were multiple overlapping tasks with at least one still ongoing
                            //   if it's still in use then we shouldn't close it, rather, the task(s) should close it
                            //   and when they do and its ref count hits zero the underlying S3Helper will be closed
                            //   (if there are any bugs in that process the S3Helper might not ever be explicitly closed
                            //    but that's not very bad really, closing the AWS s3 client (vs specific transactions)
                            //    is recommended but basically optional, it will get garbage collected like normal and
                            //    the idle connection reaper will handle freeing any of its connection pool resources)
                            //3) if expired and not in use then it should be redundant to close() here but eh
                            cached.close();
                        }
                        it.remove();
                        nr++;
                    }
                }
            }
            if (nr > 0 && debugCache && cacheLog != null) {
                cacheLog.debug(cacheLogPfx + "S3 credential cache culled " + nr + "/" + nc + " stale cache entries");
            }
        }
    }

    public static S3Helper addToCache(String key, Supplier<S3Helper> factory) {
        if (cacheSize <= 0) {
            return factory.get();
        }
        synchronized (cache) {
            var alreadyCached = getCached(key);
            if (alreadyCached != null) {
                if (debugCache && cacheLog != null) {
                    cacheLog.debug(cacheLogPfx + "using cached end-user S3 credentials");
                }
                return alreadyCached;
            }
            if (cache.size() >= cacheSize) {
                cleanupCache();
                if (cache.size() >= cacheSize) {
                    CacheEntry lru = null;
                    String lruKey = null;
                    for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
                        var entry = it.next();
                        var cached = entry.getValue();
                        if (!cached.inUse()) {
                            lru = cached;
                            lruKey = entry.getKey();
                            break;
                        }
                    }
                    if (lru != null) {
                        synchronized (lru) {
                            if (!lru.inUse()) {
                                if (debugCache && cacheLog != null) {
                                    cacheLog.debug(cacheLogPfx + "S3 credential cache culling LRU cache entry");
                                }
                                lru.close();
                                boolean removed = cache.remove(lruKey) != null;
                                if (debugCache && cacheLog != null && !removed) {
                                    cacheLog.debug(cacheLogPfx + "S3 credential cache LRU cache entry remove failed");
                                }
                            } else {
                                lru = null;
                                lruKey = null;
                            }
                        }
                    } 
                    if (lru == null && debugCache && cacheLog != null) {
                        cacheLog.debug(cacheLogPfx + "S3 credential cache full and all entries in use");
                    }
                }
            }
            var s3 = factory.get();
            if (cache.size() < cacheSize) {
                s3.cacheEntry = new CacheEntry(s3);
                cache.put(key, s3.cacheEntry);
                if (debugCache && cacheLog != null) {
                    cacheLog.debug(cacheLogPfx + "S3 credential cache added end-user credentials to cache");
                }
            } else if (cacheLog != null) {
                cacheLog.warn(cacheLogPfx + "S3 credential cache full, not adding end-user credentials to cache");
            }
            return s3;
        }
    }

    public static S3Helper getCached(String key) {
        if (cacheSize > 0) {
            synchronized (cache) {
                if (cache.containsKey(key)) {
                    var cached = cache.get(key);
                    synchronized (cached) {
                        var s3 = cached.s3.get();
                        if (s3 != null) {
                            cached.touch();
                            cached.incrementRefCount();
                            if (debugCache && cacheLog != null) {
                                cacheLog.debug(cacheLogPfx + "S3 credential cache using cached end-user credentials");
                            }
                            return s3;
                        }
                    }
                    if (debugCache && cacheLog != null) {
                        cacheLog.debug(cacheLogPfx + "S3 credential cache not using expired end-user credentials");
                    }
                    cleanupCache();
                }
            }
            if (debugCache && cacheLog != null) {
                cacheLog.debug(cacheLogPfx + "S3 credential cache end-user credentials not cached");
            }
        }
        return null;
    }

    public static S3Helper getRequestS3(HttpServletRequest request, UseAWSHeader useAWSHeader, Logger log, String pfx) {
        String headerJsonStr = null;
        JsonObject awsSessionConfig = null;
        if (useAWSHeader != UseAWSHeader.never) {
            String err = null;
            String hdr = request.getHeader("X-AWS");
            if (hdr != null) {
                try {
                    headerJsonStr = new String(Base64.decodeBase64(hdr), "UTF-8");
                    var cachedClientS3 = getCached(headerJsonStr);
                    if (cachedClientS3 != null) {
                        if (debugCache && cacheLog != null) {
                            cacheLog.debug(cacheLogPfx + "using cached end-user S3 credentials");
                        }
                        return cachedClientS3;
                    }
                    awsSessionConfig = Json.createReader(new StringReader(headerJsonStr)).readObject();
                } catch (Exception ex) {
                    err = "error parsing X-AWS header: " + ex.getMessage();
                }
            } else {
                err = "missing X-AWS header";
            }
            if (err != null) {
                if (useAWSHeader == UseAWSHeader.always) {
                    throw new IllegalArgumentException(err);
                }
                else if (log != null) log.debug(pfx + err);
            }
        }

        if (useAWSHeader == UseAWSHeader.always ||
            (useAWSHeader == UseAWSHeader.prefer && awsSessionConfig != null)) {
            if (log != null) log.debug(pfx + "using end-user S3 credentials");
            final JsonObject fasc = awsSessionConfig;
            return addToCache(headerJsonStr, () -> new S3Helper(fasc)); //null -> IllegalArgumentException
        }

        return null;
    }

    public S3Helper(JsonObject awsSessionConfig) {

        profile = null;

        try {

            if (awsSessionConfig == null) {
                throw new IllegalArgumentException("credentials missing");
            }

            AwsSessionCredentials sessionCredentials =
                AwsSessionCredentials.create(getConfigParam(awsSessionConfig, "id"),
                                             getConfigParam(awsSessionConfig, "secret"),
                                             getConfigParam(awsSessionConfig, "session"));

            region = getConfigParam(awsSessionConfig, "region");
            Region awsRegion = Region.of(region);

            var clientBuilder = S3Client.builder();
            var utilitiesBuilder = S3Utilities.builder();
            var presignerBuilder = S3Presigner.builder();
            
            clientBuilder.httpClientBuilder(ApacheHttpClient.builder().useIdleConnectionReaper(true));
            
            clientBuilder.region(awsRegion);
            utilitiesBuilder.region(awsRegion);
            presignerBuilder.region(awsRegion);

            var creds = StaticCredentialsProvider.create(sessionCredentials);
            clientBuilder.credentialsProvider(creds);
            presignerBuilder.credentialsProvider(creds);
            
            client = clientBuilder.build();
            utilities = utilitiesBuilder.build();
            presigner = presignerBuilder.build();

        } catch (Exception ex) {
            throw new IllegalArgumentException("error creating S3 client with AWS session credentials: " +
                                               ex.getMessage());
        }
    }

    private static String getConfigParam(JsonObject config, String name) {
        JsonString ret = config.getJsonString(name);
        if (ret == null) {
            throw new IllegalArgumentException("missing parameter: " + name);
        }
        return ret.getString();
    }

    public S3Helper(String profile, String region) {

        this.profile = profile = profile != null && profile.trim().length() > 0 ? profile.trim() : "null";
        this.region = region = region != null && region.trim().length() > 0 ? region.trim() : "us-gov-west-1"; //"null";

        try {
            var clientBuilder = S3Client.builder();
            var utilitiesBuilder = S3Utilities.builder();
            var presignerBuilder = S3Presigner.builder();

            //default http connection pool size is 50
            //if all connections are in use new requests will get queued up to a default of 10k pending connections
            //pending connections which cannot be serviced within some timeout (I think about 40s by default)
            //throw SdkClientException caused by ConnectionPoolTimeoutException
            //https://github.com/aws/aws-sdk-java-v2/blob/master/docs/LaunchChangelog.md#13-sdk-client-configuration
            //https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/client-configuration-http.html

            //we should not have any codepaths that don't actively close S3 connections, even in error scenarios
            //however, should connections be leaked, there are two remediations
            //one is the idle connection reaper in the AWS api, enabled below
            //the other is if we ever do get a ConnectionPoolTimeoutException in TileService
            //we have code there that will replace the S3 client in that case

            //switch these to test error scenarios
            clientBuilder.httpClientBuilder(ApacheHttpClient.builder().useIdleConnectionReaper(true));
            //clientBuilder.httpClientBuilder(ApacheHttpClient.builder().maxConnections(10));

            if (!region.toLowerCase().equals("null")) {
                Region awsRegion = Region.of(region);
                clientBuilder.region(awsRegion);
                utilitiesBuilder.region(awsRegion);
                presignerBuilder.region(awsRegion);
            }
            
            if (profile != null && !profile.toLowerCase().equals("null")) {
                var creds = ProfileCredentialsProvider.create(profile);
                clientBuilder.credentialsProvider(creds);
                presignerBuilder.credentialsProvider(creds);
            }
            
            client = clientBuilder.build();
            utilities = utilitiesBuilder.build();
            presigner = presignerBuilder.build();

        } catch (Exception ex) {
            throw new RuntimeException("error initializing AWS S3 client, profile=" + profile +
                                       ", region=" + region + ": " + ex.getMessage());
        }
    }

    public S3Helper(String profile) {
        this(profile, null);
    }

    public S3Helper() {
        this(null, null);
    }

    private S3Client checkClient() {
        if (closed) {
            throw new IllegalStateException("S3 client already closed");
        }
        if (cacheEntry != null) {
            cacheEntry.touch();
        }
        return client;
    }

    public void close() {
        if (cacheEntry != null) {
            cacheEntry.decrementRefCount();
        } else {
            closeImpl();
        }
    }

    public synchronized void closeImpl() {
        if (!closed) {
            closed = true;
            client.close();
            presigner.close();
        }
    }

    public static boolean isS3Url(String url) {

        if (url == null) {
            return false;
        }

        url = url.toLowerCase().replace("\\", "/");

        if (url.startsWith("s3://")) {
            return true;
        }

        //http[s]://BUCKET.s3-REGION.amazonaws.com/KEY
        //http[s]://BUCKET.s3.amazonaws.com/KEY
        //http[s]://s3-REGION.amazonaws.com/BUCKET/KEY
        //http[s]://s3.amazonaws.com/BUCKET/KEY

        if (url.startsWith("http://")) {
            url = url.substring(7);
        } else if (url.startsWith("https://")) {
            url = url.substring(8);
        } else {
            return false;
        }

        int slash = url.indexOf("/");
        if (slash <= 0) {
            return false;
        }

        String host = url.substring(0, slash);
        return host.endsWith("amazonaws.com") && host.contains("s3");
    }

    public static String getS3Url(String url) {
        return getS3Url(new AmazonS3URI(url));
    }

    public static String getS3Url(AmazonS3URI s3Uri) {
        return getS3Url(s3Uri.getBucket(), s3Uri.getKey());
    }

    public static String getS3Url(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    //recent versions of the AWS SDK give cryptic 403 errors if bucket is not a proper bucket name, e.g.
    //> S3Exception: The request signature we calculated does not match the signature you provided.
    //> Check your key and signing method. 
    //one of the ways bucket can not be a proper bucket name is if it actually contains some fragment of a key prefix
    //e.g. bucket="actual-bucket-name/some/stuff"
    //https://github.com/aws/aws-sdk-java-v2/issues/1767#issuecomment-609490874
    //and some of our legacy workflows, particularly for testing, actually do that
    //so check for that and move the prefix fragment, if any, to the start of key
    //we do this in a two-step process in each method that accepts bucket/key args
    //key = ensureProperKey(bucket, key)  //copies any prefix from end of bucket to start of key
    //bucket = ensureProperBucket(bucket) //removes any prefix from end of bucket
    public static String ensureProperKey(String bucket, String key) {
        int sep = bucket.indexOf("://"); //also check for case that bucket starts with e.g. "s3://"
        int start = sep >= 0 ? sep + 3 : 0;
        int slash = bucket.indexOf('/', start);
        if (slash >= 0) key = bucket.substring(slash + 1) + "/" + key;
        return key;
    }

    public static String ensureProperBucket(String bucket) {
        int sep = bucket.indexOf("://");
        int start = sep >= 0 ? sep + 3 : 0;
        int slash = bucket.indexOf('/', start);
        if (start > 0 || slash >= start) bucket = bucket.substring(start, slash >= start ? slash : bucket.length());
        return bucket;
    }

    public String getAWSRegion() {
        return region;
    }

    public String getAWSProfile() {
        return profile;
    }

    public String getS3UrlAsHttps(String bucket, String key) {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        return utilities.getUrl(GetUrlRequest.builder().bucket(bucket).key(key).build()).toString();
    }

    //https://stackoverflow.com/a/59156981/4970315
    public String getPresignedS3UrlAsHttps(String bucket, String key) {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        checkClient();
        var req = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(10))
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build();
        return presigner.presignGetObject(req).url().toString();
    }

    //see caution on getStream(String, String)
    public ResponseInputStream<GetObjectResponse> getObject(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getObject(s3Uri.getBucket(), s3Uri.getKey());
    }

    //see caution on getStream(String, String)
    public ResponseInputStream<GetObjectResponse> getObject(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        return checkClient().getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    //see caution on getStream(String, String)
    public InputStream getStream(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getStream(s3Uri.getBucket(), s3Uri.getKey());
    }

    //caution, the returned stream must be closed, even if errors occur while processing it
    //otherwise it will leak one of the limited number of slots in the http connection pool
    //managed inside the AWS S3 client
    //and that will eventually lead to exceptions like this:
    //software.amazon.awssdk.core.exception.SdkClientException: Unable to execute HTTP request:
    //Timeout waiting for connection from pool ... caused by: org.apache.http.conn.ConnectionPoolTimeoutException:
    //Timeout waiting for connection from pool
    public InputStream getStream(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return checkClient().getObject(req, ResponseTransformer.toInputStream());
    }

    public byte[] getRange(String url, long startByte, long endByte) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getRange(s3Uri.getBucket(), s3Uri.getKey(), startByte, endByte);
    }

    public byte[] getRange(String bucket, String key, long startByte, long endByte) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        if (endByte < startByte) {
            throw new IllegalArgumentException("invalid range [" + startByte + ", " + endByte + "]");
        }
        var req = GetObjectRequest.builder().bucket(bucket).key(key)
            .range("bytes=" + startByte + "-" + endByte)
            .build();
        return checkClient().getObject(req, ResponseTransformer.toBytes()).asByteArray();
    }

    public int getRange(String url, long startByte, byte[] buf, int off, int len) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getRange(s3Uri.getBucket(), s3Uri.getKey(), startByte, buf, off, len);
    }

    public int getRange(String bucket, String key, long startByte, byte[] buf, int off, int len) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        long endByte = startByte + len - 1;
        if (endByte < startByte) {
            throw new IllegalArgumentException("invalid range [" + startByte + ", " + endByte + "]");
        }
        var req = GetObjectRequest.builder().bucket(bucket).key(key)
            .range("bytes=" + startByte + "-" + endByte)
            .build();
        try (var str = checkClient().getObject(req, ResponseTransformer.toInputStream())) {
            for (int nb = 0, nr = 0; nb < len; nb += nr) {
                nr = str.read(buf, off + nb, len - nb);
                if (nr < 0) {
                    return nb;
                }
            }
            return len;
        }
    }

    public long getRange(String url, long startByte, byte[][] bufs, long max) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getRange(s3Uri.getBucket(), s3Uri.getKey(), startByte, bufs, max);
    }

    public long getRange(String bucket, String key, long startByte, byte[][] bufs, long max) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        long len = 0;
        for (int i = 0; i < bufs.length && len < max; i++) {
            if (bufs[i] != null) {
                len += bufs[i].length;
            }
        }
        if (len > max) {
            len = max;
        }
        long endByte = startByte + len - 1;
        if (endByte < startByte) {
            throw new IllegalArgumentException("invalid range [" + startByte + ", " + endByte + "]");
        }
        var req = GetObjectRequest.builder().bucket(bucket).key(key)
            .range("bytes=" + startByte + "-" + endByte)
            .build();
        try (var str = checkClient().getObject(req, ResponseTransformer.toInputStream())) {
            for (int nb = 0, nr = 0, i = 0, off = 0; nb < len; nb += nr, off += nr) {
                int left = bufs[i] != null ? bufs[i].length - off : 0;
                if (left == 0) {
                    i++;
                    off = 0;
                    nr = 0;
                } else {
                    long rb = len - nb;
                    if (rb > left) {
                        rb = left;
                    }
                    nr = str.read(bufs[i], off, (int)rb);
                    if (nr < 0) {
                        return nb;
                    }
                }
            }
            return len;
        }
    }

    public byte[] getFile(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getFile(s3Uri.getBucket(), s3Uri.getKey());
    }

    public byte[] getFile(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return checkClient().getObject(req, ResponseTransformer.toBytes()).asByteArray();
    }

    public String getTextFile(String url) throws Exception {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getTextFile(s3Uri.getBucket(), s3Uri.getKey());
    }

    public String getTextFile(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        return new String(getFile(bucket, key), "UTF-8");
    }

    public void putFile(String url, byte[] data) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        putFile(s3Uri.getBucket(), s3Uri.getKey(), data);
    }

    public void putFile(String bucket, String key, byte[] data) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = PutObjectRequest.builder().bucket(bucket).key(key).build();
        checkClient().putObject(req, RequestBody.fromBytes(data));
    }

    public void putFile(String url, String data) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        putFile(s3Uri.getBucket(), s3Uri.getKey(), data);
    }

    public void putFile(String bucket, String key, String data) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = PutObjectRequest.builder().bucket(bucket).key(key).build();
        checkClient().putObject(req, RequestBody.fromString(data));
    }

    public void putFile(String url, File data) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        putFile(s3Uri.getBucket(), s3Uri.getKey(), data);
    }

    public void putFile(String bucket, String key, File data) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = PutObjectRequest.builder().bucket(bucket).key(key).build();
        checkClient().putObject(req, RequestBody.fromFile(data));
    }

    public long getObjectLastModified(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getObjectLastModified(s3Uri.getBucket(), s3Uri.getKey());
    }

    public long getObjectLastModified(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = HeadObjectRequest.builder().bucket(bucket).key(key).build();
        HeadObjectResponse resp = checkClient().headObject(req);
        try {
            return resp.sdkHttpResponse().isSuccessful() ? resp.lastModified().toEpochMilli() : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    public String getObjectETag(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getObjectETag(s3Uri.getBucket(), s3Uri.getKey());
    }

    public String getObjectETag(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        try {
            var req = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            HeadObjectResponse resp = checkClient().headObject(req);
            return resp.sdkHttpResponse().isSuccessful() ? resp.eTag() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    public long getObjectSize(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return getObjectSize(s3Uri.getBucket(), s3Uri.getKey());
    }

    public long getObjectSize(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        var req = HeadObjectRequest.builder().bucket(bucket).key(key).build();
        HeadObjectResponse resp = checkClient().headObject(req);
        try {
            return resp.sdkHttpResponse().isSuccessful() ? resp.contentLength() : -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    public boolean doesObjectExist(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return doesObjectExist(s3Uri.getBucket(), s3Uri.getKey());
    }

    public boolean doesObjectExist(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        try {
            //note: success of HeadObject should also indicate valid read access
            //https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html
            //there used to be a getObjectMetadata() operation but that was replaced by HeadObject
            //https://github.com/aws/aws-sdk-java-v2/blob/master/docs/LaunchChangelog.md#411-s3-operation-migration
            //and it was at least suggested that getObjectMetadata() could be used to verify read access
            //https://stackoverflow.com/a/20383018
            var req = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            HeadObjectResponse resp = checkClient().headObject(req);
            return resp.sdkHttpResponse().isSuccessful();
        } catch (NoSuchBucketException ex) {
            return false;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() >= 400 && ex.statusCode() < 500) {
                //get here e.g. if insufficient read permissions
                return false;
            }
            throw ex;
        }
    }

    public boolean doesPrefixExist(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return doesPrefixExist(s3Uri.getBucket(), s3Uri.getKey());
    }

    public boolean doesPrefixExist(String bucket, String prefix) throws IOException {
        prefix = ensureProperKey(bucket, prefix);
        bucket = ensureProperBucket(bucket);
        return listObjects(bucket, prefix, 1).size() > 0;
    }

    public List<S3Object> listObjects(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return listObjects(s3Uri.getBucket(), s3Uri.getKey());
    }

    public List<S3Object> listObjects(String bucket, String prefix) throws IOException {
        return listObjects(bucket, prefix, null);
    }

    public List<S3Object> listObjects(String bucket, String prefix, Logger log) throws IOException {
        return listObjects(bucket, prefix, log, 0);
    }

    public List<S3Object> listObjects(String bucket, String prefix, int max) throws IOException {
        return listObjects(bucket, prefix, null, max);
    }

    public List<S3Object> listObjects(String bucket, String prefix, Logger log, int max) throws IOException {
        return listObjects(bucket, prefix, null, max, true, null);
    }

    public List<S3Object> listObjects(String bucketArg, String prefixArg, Logger log, int max, boolean recursive,
                                      Predicate<String> filter) throws IOException {
        final String prefix = ensureProperKey(bucketArg, prefixArg);
        final String bucket = ensureProperBucket(bucketArg);
        try {

            if (log != null) {
                log.debug("listing objects recursively under s3://" + bucket + "/" + prefix);
            }

            var builder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
            if (max > 0) {
                builder = builder.maxKeys(max);
            }
            if (!recursive) {
                builder = builder.delimiter("/");
            }

            ListObjectsV2Response listResp = checkClient().listObjectsV2(builder.build());
            
            var objects =
                new ArrayList<S3Object>(listResp.contents().stream()
                                        .filter(obj -> filter == null || filter.test("s3://" + bucket + obj.key()))
                                        .collect(Collectors.toList()));

            if (log != null) {
                log.debug("listed " + objects.size() + " objects" + (listResp.isTruncated() ? " (truncated)" : ""));
            }

            int numTruncated = 0;
            while (listResp.isTruncated()) {
                numTruncated++;
                if (log != null) {
                    log.debug("continuing truncated listing...");
                }
                listResp = checkClient().listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                                                       .continuationToken(listResp.nextContinuationToken())
                                                       .build());
                int sizeWas = objects.size();
                objects.addAll(listResp.contents().stream()
                               .filter(obj -> filter == null || filter.test("s3://" + bucket + obj.key()))
                               .collect(Collectors.toList()));
                if (log != null) {
                    log.debug("listed " + (objects.size() - sizeWas) + " more objects");
                }
            }

            if (numTruncated > 0 && log != null) {
                log.debug(numTruncated + " truncated listings, " + objects.size() + " total objects");
            }

            return objects;

        } catch (S3Exception ex) {
            if (ex.statusCode() >= 400 && ex.statusCode() < 500) {
                //get here e.g. if no objects under prefix
                //or insufficient read permissions
                if (log != null) {
                    log.warn("client error listing objects, status code " + ex.statusCode());
                }
                return new ArrayList<S3Object>();
            }
            if (log != null) {
                log.warn("non-client error listing objects, status code " + ex.statusCode() + ": " + ex.getMessage());
            }
            throw ex;
        }
    }

    public void deleteObject(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        deleteObject(s3Uri.getBucket(), s3Uri.getKey());
    }

    public void deleteObject(String bucket, String key) throws IOException {
        key = ensureProperKey(bucket, key);
        bucket = ensureProperBucket(bucket);
        checkClient().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public int deleteObjectsRecursive(String url) throws IOException {
        AmazonS3URI s3Uri = new AmazonS3URI(url);
        return deleteObjectsRecursive(s3Uri.getBucket(), s3Uri.getKey());
    }

    public int deleteObjectsRecursive(String bucket, String prefix) throws IOException {
        return deleteObjectsRecursive(bucket, prefix, null);
    }

    public int deleteObjectsRecursive(String bucket, String prefix, Logger log) throws IOException {
        return deleteObjectsRecursive(bucket, prefix, log, false);
    }

    public int deleteObjectsRecursive(String bucket, String prefix, Logger log, boolean dryRun) throws IOException {
        return deleteObjectsRecursive(bucket, prefix, log, dryRun, false);
    }
        
    public int deleteObjectsRecursive(String bucket, String prefix, Logger log, boolean dryRun, boolean throwOnError)
        throws IOException {
        return deleteObjectsRecursive(bucket, prefix, log, dryRun, false, null);
    }

    public int deleteObjectsRecursive(String bucket, String prefix, Logger log, boolean dryRun, boolean throwOnError,
                                      Predicate<String> filter) throws IOException {
        prefix = ensureProperKey(bucket, prefix);
        bucket = ensureProperBucket(bucket);

        if (log != null) {
            log.debug("deleting objects " + (dryRun ? "(dryrun) " : "") +
                      "recursively under s3://" + bucket + "/" + prefix);
            log.debug("listing objects...");
        }

        var objects = listObjects(bucket, prefix, log, 0, true, filter).stream()
            .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
            .collect(Collectors.toList());

        int no = objects.size(); //num objects
        int nd = 0; //num actually deleted

        if (log != null) {
            log.debug("collected " + no + " object identifiers to delete");
            if (no > 0) {
                log.debug("first object identifier: " + objects.get(0));
            }
            if (no > 1) {
                log.debug("last object identifier: " + objects.get(no - 1));
            }
        }

        if (!dryRun) {

            int maxBatch = 1000;

            int nb = (int)Math.ceil(((float)no) / maxBatch); //num batches
            int np = 0; //num processed
            int ne = 0; //num errors
            var batch = new ArrayList<ObjectIdentifier>(maxBatch);

            if (log != null) {
                log.debug("deleting " + no + " objects" +
                          (nb > 1 ? (" in " + nb + " batches of up to " + maxBatch) : "") + "...");
            }

            for (int bs = Math.min(no - np, maxBatch); np < no; np += bs, bs = Math.min(no - np, maxBatch)) {

                if (log != null) {
                    log.debug("deleting batch of " + bs + " objects...");
                }

                batch.clear();
                for (var it = objects.listIterator(np); it.hasNext() && batch.size() < bs; ) {
                    batch.add(it.next());
                }
                bs = batch.size(); //should be redundant

                var del = Delete.builder().objects(batch).build();

                var delResp = checkClient()
                    .deleteObjects(DeleteObjectsRequest.builder().bucket(bucket).delete(del).build());

                int bd = delResp.deleted().size();
                int be = delResp.errors().size();
                nd += bd;
                ne += be;

                if (log != null) {
                    log.debug("deleted " + bd + " objects, " + be + " errors");
                }

                if (be > 0 && throwOnError) {
                    throw new IOException(be + " errors while deleting " + bs + " objects");
                }

                if (bd < bs && throwOnError) {
                    throw new IOException(bd + " of " + bs + " objects deleted");
                }
            }

            if (log != null) {
                log.debug("deleted " + nd + " of " + no + " objects, " + ne + " errors");
            }

        } else if (log != null) {
            log.debug("dry run, skipping delete");
        }
        
        return nd;
    }
}
