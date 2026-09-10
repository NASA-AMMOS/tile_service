package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import org.apache.commons.lang3.mutable.MutableBoolean;

/**
 *
 * @author Marsette Vona
 */
public class MemCachedArchiver extends CachedArchiver {

    private ConcurrentHashMap<TileKey, byte[]> tileCache = new ConcurrentHashMap<TileKey, byte[]>();
    private volatile String dzi;

    public MemCachedArchiver(ExtendedArchiver next) {
        super(next);
    }

    @Override
    protected InputStream getCachedTile(TileKey key) throws IOException {
        byte[] tile = tileCache.get(key);
        return tile != null ? new ByteArrayInputStream(tile) : null;
    }

    @Override
    public String getCachedDZI() throws IOException {
        return dzi;
    }

    @Override
    public int numArchived() {
        return tileCache.size() + (dzi != null ? 1 : 0);
    }

    @Override
    protected <T> void cache(String path, FileAppender<T> appender) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        appender.append(os);
        cache(path, os.toByteArray());
    }

    @Override
    protected void cache(String path, File file) throws IOException {
        cache(path, Files.readAllBytes(file.toPath()));
    }

    private void cache(String path, byte[] file) throws IOException {
        Matcher matcher = TILE_PATTERN.matcher(path.replace(File.separator, "/"));
        if (matcher.find()) {
            var key = new TileKey(matcher);
            var isNew = new MutableBoolean();
            tileCache.compute(key, (ignore, existingFile) -> {
                    isNew.setValue(existingFile == null);
                    return file;
                });
            if (isNew.booleanValue()) {
                addedTile(key);
            }
        } else if (path.toLowerCase().endsWith(".dzi")) {
            dzi = new String(file, "UTF-8");
        } 
    }
}
