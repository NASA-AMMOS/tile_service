package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.lang.ref.SoftReference;
import javax.imageio.stream.ImageInputStreamImpl;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.mutable.MutableInt;

import org.slf4j.Logger;

/**
 * Facility for reading potentially large input images, possibly larger than can fit in memory.
 *
 * Source data is loaded in pages of a given size (e.g. 16M).  The last page may be smaller to match the total size of
 * the source file.  Pages are optionally cached in memory and/or on disk.  Cache sizes are bounded and use LRU
 * eviction.
 *
 * The source can either be a local file or a file on S3.
 *
 * For files small enough to fit entirely within the allowed cache, this implementation should only load the original
 * data once.  It will still have some overhead compared to a simple full monolithic load because a separate load
 * transaction will be incurred for each page.  If necessary this could be fixed by detecting that case and pre-filling
 * the cache based on a single load transaction.
 *
 * This is a synchronized MT safe implementation, which should not be a perf issue because VicarInputFile.readTile() is
 * synchronized anyway.
 *
 * @author Marsette Vona
 */
public class LRUCacheImageInputStream extends ImageInputStreamImpl {

    public final String url;

    public final String s3Bucket;
    public final String s3Key;

    public final S3Helper s3;

    public final boolean isHTTP;

    public final long pageBytes; //0 if caching is disabled, else positive
    public final int memCachePages; //mem cache capacity: 0 if mem cache disabled, else positive
    public final int diskCachePages; //disk cache capacity: 0 if disk cache disabled, else positive

    public final long srcSize; //size of s3 input file in bytes

    private String diskCacheDir; //null if disk cache disabled or already closed and deleted
    private long diskCacheOccupied;

    private byte[] tmpBuf = new byte[1];

    private LinkedHashMap<Integer, SoftReference<byte[]>> memCache; //page number -> buffer, null if no mem cache
    private LinkedHashMap<Integer, Path> diskCache; //page number -> file, null if no disk cache

    private int numReads;
    private long readBytes;

    private int pageLoads;

    private int memCacheHits;
    private int memCacheMisses;
    private int memCacheGCs;
    private int memCacheEvictions;

    private int diskCacheHits;
    private int diskCacheMisses;
    private int diskCacheEvictions;

    private int recycledBuffers;
    private int allocatedBuffers;

    private long allocatedBytes;

    private int numTransfers;
    private long transferredBytes;

    private int diskReads;
    private long diskReadBytes;

    private int diskWrites;
    private long diskWriteBytes;

    private Map<Integer, Integer> pageTransfers = new HashMap<Integer, Integer>();

    private Thread shutdownHook;

