package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.function.Predicate;

import gov.nist.isg.archiver.FilesArchiver;

import org.slf4j.Logger;

/**
 *
 * @author Marsette Vona
 */
public interface ExtendedArchiver extends FilesArchiver {

    public interface Transformer {
        public boolean shouldTransform(String path);
        public void transform(InputStream in, OutputStream out) throws IOException;
    }

    public void setTransformer(Transformer transformer);

    public void setLogger(Logger log, String pfx);

    public boolean deleteFile(String path) throws IOException;

    public int deleteFilesRecursive(Predicate<String> filter) throws IOException;

    public boolean fileExists(String path);

    public String loadTextFileIfExists(String path);

    public Map<String, Long> getFileTimes(Predicate<String> filter, boolean recursive) throws IOException;

    public InputStream openStream(String path) throws IOException;
}
