package jpl.mipl.mars.tile_service;

import gov.nist.isg.archiver.DirectoryArchiver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;

/**
 *
 * @author Marsette Vona
 */
public class ExtendedDirectoryArchiver extends DirectoryArchiver implements ExtendedArchiver {

    public final File dir;

    private Transformer transformer;

    private volatile Logger log;
    private volatile String pfx;

    public ExtendedDirectoryArchiver(File dir) throws IOException {
        super(dir);
        this.dir = dir;
    }

    @Override
    public void setTransformer(Transformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void setLogger(Logger log, String pfx) {
        this.log = log;
        this.pfx = pfx != null ? pfx : "";
    }

    @Override
    public <T> T appendFile(String path, FileAppender<T> appender) throws IOException {
        if (transformer == null || !transformer.shouldTransform(path)) {
            return super.appendFile(path, appender);
        } else {
            try (var fos = new FileOutputStream(ensureParent(path))) {
                var tmp = new ByteArrayOutputStream();
                T result = appender.append(tmp);
                transformer.transform(new ByteArrayInputStream(tmp.toByteArray()), fos);
                return result;
            } catch (Exception ex) {
                if (log != null) log.error(pfx + "failed to transform " + path, ex);
                return super.appendFile(path, appender);
            }
        }
    }

    @Override
    public <T> T appendBigFile(String path, FileAppender<T> appender) throws IOException {
        return appendFile(path, appender);
    }

    @Override
    public void appendFile(String path, File file) throws IOException {
        if (transformer == null || !transformer.shouldTransform(path)) {
            super.appendFile(path, file);
        } else {
            try (var fis = new FileInputStream(file); var fos = new FileOutputStream(ensureParent(path))) {
                transformer.transform(fis, fos);
            } catch (Exception ex) {
                if (log != null) log.error(pfx + "failed to transform " + path, ex);
                super.appendFile(path, file);
            }
        }
    }

    @Override
    public boolean deleteFile(String path) throws IOException {
        var file = new File(dir, path);
        return file.exists() && file.delete();
    }

    @Override
    public int deleteFilesRecursive(Predicate<String> filter) throws IOException {
        return deleteFilesRecursive(dir, filter, new int[] { 0, 0 })[0];
    }

    private File ensureParent(String path) throws IOException {
        File file = new File(dir, path);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("cannot create directory " + parent);
        }
        return file;
    }

    private int[] deleteFilesRecursive(File dirOrFile, Predicate<String> filter, int[] stats) throws IOException {
        //stats[0] = num deleted files
        //stats[1] = num remaining files
        if (dirOrFile.isDirectory()) {
            int[] childStats = new int[] { 0, 0 };
            for (var child : dirOrFile.listFiles()) {
                deleteFilesRecursive(child, filter, childStats);
            }
            if (childStats[1] == 0) {
                dirOrFile.delete();
            }
            stats[0] += childStats[0];
            stats[1] += childStats[1];
        } else if (filter == null || filter.test(dirOrFile.getCanonicalPath())) {
            dirOrFile.delete();
            stats[0]++;
        } else {
            stats[1]++;
        }
        return stats;
    }

    @Override
    public boolean fileExists(String path) {
        return (new File(dir, path)).exists();
    }

    @Override
    public String loadTextFileIfExists(String path) {
        try {
            var file = new File(dir, path);
            return file.exists() ? Files.readString(file.toPath()) : null;
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public Map<String, Long> getFileTimes(Predicate<String> filter, boolean recursive) throws IOException {
        return getFileTimes(dir, filter, recursive, new HashMap<String, Long>());
    }

    private Map<String, Long> getFileTimes(File dirOrFile, Predicate<String> filter, boolean recursive,
                                           Map<String, Long> ret) throws IOException {
        if (dirOrFile.isDirectory()) {
            for (var child : dirOrFile.listFiles()) {
                if (!child.isDirectory() || recursive) {
                    getFileTimes(child, filter, recursive, ret);
                }
            }
        } else {
            String path = dirOrFile.getCanonicalPath();
            if (filter == null || filter.test(path)) {
                ret.put(path, dirOrFile.lastModified());
            }
        }
        return ret;
    }

    @Override
    public InputStream openStream(String path) throws IOException {
        return new FileInputStream(new File(dir, path));
    }
}
