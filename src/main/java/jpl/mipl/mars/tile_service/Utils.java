package jpl.mipl.mars.tile_service;

import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.function.Function;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.lang.management.ManagementFactory;
import javax.json.Json;
import javax.json.JsonStructure;
import javax.json.stream.JsonGenerator;
import com.sun.management.HotSpotDiagnosticMXBean;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;

import org.slf4j.Logger;
        
/**
 *
 * @author Marsette Vona
 */
public class Utils {

    public static String getVersion(Class clazz) {
        InputStream str = null;
        try {
            str = clazz.getResourceAsStream("/resources/version");
            return str != null ? new String(IOUtils.toByteArray(str), StandardCharsets.UTF_8).trim() : "UNKNOWN";
        } catch (Exception ex) {
            return "UNKNOWN, error: " + ex.toString();
        } finally {
            if (str != null) {
                try {
                    str.close();
                } catch (Exception ex) {
                    //ignore
                }
            }
        }
    }

    public static String trimWhitespaceQuotesBraces(String str) {
        str = str.trim();
        while ((str.startsWith("\"") && str.endsWith("\"")) ||
               (str.startsWith("'") && str.endsWith("'")) ||
               (str.startsWith("(") && str.endsWith(")")) ||
               (str.startsWith("[") && str.endsWith("]")) ||
               (str.startsWith("{") && str.endsWith("}"))) {
            str = str.substring(1, str.length() - 1);
            str = str.trim();
        }
        return str;
    }

