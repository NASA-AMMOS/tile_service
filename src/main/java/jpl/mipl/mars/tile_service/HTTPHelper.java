package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.HttpURLConnection;

/**
 * @author Marsette Vona
 */
public class HTTPHelper {

    public static boolean isHTTPUrl(String url) {
        url = url.toLowerCase();
        return url.startsWith("http://") || url.startsWith("https://");
    }
    
    public static boolean exists(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection)((new URL(url)).openConnection());
        c.setRequestMethod("HEAD");
        return c.getResponseCode() == HttpURLConnection.HTTP_OK;
    }

    public static long getSize(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection)((new URL(url)).openConnection());
        c.setRequestMethod("HEAD");
        return c.getContentLengthLong();
    }

    public static String getETag(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection)((new URL(url)).openConnection());
        c.setRequestMethod("HEAD");
        String etag = c.getHeaderField("ETag");
        if (etag == null || etag.length() == 0) throw new IOException("missing ETag for " + url);
        return etag;
    }

    public static long getLastModified(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection)((new URL(url)).openConnection());
        c.setRequestMethod("HEAD");
        return c.getLastModified();
    }

    public static InputStream getStream(String url) throws IOException {
        return (new URL(url)).openConnection().getInputStream();
    }

    public static byte[] getRange(String url, long startByte, long endByte) throws IOException {
        byte[] buf = new byte[(int)(endByte - startByte + 1)];
        int nr = getRange(url, startByte, buf, 0, buf.length);
        if (nr == buf.length) return buf;
        byte[] tmp = new byte[nr];
        System.arraycopy(buf, 0, tmp, 0, nr);
        return tmp;
    }

    //returns -1 if range queries are not supported by server, else number of bytes actually read
    public static int getRange(String url, long startByte, byte[] buf, int off, int len) throws IOException {
        long endByte = startByte + len - 1;
        if (endByte < startByte) {
            throw new IllegalArgumentException("invalid range [" + startByte + ", " + endByte + "]");
        }
        HttpURLConnection c = (HttpURLConnection)((new URL(url)).openConnection());
        c.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);
        if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;
        try (InputStream is = c.getInputStream()) {
            for (int nb = 0, nr = 0; nb < len; nb += nr, off += nr) {
                nr = is.read(buf, off, len - nb);
                if (nr < 0) return nb;
            }
            return len;
        }
    }

    //returns -1 if range queries are not supported by server, else number of bytes actually read
    public static long getRange(String url, long startByte, byte[][] bufs, long max) throws IOException {
        long len = 0;
        for (int i = 0; i < bufs.length && len < max; i++) {
            if (bufs[i] != null) len += bufs[i].length;
        }
        if (len > max) len = max;
        long endByte = startByte + len - 1;
        if (endByte < startByte) { 
            throw new IllegalArgumentException("invalid range [" + startByte + ", " + endByte + "]");
        }
        HttpURLConnection c = (HttpURLConnection)((new URL(url)).openConnection());
        c.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);
        if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;
        try (InputStream is = c.getInputStream()) {
            for (int nb = 0, nr = 0, i = 0, off = 0; nb < len; nb += nr, off += nr) {
                int left = bufs[i] != null ? bufs[i].length - off : 0;
                if (left == 0) {
                    i++;
                    off = 0;
                    nr = 0;
                } else {
                    long rb = len - nb;
                    if (rb > left) rb = left;
                    nr = is.read(bufs[i], off, (int)rb);
                    if (nr < 0) return nb;
                }
            }
            return len;
        }
    }
}