    public LRUCacheImageInputStream(String url, S3Helper s3, long pageBytes, int memCachePages, int diskCachePages,
                                    String diskCacheDir) throws IOException {

        this.url = url;

        if (S3Helper.isS3Url(url)) { //check first to catch http[s]://BUCKET.s3-REGION.amazonaws.com/KEY
            if (s3 == null) {
                throw new IllegalArgumentException("S3 helper null");
            }
            this.s3 = s3;
            this.isHTTP = false;
            var s3Uri = new AmazonS3URI(url);
            s3Bucket = s3Uri.getBucket();
            s3Key = s3Uri.getKey();
            srcSize = s3.getObjectSize(s3Bucket, s3Key);
        } else if (HTTPHelper.isHTTPUrl(url)) {
            this.s3 = null;
            this.isHTTP = true;
            s3Bucket = null;
            s3Key = null;
            srcSize = HTTPHelper.getSize(url);
        } else {
            if (url.toLowerCase().startsWith("file://")) {
                url = url.substring(7);
            }
            this.s3 = null;
            this.isHTTP = false;
            s3Bucket = null;
            s3Key = null;
            srcSize = (new File(url)).length();
        }

        this.pageBytes = pageBytes > 0 ? pageBytes : 0;

        this.memCachePages = memCachePages > 0 ? memCachePages : 0;

        if (diskCachePages > 0) {
            this.diskCacheDir = (diskCacheDir != null && diskCacheDir.trim().length() > 0) ? diskCacheDir.trim() : null;
            this.diskCachePages = (this.diskCacheDir != null) ? diskCachePages : 0;
        } else {
            this.diskCacheDir = null;
            this.diskCachePages = 0;
        }

        if (pageBytes > 0 && memCachePages > 0) {
            memCache = new LinkedHashMap<Integer, SoftReference<byte[]>>(memCachePages, 0.75f, /* accessOrder */ true);
        }

        if (pageBytes > 0 && diskCachePages > 0) {
            diskCache = new LinkedHashMap<Integer, Path>(diskCachePages, 0.75f, /* accessOrder */ true);
            //normally close() should be called
            //however if the app is quit (e.g. during testing/tuning) with ctrl-c, it may not be
            //but a shutdown hook does work in that situation
            //though shutdown hooks also may not work in other situations like kill -KILL
            String shName = "LRUCacheImageInputStream shutdown hook for " + diskCacheDir;
            shutdownHook = new Thread(shName) {
                    public void run() {
                        try {
                            deleteDiskCache();
                        } catch (Exception ex) {
                            System.err.println("error in " + shName + ": " + ex.getMessage());
                        }
                    }
                };
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    public LRUCacheImageInputStream(String url, long pageBytes, int memCachePages, int diskCachePages,
                                    String diskCacheDir) throws IOException {
        this(url, null, pageBytes, memCachePages, diskCachePages, diskCacheDir);
    }

    public LRUCacheImageInputStream(String url, long pageBytes, int memCachePages) throws IOException {
        this(url, null, pageBytes, memCachePages, 0, null);
    }

    @Override
    public long length() {
        return srcSize;
    }

    @Override
    synchronized public int read() throws IOException {
        return read(tmpBuf, 0, 1) > 0 ? Byte.toUnsignedInt(tmpBuf[0]) : -1;
    }

    @Override
    synchronized public int read(byte buf[], int off, int len) throws IOException {

        if (buf == null) {
            throw new IllegalArgumentException("null read buffer");
        }

        if (off < 0 || off >= buf.length) {
            throw new IllegalArgumentException("invalid read offset");
        }

        if (len < 0 || off + len > buf.length) {
            throw new IllegalArgumentException("invalid read length");
        }

        if (len == 0) {
            return 0;
        }

        if (streamPos >= srcSize) {
            return -1;
        }

        int nb = 0;
        if (memCache == null && diskCache == null) {
            nb = readSource(streamPos, buf, off, len);
            if (nb > 0) {
                streamPos += nb;
            }
        } else {
            for (int nr = 0; nb < len && streamPos < srcSize; nb += nr, streamPos += nr) {
                int pageNum = (int)(streamPos / pageBytes);
                byte[] page = loadPage(pageNum);
                if (page == null) {
                    break;
                }
                int srcStart = (int)(streamPos % pageBytes);
                nr = (int)Math.min(len - nb, page.length - srcStart);
                System.arraycopy(page, srcStart, buf, off + nb, nr);
            }
        }

        numReads++;
        readBytes += nb;

        return nb;
    }

    @Override
    synchronized public void close() throws IOException {
        deleteDiskCache();
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        diskCache = null;
        memCache = null;
        shutdownHook = null;
    }

    synchronized public void deleteDiskCache() throws IOException {
        if (diskCacheDir != null && Files.isDirectory(Paths.get(diskCacheDir))) {
            FileUtils.deleteDirectory(new File(diskCacheDir));
            diskCacheDir = null;
        }
        diskCache = null;
        diskCacheOccupied = 0;
    }

    public synchronized int maxPageTransfers() {
        return !pageTransfers.isEmpty() ? Collections.max(pageTransfers.values()) : 0;
    }

    public synchronized long memCacheUsed() {
        long used = 0;
        if (memCache != null) {
            for (var it = memCache.entrySet().iterator(); it.hasNext(); ) {
                var buf = it.next().getValue().get();
                if (buf != null) {
                    used += buf.length;
                } else {
                    memCacheGCs++;
                    it.remove();
                }
            }
        }
        return used;
    }

    public synchronized long diskCacheUsed() {
        return diskCacheOccupied;
    }

    public synchronized long memCacheCapacity() {
        return memCache != null ? memCachePages * pageBytes : 0;
    }

    public synchronized long diskCacheCapacity() {
        return diskCache != null ? diskCachePages * pageBytes : 0;
    }

    public synchronized long loadedBytes() {
        return transferredBytes;
    }

    public synchronized void dumpStats(Logger log, String pfx) {
        log.debug(pfx + "LRU cache image reader stats");
        log.debug(pfx + "  source size: " + Utils.kmg(srcSize) + ", page bytes: " + Utils.kmg(pageBytes) +
                  ", mem cache max pages: " + memCachePages +
                  (diskCacheCapacity() > 0 ?
                   (", disk cache max pages: " + diskCachePages + ", dir: " + diskCacheDir) : ""));
        log.debug(pfx + "  total read() calls: " + numReads + ", bytes: " + Utils.kmg(readBytes) +
                  ", page loads: " + pageLoads);
        log.debug(pfx + "  allocated buffers: " + allocatedBuffers + ", recycled: " + recycledBuffers +
                  ", bytes allocated: " + Utils.kmg(allocatedBytes));
        log.debug(pfx + "  transfers: " + numTransfers + ", bytes: " + Utils.kmg(transferredBytes) +
                  ", max for any page: " + maxPageTransfers());
        long mcu = memCache != null ? memCacheUsed() : -1; //memCache is null after close()
        long mcc = pageBytes * memCachePages;
        log.debug(pfx + "  mem cache " + (mcu >= 0 ? (Utils.kmg(mcu) + "/" + Utils.kmg(mcc) + " used, ") : "") +
                  "hits/misses/GCs/evictions: " +
                  memCacheHits + "/" + memCacheMisses + "/" + memCacheGCs + "/" + memCacheEvictions);
        if (diskCacheCapacity() > 0) {
            long dcu = diskCacheOccupied;
            long dcc = pageBytes * diskCachePages;
            log.debug(pfx + "  disk cache " + (dcu >= 0 ? (Utils.kmg(dcu) + "/" + Utils.kmg(dcc) + " used, ") : "") +
                     "hits/misses/evictions: " + diskCacheHits + "/" + diskCacheMisses + "/" + diskCacheEvictions);
            log.debug(pfx + "  disk cache reads: " + diskReads + " (" + Utils.kmg(diskReadBytes) + "), writes: " +
                      diskWrites + " (" + Utils.kmg(diskWriteBytes) + ")");
        }
    }

    public synchronized void dumpStats(Logger log) {
        dumpStats(log, "");
    }

    public synchronized void dumpPageTransfers(Logger log, String pfx) {
        var pagesWithOneTransfer = new MutableInt();
        pageTransfers.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue())).forEach((entry) -> {
                if (entry.getValue() > 1) {
                    log.debug(pfx + " page " + entry.getKey() + ": " + entry.getValue() + " transfers");
                } else {
                    pagesWithOneTransfer.increment();
                }
            });
        log.debug(pfx + pagesWithOneTransfer + " pages transferred once");
    }

