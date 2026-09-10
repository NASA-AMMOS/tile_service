package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.File;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import gov.nist.isg.archiver.FilesArchiver;

import org.slf4j.Logger;

/**
 *
 * @author Marsette Vona
 */
public abstract class CachedArchiver implements FilesArchiver, ExtendedArchiver {

    protected class TileKey {
        
        public final int level;
        public final int col;
        public final int row;
        
        public TileKey(Matcher matcher) {
            level = Integer.parseInt(matcher.group(1));
            col = Integer.parseInt(matcher.group(2));
            row = Integer.parseInt(matcher.group(3));
        }

        public TileKey(int level, int col, int row) {
            this.level = level;
            this.col = col;
            this.row = row;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TileKey that = (TileKey) o;
            return that.level == level && that.col == col && that.row == row;
        }
        
        @Override
        public int hashCode() {
            int result = level;
            result = 31 * result + col;
            result = 31 * result + row;
            return result;
        }
    }

    public final ExtendedArchiver output;

    //...LEVEL/COL_ROW.ext
    public static final Pattern TILE_PATTERN = Pattern.compile("(\\d+)/(\\d+)_(\\d+)\\.([^/]+)$");

    private ConcurrentHashMap<Integer, Integer> numTilesAtLevel = new ConcurrentHashMap<Integer, Integer>();

    private volatile String cancelSource;
    private volatile boolean cancelled;

    private volatile Logger log;
    private volatile String pfx;

    public CachedArchiver(ExtendedArchiver next) {
        output = next;
    }

    @Override
    public void setTransformer(Transformer transformer) {
        output.setTransformer(transformer);
    }

    @Override
    public void setLogger(Logger log, String pfx) {
        this.log = log;
        this.pfx = pfx != null ? pfx : "";
        output.setLogger(log, pfx);
    }

    @Override
    public <T> T appendFile(String path, FileAppender<T> appender) throws IOException {
        checkCancelled(path);
        T ret = output.appendFile(path, appender);
        cache(path, appender);
        return ret;
    }

    @Override
    public <T> T appendBigFile(String path, FileAppender<T> appender) throws IOException {
        checkCancelled(path);
        T ret = output.appendBigFile(path, appender);
        cache(path, appender);
        return ret;
    }

    @Override
    public void appendFile(String path, File file) throws IOException {
        checkCancelled(path);
        output.appendFile(path, file);
        cache(path, file);
    }

    @Override
    public void close() throws IOException {
        output.close();
    }

    @Override
    public boolean deleteFile(String path) throws IOException {
        return output.deleteFile(path);
    }

    @Override
    public int deleteFilesRecursive(Predicate<String>  filter) throws IOException {
        return output.deleteFilesRecursive(filter);
    }

    @Override
    public boolean fileExists(String path) {
        return output.fileExists(path);
    }

    @Override
    public String loadTextFileIfExists(String path) {
        return output.loadTextFileIfExists(path);
    }

    @Override
    public Map<String, Long> getFileTimes(Predicate<String> filter, boolean recursive) throws IOException {
        return output.getFileTimes(filter, recursive);
    }

    @Override
    public InputStream openStream(String path) throws IOException {
        return output.openStream(path);
    }

    public InputStream getCachedTile(String path) throws IOException {
        Matcher matcher = TILE_PATTERN.matcher(path.replace(File.separator, "/"));
        return matcher.find() ? getCachedTile(new TileKey(matcher)) : null;
    }

    public InputStream getCachedTile(int level, int col, int row) throws IOException {
        return getCachedTile(new TileKey(level, col, row));
    }

    public int getNumCachedTilesAtLevel(int level) {
        Integer num = numTilesAtLevel.get(level);
        return num != null ? num.intValue() : 0;
    }

    public abstract String getCachedDZI() throws IOException;

    public abstract int numArchived();

    public void archiveText(String text, String filename) throws IOException {
        appendFile(filename, new FilesArchiver.FileAppender<Boolean>() {
                @Override
                public Boolean append(OutputStream outputStream) throws IOException {
                    try (PrintStream ps = new PrintStream(outputStream)) {
                        ps.print(text);
                        return true;
                    } catch (Exception ex) {
                        throw new IOException("error writing " + filename, ex);
                    }
                }
            });
    }

    public void archiveImage(BufferedImage image, String format, String filename) throws IOException {
        boolean wrote = appendFile(filename, new FilesArchiver.FileAppender<Boolean>() {
                @Override
                public Boolean append(OutputStream outputStream) throws IOException {
                    return ImageIO.write(image, format, outputStream);
                }
            });
        if (!wrote) {
            throw new IOException("no " + format + " image writer found for " + filename);
        }
    }

    protected void addedTile(TileKey key) {
        numTilesAtLevel.compute(key.level, (level, num) -> num != null ? (num + 1) : 1);
    }

    protected abstract InputStream getCachedTile(TileKey key) throws IOException;

    protected abstract <T> void cache(String path, FileAppender<T> appender) throws IOException;

    protected abstract void cache(String path, File file) throws IOException;

    public void cancel(String source) {
        cancelSource = source;
        cancelled = true;
    }

    private void checkCancelled(String path) {
        //allow saving error.txt and progress.txt even if cancelled
        if ((cancelled || Thread.currentThread().isInterrupted()) && !path.endsWith(".txt")) {
            throw new DziTask.CancellationException(cancelSource);
        }
    }
}
