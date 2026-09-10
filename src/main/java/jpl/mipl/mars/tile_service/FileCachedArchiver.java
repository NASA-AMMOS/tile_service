package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;

/**
 *
 * @author Marsette Vona
 */
public class FileCachedArchiver extends CachedArchiver {

    private ConcurrentHashMap<TileKey, String> tileCache = new ConcurrentHashMap<TileKey, String>();
    private volatile String dziPath;

    public FileCachedArchiver(ExtendedArchiver next) {
        super(next);
    }

    @Override
    protected InputStream getCachedTile(TileKey key) throws IOException {
        String path = tileCache.get(key);
        return path != null ? openStream(path) : null;
    }

    @Override
    public String getCachedDZI() throws IOException {
        String path = dziPath;
        if (path == null) {
            return null;
        }
        try (var is = openStream(path)) {
            var sw = new StringWriter();
            IOUtils.copy(is, sw, StandardCharsets.UTF_8);
            return sw.toString();
        }
    }

    @Override
    public int numArchived() {
        return tileCache.size() + (dziPath != null ? 1 : 0);
    }

    @Override
    protected <T> void cache(String path, FileAppender<T> appender) throws IOException {
        cache(path);
    }

    @Override
    protected void cache(String path, File file) throws IOException {
        cache(path);
    }

    private void cache(String path) {
        Matcher matcher = TILE_PATTERN.matcher(path.replace(File.separator, "/"));
        if (matcher.find()) {
            var key = new TileKey(matcher);
            var isNew = new MutableBoolean();
            tileCache.compute(key, (ignore, existingPath) -> {
                    isNew.setValue(existingPath == null);
                    return path;
                });
            if (isNew.booleanValue()) {
                addedTile(key);
            }
        } else if (path.toLowerCase().endsWith(".dzi")) {
            dziPath = path;
        } 
    }
}
