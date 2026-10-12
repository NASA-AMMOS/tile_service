package jpl.mipl.mars.tile_service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.net.URI;
import java.net.URL;
import java.net.InetAddress;
import java.net.HttpURLConnection;

/**
 * @author Marsette Vona
 */
public class HTTPHelper {

    public static boolean isHTTPUrl(String url) {
        url = url.toLowerCase();
        return url.startsWith("http://") || url.startsWith("https://");
    }

    public static final int MAX_REDIRECTS = 5;

    //open a connection to url, following up to MAX_REDIRECTS redirects
    //the target host of every hop is checked with isSafeHost() to avoid server side request forgery (SSRF)
    //this is checked on every connection, not just once up front, to limit DNS rebinding attacks
    //the response code has been read when this returns
    private static HttpURLConnection open(String url, String method, String range) throws IOException {
        URL u = new URL(url);
        for (int hops = 0; ; hops++) {
            String protocol = u.getProtocol().toLowerCase();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IOException("unsupported protocol " + protocol + " for " + u);
            }
            if (!isSafeHost(u.getHost())) { //check host of the URL object we actually connect to
                throw new IOException("blocked request to non-public host " + u.getHost());
            }
            HttpURLConnection c = (HttpURLConnection)u.openConnection();
            c.setInstanceFollowRedirects(false); //we follow redirects ourselves so we can check each hop
            c.setRequestMethod(method);
            if (range != null) {
                c.setRequestProperty("Range", range);
            }
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_MOVED_PERM && code != HttpURLConnection.HTTP_MOVED_TEMP &&
                code != HttpURLConnection.HTTP_SEE_OTHER && code != 307 && code != 308) {
                return c;
            }
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (location == null || location.isEmpty()) {
                throw new IOException("redirect " + code + " without location from " + u);
            }
            if (hops >= MAX_REDIRECTS) {
                throw new IOException("too many redirects from " + url);
            }
            u = new URL(u, location); //location may be relative
        }
    }
    public static boolean exists(String url) throws IOException {
        HttpURLConnection c = open(url, "HEAD", null);
        return c.getResponseCode() == HttpURLConnection.HTTP_OK;
    }

    public static long getSize(String url) throws IOException {
        HttpURLConnection c = open(url, "HEAD", null);
        return c.getContentLengthLong();
    }

    public static String getETag(String url) throws IOException {
        HttpURLConnection c = open(url, "HEAD", null);
        String etag = c.getHeaderField("ETag");
        if (etag == null || etag.length() == 0) throw new IOException("missing ETag for " + url);
        return etag;
    }

    public static long getLastModified(String url) throws IOException {
        HttpURLConnection c = open(url, "HEAD", null);
        return c.getLastModified();
    }

    public static InputStream getStream(String url) throws IOException {
        return open(url, "GET", null).getInputStream();
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
        HttpURLConnection c = open(url, "GET", "bytes=" + startByte + "-" + endByte);
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
        HttpURLConnection c = open(url, "GET", "bytes=" + startByte + "-" + endByte);
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

    //Server Side Request Forgery (SSRF) filter

    private static final List<String> BLOCKED_HOSTNAMES = List.of(
        "localhost",
        "metadata.google.internal",
        "instance-data"
    );

    //returns true iff urlString is an http[s] URL whose host resolves only to public addresses
    //this is a pre-filter only; open() re-checks every connection, including redirects
    public static boolean isSafeUrl(String urlString) {
        try {
            URI uri = URI.create(urlString); //strict parse, malformed URLs are rejected

            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }

            return isSafeHost(uri.getHost());
        } catch (Exception e) {
            return false; //malformed URL
        }
    }

    //returns true iff host is not a known sensitive hostname and all of its addresses are public
    //IPv6 literals may be bracketed
    public static boolean isSafeHost(String host) {

        if (host == null || host.isBlank()) {
            return false;
        }

        String name = host.toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) { //"localhost." is the same host as "localhost"
            name = name.substring(0, name.length() - 1);
        }
        if (BLOCKED_HOSTNAMES.contains(name) || name.endsWith(".localhost")) {
            return false;
        }

        try {
            //check all addresses, not just the first, since the connection could use any of them
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs.length == 0) {
                return false;
            }
            for (InetAddress addr : addrs) {
                if (!isSafeAddress(addr)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false; //resolution failure
        }
    }

    public static boolean isSafeAddress(InetAddress addr) {

        byte[] b = addr.getAddress();

        if (b.length == 4) {
            return isSafeIPv4(b, 0);
        }

        if (addr.isLoopbackAddress() || //::1
            addr.isAnyLocalAddress() || //::
            addr.isLinkLocalAddress() || //fe80::/10
            addr.isSiteLocalAddress() || //fec0::/10 (deprecated)
            addr.isMulticastAddress()) { //ff00::/8
            return false;
        }

        if ((b[0] & 0xfe) == 0xfc) { //fc00::/7 unique local, includes AWS metadata fd00:ec2::254
            return false;
        }

        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) { //2001::/32 Teredo, IPv4 is obfuscated
            return false;
        }

        if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte)0xff && b[3] == (byte)0x9b && b[4] == 0 && b[5] == 1) {
            return false; //64:ff9b:1::/48 local use NAT64, IPv4 position depends on local prefix length
        }

        //IPv6 addresses with embedded IPv4 addresses
        if (allZero(b, 0, 10) && ((b[10] == 0 && b[11] == 0) || //::a.b.c.d IPv4 compatible (deprecated)
                                  (b[10] == (byte)0xff && b[11] == (byte)0xff))) { //::ffff:a.b.c.d IPv4 mapped
            return isSafeIPv4(b, 12);
        }
        if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte)0xff && b[3] == (byte)0x9b && allZero(b, 4, 12)) {
            return isSafeIPv4(b, 12); //64:ff9b::/96 NAT64
        }
        if (b[0] == 0x20 && b[1] == 0x02) { //2002::/16 6to4
            return isSafeIPv4(b, 2);
        }

        return true;
    }

    private static boolean isSafeIPv4(byte[] b, int off) {
        int a0 = b[off] & 0xff, a1 = b[off + 1] & 0xff, a2 = b[off + 2] & 0xff;
        return !(a0 == 0 || //0.0.0.0/8 "this" network
                 a0 == 10 || //10.0.0.0/8 private
                 (a0 == 100 && a1 >= 64 && a1 <= 127) || //100.64.0.0/10 carrier grade NAT, includes Alibaba metadata
                 a0 == 127 || //127.0.0.0/8 loopback
                 (a0 == 169 && a1 == 254) || //169.254.0.0/16 link local, includes AWS/GCP/Azure metadata
                 (a0 == 172 && a1 >= 16 && a1 <= 31) || //172.16.0.0/12 private
                 (a0 == 192 && a1 == 0 && a2 == 0) || //192.0.0.0/24 IETF protocol assignments, includes OCI metadata
                 (a0 == 192 && a1 == 168) || //192.168.0.0/16 private
                 (a0 == 198 && (a1 == 18 || a1 == 19)) || //198.18.0.0/15 benchmarking
                 a0 >= 224); //224.0.0.0/4 multicast, 240.0.0.0/4 reserved, 255.255.255.255 broadcast
    }

    private static boolean allZero(byte[] b, int start, int end) {
        for (int i = start; i < end; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