    public synchronized void dumpPageTransfers(Logger log) {
        dumpPageTransfers(log);
    }

    synchronized private byte[] loadPage(int pageNum) throws IOException {

        if (pageBytes <= 0) {
            throw new IllegalStateException("caching disabled");
        }

        if (pageNum < 0) {
            throw new IllegalArgumentException("invalid page number " + pageNum);
        }

        long startByte = pageNum * pageBytes;
        if (startByte >= srcSize) {
            return null;
        }

        long endByte = Math.min(startByte + pageBytes - 1, srcSize - 1);
        int bs = (int)(endByte - startByte + 1);

        byte[] buf = null; //might reuse buffer from LRU memcache page
        int nr = bs;

        boolean downloaded = false;
        if (memCache != null) {
            buf = memCache.containsKey(pageNum) ? memCache.get(pageNum).get() : null;
            if (buf != null) {
                memCacheHits++;
            } else {
                memCacheMisses++;
                if (memCache.containsKey(pageNum)) {
                    for (var it = memCache.entrySet().iterator(); it.hasNext(); ) {
                        var entry = it.next();
                        if (entry.getValue().get() == null) {
                            memCacheGCs++;
                            it.remove();
                        }
                    }
                }
                while (memCache.size() >= memCachePages) {
                    memCacheEvictions++;
                    var lru = getLRU(memCache);
                    int evictedPage = lru.getKey();
                    byte[] evictedBuf = lru.getValue().get();
                    memCache.remove(evictedPage);
                    if (evictedBuf != null) {
                        if (diskCache != null && !diskCache.containsKey(evictedPage) &&
                            (diskCache.size() < diskCachePages || getLRU(diskCache).getKey() != pageNum)) {
                            //save LRU page that's getting evicted from mem to disk
                            //but only if it won't evict the page we're loading
                            saveDiskPage(evictedPage, evictedBuf);
                        }
                        if (buf == null ||
                            (buf.length != bs && (evictedBuf.length == bs || evictedBuf.length > buf.length))) {
                            buf = evictedBuf; //try to recycle
                        }
                    }
                }
                buf = allocateBuffer(buf, bs);
                if (diskCache != null && diskCache.containsKey(pageNum)) {
                    nr = loadDiskPage(pageNum, buf);
                } else {
                    downloaded = true;
                    nr = readSource(startByte, buf);
                }
                memCache.put(pageNum, new SoftReference<byte[]>(buf));
            }
        } else if (diskCache != null) {
            downloaded = !diskCache.containsKey(pageNum);
            buf = allocateBuffer(buf, bs);
            nr = loadDiskPage(pageNum, buf);
        } else {
            downloaded = true;
            buf = allocateBuffer(buf, bs);
            nr = readSource(startByte, buf);
        }

        if (buf == null || buf.length != bs || nr != bs) {
            throw new IOException("unexpected EOF reading page " + pageNum);
        }

        if (downloaded) {
            if (!pageTransfers.containsKey(pageNum)) {
                pageTransfers.put(pageNum, 1);
            } else {
                pageTransfers.put(pageNum, pageTransfers.get(pageNum) + 1);
            }
        }

        pageLoads++;

        return buf;
    }

