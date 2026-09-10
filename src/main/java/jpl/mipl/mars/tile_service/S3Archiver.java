package jpl.mipl.mars.tile_service;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Predicate;

import software.amazon.awssdk.services.s3.model.S3Object;

import org.slf4j.Logger;

/**
 *
 * @author Marsette Vona
 */
public class S3Archiver implements ExtendedArchiver {

    public final String bucket;
    public final String prefix;
    public final S3Helper s3;

    private volatile Transformer transformer;

    private volatile Logger log;
    private volatile String pfx;

    public S3Archiver(String bucket, String prefix, S3Helper s3) {
        this.bucket = bucket;
        this.prefix = prefix;
        this.s3 = s3;
    }

    @Override
    public void setTransformer(Transformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void setLogger(Logger log, String pfx) {
        this.log = log;
        this.pfx = pfx;
    }

    @Override
    public <T> T appendFile(String path, FileAppender<T> appender) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        T result = appender.append(out);
        byte[] bytes = out.toByteArray();
        if (transformer != null && transformer.shouldTransform(path)) {
            try {
                out.reset();
                transformer.transform(new ByteArrayInputStream(bytes), out);
                bytes = out.toByteArray();
            } catch (Exception ex) {
                if (log != null) log.error(pfx + "failed to transform " + path, ex);
                //bytes should still have its original value
            }
        }
        s3.putFile(bucket, pathToKey(path), bytes);
        return result;
    }

    @Override
    public <T> T appendBigFile(String path, FileAppender<T> appender) throws IOException {
        return appendFile(path, appender);
    }

    @Override
    public void appendFile(String path, File file) throws IOException {
        s3.putFile(bucket, pathToKey(path), file);
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public boolean deleteFile(String path) throws IOException {
        String key = pathToKey(path);
        if (!s3.doesObjectExist(bucket, key)) {
            return false;
        }
        s3.deleteObject(bucket, key);
        return true;
    }

    @Override
    public int deleteFilesRecursive(Predicate<String> filter) throws IOException {
        return s3.deleteObjectsRecursive(bucket, prefix, null, false, false, filter);
    }

    @Override
    public Map<String, Long> getFileTimes(Predicate<String> filter, boolean recursive) throws IOException {
        var ret = new HashMap<String, Long>();
        for (S3Object obj : s3.listObjects(bucket, prefix + "/", null, 0, recursive, filter)) {
            ret.put("s3://" + bucket + "/" + obj.key(), obj.lastModified().toEpochMilli());
        }
        return ret;
    }

    @Override
    public InputStream openStream(String path) throws IOException {
        return s3.getStream(bucket, pathToKey(path));
    }

    @Override
    public boolean fileExists(String path) {
        try {
            return s3.doesObjectExist(bucket, pathToKey(path));
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public String loadTextFileIfExists(String path) {
        try {
            String key = pathToKey(path);
            return s3.doesObjectExist(bucket, key) ? s3.getTextFile(bucket, key) : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private String pathToKey(String path) {
        return prefix + "/" + path.replace(File.separator, "/");
    }
}