    public static String formatToMimeType(String format) {
        switch (format.toLowerCase()) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "tif": case "tiff": return "image/tiff";
            default: throw new IllegalArgumentException("unsupported image format " + format);
        }
    }

    // null -> ""
    // "" -> ""
    // /foo/bar -> /foo/bar/
    public static String ensureSeparators(String path, String separator) {
        path = path == null ? "" : path;
        if (separator.equals("/")) {
            path = path.replace(File.separator, separator);
        } else if (separator.equals(File.separator)) {
            path = path.replace("/", separator);
        }
        path = StringUtils.stripEnd(path, separator);
        path = path.isEmpty() ? path : path + separator;
        return path;
    }

    public static boolean hasExt(String url, String[] exts) {
        int lastDot = url.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < url.length() - 1) {
            String ext = url.substring(lastDot + 1).toLowerCase();
            for (int i = 0; i < exts.length; i++) {
                if (exts[i] != null && exts[i].length() > 0 && ext.equals(exts[i].toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String setExt(String url, String ext) {
        int lastDot = url.lastIndexOf('.');
        return (lastDot >= 0 ? url.substring(0, lastDot) : url) + "." + ext;
    }

    public static float parseFloat(String str, float def, Predicate<Number> pred) {
        if (isAuto(str)) {
            return def;
        }
        try {
            float fval = Float.parseFloat(str.trim());
            return (pred == null || pred.test(fval)) ? fval : def;
        } catch (Exception ex) {
            throw new IllegalArgumentException("error parsing " + str + " as float");
        }
    }

    public static float parseFloat(String str, float def) {
        return parseFloat(str, def, null);
    }
    
    public static int parseInt(String str, int def, Predicate<Number> pred) {
        if (isAuto(str)) {
            return def;
        }
        try {
            int ival = Integer.parseInt(str.trim());
            return (pred == null || pred.test(ival)) ? ival : def;
        } catch (Exception ex) {
            throw new IllegalArgumentException("error parsing " + str + " as int");
        }
    }
    
    public static int parseInt(String str, int def) {
        return parseInt(str, def, null);
    }

    public static long parseLong(String str, long def, Predicate<Number> pred) {
        if (isAuto(str)) {
            return def;
        }
        try {
            long lval = Long.parseLong(str.trim());
            return (pred == null || pred.test(lval)) ? lval : def;
        } catch (Exception ex) {
            throw new IllegalArgumentException("error parsing " + str + " as long");
        }
    }
    
    public static long parseLong(String str, long def) {
        return parseLong(str, def, null);
    }

    public static boolean parseBool(String str, boolean def) {
        return isAuto(str) ? def : str.trim().toLowerCase().equals("true");
    }
    
    public static boolean parseBool(String str) {
        return parseBool(str, false);
    }

    public static <T extends Enum<T>> T parseEnum(String str, Class<T> type, T def) {
        try {
            return isAuto(str) ? def : Enum.valueOf(type, str.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("error parsing " + str + " as " + type.getName());
        }
    }
    
    public static long parseBytes(String str, long def) {
        if (isAuto(str)) {
            return def;
        }
        try {
            str = str.trim().toLowerCase();
            long mult = 1;
            if (str.endsWith("k")) {
                mult = 1024;
            } else if (str.endsWith("m")) {
                mult = 1024 * 1024;
            } else if (str.endsWith("g")) {
                mult = 1024 * 1024 * 1024;
            }
            if (mult > 1) {
                str = str.substring(0, str.length() - 1).trim();
            }
            double dval = Double.parseDouble(str);
            return dval >= 0 ? (long)(mult * dval) : def;
        } catch (Exception ex) {
            throw new IllegalArgumentException("error parsing " + str + " as bytes");
        }
    }
    
    public static long parseBytes(String str, String def) {
        return parseBytes(str, parseBytes(def, 0));
    }

    public static long parseSeconds(String str, long def) {
        if (isAuto(str)) {
            return def;
        }
        try {
            str = str.trim().toLowerCase();
            long mult = 1;
            if (str.endsWith("h")) {
                mult = 60 * 60;
                str = str.substring(0, str.length() - 1).trim();
            } else if (str.endsWith("m")) {
                mult = 60;
                str = str.substring(0, str.length() - 1).trim();
            } else if (str.endsWith("s")) {
                mult = 1;
                str = str.substring(0, str.length() - 1).trim();
            }
            double dval = Double.parseDouble(str);
            return dval >= 0 ? (long)(mult * dval) : def;
        } catch (Exception ex) {
            throw new IllegalArgumentException("error parsing " + str + " as seconds");
        }
    }

    public static String parseString(String str, String def) {
        return isAuto(str) ? def :  str;
    }

    public static String kmg(long val) {
        if (val < 1024) {
            return Long.toString(val);
        } else if (val < 1024 * 1024) {
            return String.format("%.1fk", val / 1024.0);
        } else if (val < 1024 * 1024 * 1024) {
            return String.format("%.1fM", val / (1024.0 * 1024.0));
        } else {
            return String.format("%.1fG", val / (1024.0 * 1024.0 * 1024.0));
        }
    }

    public static String kmg(double val) {
        return kmg((long)val);
    }

    public static String hms(double ms)
    {
        String sign = ms < 0 ? "-" : "";
        if (ms == 0) {
            return "0s";
        } else if (ms < 1e3) {
            return String.format("%s%dms", sign, (int)ms);
        } else if (ms < 60 * 1e3) {
            double s = 1e-3 * ms;
            return String.format("%s%.3fs", sign, s);
        } else if (ms < 60 * 60 * 1e3) {
            int s = (int)(1e-3 * ms);
            return String.format("%s%dm%ds", sign, s / 60, s % 60);
        } else {
            int s = (int)(1e-3 * ms);
            return String.format("%s%dh%dm%ds", sign, s / (60 * 60), (s / 60) % 60, s % 60);
        }
    }

    public static boolean isNonNaN(Number num) {
        return num != null && !Double.isNaN(num.doubleValue());
    }

    public static boolean isNonNegative(Number num) {
        return num != null && num.doubleValue() >= 0;
    }

    public static boolean isPositive(Number num) {
        return num != null && num.doubleValue() > 0;
    }

    public static boolean isPercent(Number num) {
        return num != null && num.doubleValue() >= 0 && num.doubleValue() <= 100;
    }

    public static boolean isAlpha(Number num) {
        return num != null && num.doubleValue() >= 0 && num.doubleValue() <= 1;
    }

    public static String getStringArg(Map<String, Object> args, String name, String def) {
        if (args == null || !args.containsKey(name)) {
            return def;
        }
        Object val = args.get(name);
        String sval = (val instanceof String) ? (((String)val).trim()) : (val != null) ? val.toString().trim() : def;
        return isAuto(sval) ? def : sval;
    }
    
    public static boolean getBoolArg(Map<String, Object> args, String name, boolean def) {
        if (args == null || !args.containsKey(name)) {
            return def;
        }
        Object val = args.get(name);
        if (val instanceof Boolean) {
            return ((Boolean)val).booleanValue();
        } else if (val instanceof String) {
            String sval = ((String)val).trim().toLowerCase();
            return isAuto(sval) ? def : parseBool(sval, def);
        } else if (val == null) {
            return def;
        } else {
            throw new IllegalArgumentException("invalid type " + val.getClass().getName() + " for bool arg " + name);
        }
    }
    
    public static int getIntArg(Map<String, Object> args, String name, int def, Predicate<Number> pred) {
        if (args == null || !args.containsKey(name)) {
            return def;
        }
        Object val = args.get(name);
        Integer ival = null;
        if (val instanceof Number) {
            ival = ((Number)val).intValue();
        } else if (val instanceof String) {
            String sval = ((String)val).trim().toLowerCase();
            if (isAuto(sval)) {
                return def;
            }
            ival = parseInt(sval, def);
        } else if (val == null) {
            return def;
        } else {
            throw new IllegalArgumentException("invalid type " + val.getClass().getName() + " for int arg " + name);
        }
        return pred.test(ival) ? ival : def;
    }
    
    public static float getFloatArg(Map<String, Object> args, String name, float def, Predicate<Number> pred) {
        if (args == null || !args.containsKey(name)) {
            return def;
        }
        Object val = args.get(name);
        Float fval = null;
        if (val instanceof Number) {
            fval = ((Number)val).floatValue();
        } else if (val instanceof String) {
            String sval = ((String)val).trim().toLowerCase();
            if (isAuto(sval)) {
                return def;
            }
            fval = parseFloat(sval, def);
        } else if (val == null) {
            return def;
        } else {
            throw new IllegalArgumentException("invalid type " + val.getClass().getName() + " for float arg " + name);
        }
        return pred.test(fval) ? fval : def;
    }

    public static <T extends Enum<T>> T getEnumArg(Map<String, Object> args, String name, T def, Class<T> type) {
        if (args == null || !args.containsKey(name)) {
            return def;
        }
        Object val = args.get(name);
        if (type.isInstance(val)) {
            return type.cast(val);
        } else if (val instanceof String) {
            String sval = ((String)val).trim().toLowerCase();
            return isAuto(sval) ? def : parseEnum(sval, type, def);
        } else if (val == null) {
            return def;
        } else {
            throw new IllegalArgumentException("invalid type " + val.getClass().getName() + " for " +
                                               type.getSimpleName() + " arg " + name);
        }
    }

    //https://stackoverflow.com/a/28008477/4970315
    public static DoubleStream doubleStream(float[] floatArray) {
        return IntStream.range(0, floatArray.length).mapToDouble(i -> floatArray[i]);
    }

    public static SecureRandom getBestPRNG() {

        //this is what the JDK does in non-FIPS mode
        //https://github.com/AdoptOpenJDK/openjdk-jdk11/blob/master/src/java.base/share/classes/sun/security/ssl/JsseJce.java#L279
        //https://github.com/AdoptOpenJDK/openjdk-jdk11/blob/master/src/java.base/share/classes/sun/security/ssl/SunJSSE.java#L77
        return new SecureRandom();

        //try {
        //    return SecureRandom.getInstanceStrong();
        //} catch (NoSuchAlgorithmException ex) {
        //    return new SecureRandom();
        //}
    }

    public static void spewJVM(Logger log, String pfx) {

        Runtime rt = Runtime.getRuntime();
        log.debug(pfx + rt.availableProcessors() + " cores, " + Utils.kmg(rt.maxMemory()) + " max heap");

        var sb = new StringBuilder();
        for (String key : new String[] { "os.name", "os.arch", "os.version", ";",
                                         "java.vm.name", "java.vm.vendor", "java.vm.version", "java.vm.info", ";",
                                         "java.runtime.name", "java.runtime.version" }) {
            if (!Character.isLetter(key.charAt(0))) {
                sb.append(key);
            } else {
                String val = System.getProperty(key);
                if (val != null) {
                    if (sb.length() > 0) {
                        sb.append(" ");
                    }
                    sb.append(val);
                }
            }
        }
        log.debug(pfx + sb.toString());

        try {
            sb = new StringBuilder();
            sb.append("JVM arguments:");
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                sb.append(" ");
                sb.append(arg);
            }
            log.debug(pfx + sb.toString());
        } catch (Exception ex) {
            log.debug(pfx + "error getting JVM arguments: " + ex.getMessage());
        }

        try {
            sb = new StringBuilder();
            sb.append("GC:");
            for (var gcMxBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                sb.append(" ");
                sb.append(gcMxBean.getName());
            }
            log.debug(pfx + sb.toString());
        } catch (Exception ex) {
            log.debug(pfx + "error getting GC info: " + ex.getMessage());
        }
        var env = System.getenv();
        log.debug(pfx + "HOME: " + env.get("HOME"));

        try {
            var sr = getBestPRNG();
            log.debug(pfx + "SecureRandom provider: " + sr.getProvider() + ", algorithm: " + sr.getAlgorithm());
        } catch (Exception ex) {
            log.debug(pfx + "error getting SecureRandom info: " + ex.getMessage());
        }
    }

    public static void spewJVM(Logger log) {
        spewJVM(log, "");
    }

    public static void spewStack(Logger log, String pfx) {
        var st = (new Throwable()).getStackTrace();
        for (int i = 2; i < st.length; i++) {
            log.debug(pfx + st[i]);
        }
    }

    public static void spewStack(Logger log) {
        spewStack(log, "");
    }

    public static String getFileMD5(String path) throws IOException {
        try (var fs = new FileInputStream(path)) {
            return DigestUtils.md5Hex(fs);
        }
    }

    public static long fileLastModified(String path) throws IOException {
        return Files.getLastModifiedTime(Paths.get(path)).toMillis();
    }

    public static long diskFreeBytesSafe(String path) {
        try {
            return diskFreeBytes(path);
        } catch (IOException ex) {
            return 0;
        }
    }

    public static long diskFreeBytesSafe() {
        try {
            return diskFreeBytes();
        } catch (IOException ex) {
            return 0;
        }
    }

    public static long diskFreeBytes(String path) throws IOException {
        var p = Paths.get(path).toAbsolutePath().normalize();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p == null) {
            throw new FileNotFoundException(path);
        }
        return Files.getFileStore(p).getUsableSpace();
    }

    public static long diskFreeBytes() throws IOException {
        return diskFreeBytes(System.getProperty("user.dir"));
    }

    public static void dumpHeap() {
        try {
            var mxBean = ManagementFactory.newPlatformMXBeanProxy(ManagementFactory.getPlatformMBeanServer(),
                                                                  "com.sun.management:type=HotSpotDiagnostic",
                                                                  HotSpotDiagnosticMXBean.class);
            int i = 0;
            String filename = "dump" + i + ".hprof";
            while (Files.exists(Paths.get(filename))) {
                filename = "dump" + (++i) + ".hprof";
            }
            System.err.println("dumping heap to " + filename);
            mxBean.dumpHeap(filename, true);
        } catch (Exception ex) {
            System.err.println("error dumping heap: " + ex.getMessage());
        }
    }

    public static String toISO8601(long msSinceEpoch) {
        var df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date(msSinceEpoch));
    }

    public static long getLongField(Class clazz, Object obj, String name) throws Exception {
        var f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(obj);
    }

    public static long getLongField(Object obj, String name) throws Exception {
        return getLongField(obj.getClass(), obj, name);
    }

    public static void setLongField(Class clazz, Object obj, String name, long val) throws Exception {
        var f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(obj, val);
    }

    public static void setLongField(Object obj, String name, long val) throws Exception {
        setLongField(obj.getClass(), obj, name, val);
    }

    public static int getIntField(Class clazz, Object obj, String name) throws Exception {
        var f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(obj);
    }

    public static int getIntField(Object obj, String name) throws Exception {
        return getIntField(obj.getClass(), obj, name);
    }

    public static void setIntField(Class clazz, Object obj, String name, int val) throws Exception {
        var f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(obj, val);
    }

    public static void setIntField(Object obj, String name, int val) throws Exception {
        setIntField(obj.getClass(), obj, name, val);
    }

    public  static void setQuiet(Logger log) {
        setLogLevel(log, "OFF");
    }

    public  static void setLogLevel(Logger log, String level) {
        try {
            var clazz = log.getClass(); //org.slf4j.impl.SimpleLogger
            var cll = clazz.getDeclaredField("currentLogLevel");
            var llo = clazz.getDeclaredField("LOG_LEVEL_" + level);
            cll.setAccessible(true);
            llo.setAccessible(true);
            cll.set(log, llo.getInt(log));
        } catch (Exception ex) {
            log.error("error setting log level " + level, ex);
        }
    }

    public static boolean isAuto(String str) {
        return str == null || str.isEmpty() || str.toLowerCase().trim().equals("auto");
    }

    public static String fromWhence(Class clazz) {
        try {
            return clazz.getClassLoader().getResource(clazz.getName().replace(".", "/") + ".class").getPath();
        } catch (Throwable t) {
            return "error getting provenance of " + clazz.getName() + ": " + t.getMessage();
        }
    }

    public static String fromWhence(String className) {
        try {
            return fromWhence(Class.forName(className));
        } catch (Throwable t) {
            return "error getting provenance of " + className + ": " + t.getMessage();
        }
    }

    public static String fromWhence(LinkageError ex) {
        String msg = ex.getMessage();
        int lastDot = msg.lastIndexOf('.');
        if (lastDot > 0) {
            return fromWhence(msg.substring(0, lastDot));
        } else {
            return "error getting provenance of linkage error";
        }
    }

    public static boolean anyMatch(Pattern[] patterns, String str) {
        if (patterns == null || str == null) {
            return false;
        }
        for (int i = 0; i < patterns.length; i++) {
            if (patterns[i].matcher(str).matches()) {
                return true;
            }
        }
        return false;
    }

    public static Pattern[] parsePatternList(String list) throws PatternSyntaxException
    {
        if (list == null) {
            return new Pattern[0];
        }
        String[] regex = list.split(",");
        var patterns = new ArrayList<Pattern>();
        for (int i = 0; i < regex.length; i++) {
            if (regex[i].length() > 0) {
                try {
                    patterns.add(Pattern.compile(regex[i]));
                } catch (PatternSyntaxException ex) {
                    throw new PatternSyntaxException("error in pattern " + i + ": " + ex.getDescription(),
                                                     ex.getPattern(), ex.getIndex());
                }
            }
        }
        return patterns.toArray(new Pattern[0]); //https://stackoverflow.com/a/9572820
    }

    public static String getFilename(String path) {
        int sep = Math.max(path.lastIndexOf(File.separator), path.lastIndexOf('/')); //handle URL or file path
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    public static String prettyPrintJson(JsonStructure json) { //https://stackoverflow.com/a/60353419
        var config = new HashMap<String, Boolean>();
        config.put(JsonGenerator.PRETTY_PRINTING, true);
        var writer = new StringWriter();
        Json.createWriterFactory(config).createWriter(writer).write(json);
        return writer.toString();
    }

    public static String printJson(JsonStructure json, boolean pretty) {
        return pretty ? prettyPrintJson(json) : json.toString();
    }
}