    private synchronized int readSource(long startByte, byte[] buf, int off, int len) throws IOException {
        int nr = 0;
        if (s3 != null) {
            nr = s3.getRange(s3Bucket, s3Key, startByte, buf, off, len);
        } else if (isHTTP) {
            nr = HTTPHelper.getRange(url, startByte, buf, off, len);
            if (nr < 0) throw new IOException("HTTP(S) range request not supported for " + url);
        } else {
            try (var fc = FileChannel.open(Paths.get(url), StandardOpenOption.READ)) {
                nr = fc.read(ByteBuffer.wrap(buf, off, len), startByte);
            }
        }
        numTransfers++;
        transferredBytes += nr;
        return nr;
    }

    private synchronized int readSource(long startByte, byte[] buf) throws IOException {
        return readSource(startByte, buf, 0, buf.length);
    }

    private synchronized <K, V> Map.Entry<K, V> getLRU(LinkedHashMap<K, V> map) {
        return map.size() > 0 ? map.entrySet().iterator().next() : null;
    }

    private synchronized byte[] allocateBuffer(byte[] buf, int size) {
        if (buf != null && buf.length == size) {
            recycledBuffers++;
            return buf;
        }
        allocatedBuffers++;
        allocatedBytes += size;
        return new byte[size];
    }

    private synchronized void saveDiskPage(int pageNum, byte[] buf) throws IOException {
        if (diskCache.containsKey(pageNum)) {
            return;
        }
        while (diskCache.size() >= diskCachePages) {
            diskCacheEvictions++;
            var lru = getLRU(diskCache);
            var path = lru.getValue();
            if (Files.exists(path)) {
                diskCacheOccupied -= Files.size(path);
                Files.delete(path);
            }
            diskCache.remove(lru.getKey());
        }
        diskCache.put(pageNum, writeCacheFile(pageNum, buf));
    }

    private synchronized int loadDiskPage(int pageNum, byte[] buf) throws IOException {
        if (diskCache.containsKey(pageNum)) {
            diskCacheHits++;
            return readCacheFile(diskCache.get(pageNum), buf);
        } else {
            diskCacheMisses++;
            long startByte = pageNum * pageBytes;
            int nr = readSource(startByte, buf);
            if (nr == buf.length) {
                saveDiskPage(pageNum, buf);
            }
            return nr;
        }
    }

    private synchronized int readCacheFile(Path file, byte[] buf) throws IOException {
        try (var fc = FileChannel.open(file, StandardOpenOption.READ)) {
            if (fc.size() != buf.length) {
                throw new IOException("unexpected length " + fc.size() + " != " + buf.length + " for file " + file);
            }
            var bb = ByteBuffer.wrap(buf);
            int nb = 0;
            for (int nr = 0; nb < buf.length && nr >= 0; nb += nr) {
                nr = fc.read(bb);
            }
            diskReads++;
            diskReadBytes += nb;
            return nb;
        }
    }

    private synchronized Path writeCacheFile(long pageNum, byte[] buf) throws IOException {
        Files.createDirectories(Paths.get(diskCacheDir));
        var file = Paths.get(diskCacheDir, pageNum + ".bin");
        try (var fc = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                                       StandardOpenOption.TRUNCATE_EXISTING)) {
            var bb = ByteBuffer.wrap(buf);
            for (int nw = 0, nb = 0; nb < buf.length && nw >= 0; nb += nw) {
                nw = fc.write(bb);
            }
        }
        diskWrites++;
        diskWriteBytes += buf.length;
        diskCacheOccupied += buf.length;
        return file;
    }
}
