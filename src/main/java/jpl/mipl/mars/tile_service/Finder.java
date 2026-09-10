package jpl.mipl.mars.tile_service;

import jpl.mipl.mars.viewer.api.Constants;
import jpl.mipl.mars.viewer.api.EdrQuery;
import jpl.mipl.mars.viewer.api.EdrGroup;
import jpl.mipl.mars.viewer.api.RdrQuery;
import jpl.mipl.mars.viewer.api.SolRange;
import jpl.mipl.mars.viewer.util.TypeIdToName;
import jpl.mipl.mars.viewer.finder.MarsImageFileFinder;
import jpl.mipl.mars.viewer.finder.FileFinderException;
import jpl.mipl.mars.viewer.finder.util.DispatchableMarsImageFileFinder;
import jpl.mipl.mars.viewer.finder.mission.m20.M20OcsLocalImageFileFinder;
import jpl.mipl.mars.viewer.finder.mission.m20combined.M20OcsLocalUberImageFileFinder;
import jpl.mipl.mars.viewer.finder.mission.cadre.CadreOdsImageFileFinder;
import jpl.mipl.mars.viewer.image.config.ImageConfiguration;
import jpl.mipl.mars.viewer.image.config.ImageConfigurationSingleton;
import jpl.mipl.mars.viewer.image.config.ImageTypeLookupException;


import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.util.Calendar;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonArray;
import javax.json.JsonObjectBuilder;

/**
 * @author Marsette Vona
 */
public class Finder {

    public static final long DEF_MAX_SOL_RANGE_AGE_SEC = 5 * 60;
    public static long maxSolRangeAge = DEF_MAX_SOL_RANGE_AGE_SEC;

    public final String mission;
    public final String root, absoluteRoot;
    public final MarsImageFileFinder finder;
    public final ImageConfiguration imconf;

    private static Object solRangeLock = new Object();
    private static long solRangeTimestamp = -1;
    private static int startSol = -1, endSol = -1;

    public static boolean missionSupported(String mission) {
        switch (mission) {
            case "M20": return true;
            case "CADRE": return true;
            default: return false;
        }
    }

    public Finder(String mission, String root) throws IOException {
        try {
            this.mission = mission;
            this.root = root;
            absoluteRoot = Paths.get(root).toAbsolutePath().toString();
            switch (mission) {
                case "M20": {
                    //expects paths like this under fsInputDir
                    //00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.IMG
                    finder = new M20OcsLocalUberImageFileFinder(DziParams.fsInputDir);
                    break;
                }
                case "CADRE": {
                    //expects paths like this under fsInputDir
                    //3325/mipl/rdr/rover_a/AFLM_3325T1700630067_814XYZ_C00030_0LLJ13.VIC
                    finder = new CadreOdsImageFileFinder(DziParams.fsInputDir);
                    break;
                }
                default: throw new IllegalArgumentException("unsupported mission \"" + mission + "\"");
            }
            imconf = ImageConfigurationSingleton.getConfiguration();
        } catch (FileFinderException ex) {
            throw new IOException("error creating finder for mission \"" + mission + "\"", ex);
        }
    }

    public JsonObject getSolRange() throws IOException {
        var ret = Json.createObjectBuilder();
        synchronized (solRangeLock) {
            long now = System.currentTimeMillis();
            if (solRangeTimestamp < 0 || maxSolRangeAge <= 0 || now > solRangeTimestamp + (maxSolRangeAge * 1000L)) {
                updateSolRange(mission, finder, root);
            }
            ret.add("start", startSol);
            ret.add("end", endSol);
        }
        return ret.build();
    }

    private void updateSolRange(String mission, MarsImageFileFinder finder, String root) throws IOException {

        startSol = endSol = -1;

        var solRange = finder.getSolRange();

        if (solRange.isRangeDefined()) {
            startSol = solRange.getStart();
            endSol = solRange.getEnd();
        }

        int min = -1, max = -1, minDoy = -1, maxDoy = -1;
        String solPat = "\\d{5}", doyPat = "\\d{0,3}"; //let's consider the M20 way the default here
        switch (mission) {
            case "M20": {
                min = 0;
                max = M20OcsLocalImageFileFinder.SOL_MAX;
                minDoy = M20OcsLocalImageFileFinder.SOL_DOY_MIN;
                maxDoy = M20OcsLocalImageFileFinder.SOL_DOY_MAX;
                break;
            }
            case "CADRE": {
                min = 0;
                max = CadreOdsImageFileFinder.SOL_MAX;
                solPat = doyPat = "\\d{4}";
                minDoy = 3000;
                maxDoy = 9365;
                break;
            }
        }

        String patStr = SolRange.DOY_NAME.equals(solRange.getName()) ? doyPat : solPat;
        var pat = patStr != null ? Pattern.compile(patStr) : null;

        if (pat != null &&
            (!solRange.isRangeDefined() || startSol < 0 || endSol < 0 || startSol > endSol ||
             startSol == SolRange.UNK_SOL || endSol == SolRange.UNK_SOL ||
             startSol == SolRange.NULL_SOL || endSol == SolRange.NULL_SOL ||
             (startSol == min && endSol == max) || (startSol == minDoy && endSol == maxDoy))) {
            startSol = endSol = -1;
            try (var ds = Files.newDirectoryStream(FileSystems.getDefault().getPath(root), Files::isDirectory)) {
                for (var p : ds) {
                    String dirName = p.getFileName().toString();
                    if (pat.matcher(dirName).matches()) {
                        try {
                            int sol = Integer.parseInt(dirName);
                            if (startSol < 0 || sol < startSol) {
                                startSol = sol;
                            }
                            if (endSol < 0 || sol > endSol) {
                                endSol = sol;
                            }
                        } catch (NumberFormatException ex) {
                            //should not happen, patterns should ensure dirName is an int
                            throw new IOException("error parsing \"" + dirName + "\" as an integer");
                        }
                    }
                }
            }
        }

        solRangeTimestamp = System.currentTimeMillis();
    }

    public JsonObject getEDR(String path) throws IOException {
        try {
            return getMetadata(path, "edr", Constants.PRODUCT_TYPE_EDR);
        } catch (FileFinderException | ImageTypeLookupException ex) {
            throw new IOException("error getting metadata for " + path, ex);
        }
    }

    public JsonObject getRDR(String path) throws IOException {
        try {
            return getMetadata(path, "rdr", Constants.PRODUCT_TYPE_RDR);
        } catch (FileFinderException | ImageTypeLookupException ex) {
            throw new IOException("error getting metadata for " + path, ex);
        }
    }

    public JsonArray getEDRs(int sol) throws IOException {
        try {
            var query = new EdrQuery();
            query.setSol(sol);
            var result = finder.queryEdrs(query);
            var files = result.getFiles();
            var ret = Json.createArrayBuilder();
            if (files != null) {
                for (var file : files) {
                    ret.add(getMetadata(file, "edr", Constants.PRODUCT_TYPE_EDR));
                }
            }
            return ret.build();
        } catch (FileFinderException | ImageTypeLookupException ex) {
            throw new IOException("error getting EDRs for sol " + sol, ex);
        }
    }

    public JsonArray getRDRs(String edrPath) throws IOException {
        try {
            String fullPath = Paths.get(root, edrPath).toString();
            var query = new RdrQuery(new EdrGroup(finder, List.of(fullPath)));
            if (finder instanceof CadreOdsImageFileFinder) {
                String sol = ((CadreOdsImageFileFinder)finder).extractCycleYdoy(edrPath);
                try {
                    query.setSol(Integer.parseInt(sol));
                } catch (NumberFormatException ex) {
                    throw new IOException("error parsing sol string \"" + sol + "\"", ex);
                }
            }
            var result = finder.queryRdrs(query);
            var files = result.getRdrsFor(fullPath);
            var ret = Json.createArrayBuilder();
            if (files != null) {
                for (var file : files) {
                    ret.add(getMetadata((String)file, "rdr", Constants.PRODUCT_TYPE_RDR));
                }
            }
            return ret.build();
        } catch (FileFinderException | ImageTypeLookupException ex) {
            throw new IOException("error getting RDRs for EDR \"" + edrPath + "\"", ex);
        }
    }

    private String stripRoot(String path) {
        if (path.startsWith(root)) {
            path = path.substring(root.length());
        }
        if (path.startsWith(absoluteRoot)) {
            path = path.substring(absoluteRoot.length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private JsonObject getMetadata(String path, String what, short pt)
        throws FileFinderException, ImageTypeLookupException, IOException {

        path = stripRoot(path);

        //based on code in DataDrive-OCS-Lambda-Ingester jpl.mipl.mars.tika.parser.vicars.VicarUtils

        String filename = finder.extractFilename(path);
        String ns = finder.getProductNamespace();
        String productType = finder.extractImageType(filename, pt);
        String imageType = imconf.getImageTypeId(ns, productType);
        String ft = ((finder instanceof DispatchableMarsImageFileFinder) ?
                     ((DispatchableMarsImageFileFinder)finder).getMatchingFileFinder(filename) : finder)
            .getType().toUpperCase();
        String finderType = ft.contains("TILE") ? "Tile" : ft.contains("MOSAIC") ? "Mosaic" : "Single Frame";
        String sclk = finder.extractSclk(filename);

        var md = Json.createObjectBuilder();

        md.add("finder_type", finderType);
        md.add("instrument_id", finder.extractInstrumentId(filename));
        md.add("instrument", finder.extractInstrument(filename, pt));
        md.add("instrument_category", finder.extractInstrumentCategory(filename));
        md.add("product_type", productType);
        md.add("group_id", finder.getGroupId(filename));
        md.add("overlay_id", finder.getOverlayId(filename));
        md.add("is_source_product", finder.isTypeSource(productType));
        md.add("overlayable", imconf.getImageLookup().isOverlayable(imageType));
        md.add("eye_type", TypeIdToName.getEyeName(finder.extractEyeType(filename, pt)));
        md.add("size_type", TypeIdToName.getSizeName(finder.extractSizeType(filename)));
        md.add("projection", TypeIdToName.getProjectionName(finder.extractProjectionType(filename)));
        md.add("geometry", TypeIdToName.getGeometryName(finder.extractGeometryType(filename)));
        md.add("stage", TypeIdToName.getStageName(imconf.getProductContext(ns).getStageType(productType)));
        md.add("version", finder.extractVersion(filename, pt));
        md.add("sclk", sclk != null ? sclk : "NULL");
        md.add("image_type", imageType);
        md.add("description", imconf.getProductDescription(ns, productType));
        md.add("supplemental_description", finder.getUIContext().getSupplementalDescription(filename));

        //as of 6/26/23 Nick Toole confirms that current MV FileFinder API does not generally implement SOL parsing
        //for now we shim it in here based in part on mission specific code in
        //DataDrive-OCS-Lambda-Ingester jpl.mipl.mars.tika.parser.vicars.VicarUtils
        String sol = null;
        try {
            switch (mission) {
                case "M20": {
                    sol = "Mosaic".equals(finderType) ? filename.substring(7, 11) : filename.substring(4, 8);
                    if (Character.isLetter(sol.charAt(0))) {
                        md.add("sol", Integer.parseInt(sol.substring(1)));
                        md.add("sol_doy", true);
                        md.add("year", yearCharToInt(sol.charAt(0)));
                    } else if (Character.isLetter(sol.charAt(sol.length() - 1))) {
                        md.add("sol", Integer.parseInt(sol.substring(0, sol.length() - 1)));
                        md.add("sol_doy", true);
                        md.add("year", yearCharToInt(sol.charAt(sol.length() - 1)));
                    } else {
                        md.add("sol", Integer.parseInt(sol));
                        md.add("sol_doy", false);
                        md.add("year", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
                    }
                    break;
                }
                case "CADRE": {
                    //CADRE Image Filename Spec
                    //https://docs.google.com/document/d/1fzfoIWHC0C_05bdYG2pKrMfQ6NTvr74XDSGHiLQx3zs
                    sol = ((CadreOdsImageFileFinder)finder).extractCycleYdoy(filename);
                    int n = Integer.parseInt(sol);
                    md.add("wake_cycle_or_ydoy", n);
                    if (n >= 3000) {
                        md.add("sol", Integer.parseInt(sol.substring(1)));
                        md.add("sol_doy", true);
                        md.add("year", yearCharToIntCADRE(sol.charAt(0)));
                    } else {
                        md.add("sol", Integer.parseInt(sol));
                        md.add("sol_doy", false);
                        md.add("year", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
                    }
                    break;
                }
                default: {
                    md.add("sol", -1);
                    md.add("sol_doy", false);
                    md.add("year", Integer.toString(Calendar.getInstance().get(Calendar.YEAR)));
                    break;
                }
            }
        } catch (NumberFormatException ex) {
            throw new IOException("error parsing sol string \"" + sol + "\"", ex);
        }

        var ret = Json.createObjectBuilder();
        ret.add(what, path);
        ret.add("metadata", md);
        return ret.build();
    }

    private static int yearCharToInt(char yearChar) {
        return (yearChar >= 'A' && yearChar <= 'Z') ? 2017 + (yearChar - 'A') : -1;
    }

    private static int yearCharToIntCADRE(char yearChar) {
        return (yearChar >= '3' && yearChar <= '9') ? 2023 + (yearChar - '3') : -1;
    }

    public JsonObject getVicarLabel(String path) throws IOException {

        //based on code in mis_rest_service jpl.mipl.mars.mis.SparseImage
        //but this is more complete including handling EOL and array values

        var headers = new ArrayList<VicarHeader>();
        try (var raf = new RandomAccessFile(Paths.get(root, path).toFile(), "r")) {

            long labelStartByte = -1, imageStartByte = -1;
            int labelLength = -1;
            String magic = readString(raf, 0, 8);
            switch (magic) {
                case "LBLSIZE ": case "LBLSIZE=": { //VICAR with no PDS/ODL wrapper
                    labelStartByte = 0;
                    labelLength = parseVicarLabelLength(raf, labelStartByte);
                    imageStartByte = labelStartByte + labelLength;
                    break;
                }
                case "ODL_VERS": case "PDS_VERS": { //PDS/ODL wrapper
                    var starts = parsePDSHeader(raf);
                    labelStartByte = starts[0];
                    labelLength = parseVicarLabelLength(raf, labelStartByte);
                    imageStartByte = starts[1];
                    break;
                }
                default: throw new IOException("unrecognized start \"" + magic + "\" of VICAR or PDS/ODL file");
            }

            parseVicarHeaders(readString(raf, labelStartByte, labelLength), headers);

            String org = "BSQ";
            boolean eol = false;
            int rs = 0, nlb = 0;
            int nb = 0, nl = 0, ns = 0;
            int n2 = -1, n3 = -1;
            boolean done = false;
            for (var h : headers) {
                switch (h.name) {
                    case "ORG": org = h.toString(); break;
                    case "EOL": eol = h.toInt() == 1; break;
                    case "RECSIZE": rs = h.toInt(); break;
                    case "NLB": nlb = h.toInt(); break;
                    case "NB": nb = h.toInt(); break;
                    case "NL": nl = h.toInt(); break;
                    case "NS": ns = h.toInt(); break;
                    case "N2": n2 = h.toInt(); break;
                    case "N3": n3 = h.toInt(); break;
                    case "SYSTEM": case "TASK": done = true; break;
                }
                if (done) {
                    break;
                }
            }

            if (eol) {
                boolean bsq = org.equals("BSQ"); // N1=Samples, N2=Lines,   N3=Bands
                boolean bil = org.equals("BIL"); // N1=Samples, N2=Bands,   N3=Lines
                boolean bip = org.equals("BIP"); // N1=Bands,   N2=Samples, N3=Lines
                if (!bsq && !bil && !bip) {
                    throw new IOException("unrecognized VICAR organization: \"" + org + "\"");
                }
                if (n2 < 0) {
                    n2 = bsq ? nl : bil ? nb : ns;
                }
                if (n3 < 0) {
                    n3 = bsq ? nb : nl;
                }
                labelStartByte = imageStartByte + rs * (nlb + (n2 * n3));
                labelLength = parseVicarLabelLength(raf, labelStartByte);
                parseVicarHeaders(readString(raf, labelStartByte, labelLength), headers);
            }
        }

        var system = Json.createObjectBuilder();
        var properties = Json.createObjectBuilder();
        var tasks = Json.createArrayBuilder();
        String currPropName = null;
        JsonObjectBuilder currProp = null, currTask = null;
        for (var h : headers) {
            if ("PROPERTY".equals(h.name)) {
                if (currTask != null) {
                    throw new IOException("invalid VICAR header: PROPERTY after TASK");
                }
                if (currPropName != null) {
                    properties.add(currPropName, currProp);
                }
                currPropName = h.toString();
                currProp = Json.createObjectBuilder();
            } else if ("TASK".equals(h.name)) {
                if (currPropName != null) {
                    properties.add(currPropName, currProp);
                    currPropName = null;
                    currProp = null;
                }
                if (currTask != null) {
                    tasks.add(currTask);
                }
                currTask = Json.createObjectBuilder();
                currTask.add("TASK", h.toString());
            } else if (currProp != null) {
                h.toJson(currProp);
            } else if (currTask != null) {
                h.toJson(currTask);
            } else {
                h.toJson(system);
            }
        }

        var ret = Json.createObjectBuilder();
        ret.add("system", system);
        ret.add("properties", properties);
        ret.add("tasks", tasks);
        return ret.build();
    }

    private static String readString(RandomAccessFile raf, long start, int length) throws IOException {
        var buf = new byte[length];
        raf.seek(start);
        raf.readFully(buf);
        return new String(buf, "UTF-8");
    }

    private static int parseInt(String str, String name) throws IOException {
        if (str == null) {
            throw new IOException("header not found: " + name);
        }
        try {
            str = str.trim();
            if (str.length() > 0) {
                return Integer.parseInt(str.split("\\s+")[0]); //parse first whitespace separated token
            } else {
                return 0;
            }
        } catch (NumberFormatException ex) {
            throw new IOException("error parsing header \"" + name + "\" as integer", ex);
        }
    }

    //read the 100 chars after "LBLSIZE=" or "LBLSIZE "
    //these *shold* include the full integer string of the VICAR label size, possibly plus subsequent tokens 
    //note that it is also possible for there to be an unlimited amount of whitespace before and after the =
    //that seprates LBLSIZE from its value, so this could fail on a valid VICAR header in such a case
    private static int parseVicarLabelLength(RandomAccessFile raf, long labelStart) throws IOException {
        String str = readString(raf, labelStart + 8, 100).trim();
        if (str.startsWith("=")) {
            str = str.substring(1);
        }
        return parseInt(str, "LBLSIZE");
    }
    
    private static long[] parsePDSHeader(RandomAccessFile raf) throws IOException {
        String recordType = null;
        long recordBytes = 0;
        long imageHeaderRecord = 0;
        long imageRecord = 0;
        int lineNumber = 1;
        String line = null;
        while ((line = readPDSLine(raf)) != null) {
            line = line.trim();
            if ("END".equals(line)) {
                break;
            }
            String[] tok = line.split("=");
            if (tok.length == 2) {
                String key = tok[0].trim();
                String val = tok[1].trim();
                switch (key) {
                case "RECORD_TYPE": recordType = val; break;
                case "RECORD_BYTES": recordBytes = parseInt(val, "RECORD_BYTES"); break;
                case "^IMAGE_HEADER": imageHeaderRecord = parseInt(val, "^IMAGE_HEADER"); break;
                case "^IMAGE": imageRecord = parseInt(val, "^IMAGE"); break;
                }
            }
            if (recordType != null && recordBytes > 0 && imageHeaderRecord > 0 && imageRecord > 0) {
                break;
            }
            if (++lineNumber >= 100) {
                break;
            }
        }
        if (recordType == null) {
            throw new IOException("missing PDS/ODL header RECORD_TYPE");
        }
        if (!"FIXED_LENGTH".equals(recordType)) {
            throw new IOException("unsupported PDS/ODL RECORD_TYPE " + recordType);
        }
        if (recordBytes <= 0) {
            throw new IOException("missing PDS/ODL header RECORD_BYTES");
        }
        if (imageHeaderRecord <= 0) {
            throw new IOException("missing PDS/ODL header ^IMAGE_HEADER");
        }
        if (imageRecord <= 0) {
            throw new IOException("missing PDS/ODL header ^IMAGE");
        }
        return new long[] { recordBytes * (imageHeaderRecord - 1), //Vicar label start byte
                            recordBytes * (imageRecord - 1) }; //image start byte
    }

    //PDS/ODL statements can span lines when the value is a quoted string, sequence or set
    //this impl doesn't handle those cases, only numeric values
    //this impl is still a little broken in that a valid PDS file could exist where
    //e.g. a string could contain RECORD_TYPE=FOO on its own line
    //but yeah, mis_rest_service doesn't handle that either...
    //(note that PDS comments can't span lines, and must appear at the end of a line)
    private static String readPDSLine(RandomAccessFile raf) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; true; i++) {
            int c = raf.readUnsignedByte();
            if (c == '\r' || c == '\n' || c == 11 /* vertical tab */ || c == 12 /* form feed */) {
                //https://pds.jpl.nasa.gov/datastandards/pds3/standards/sr/Chapter12.pdf
                //pds statements *should* be delimited by \r\n
                //but *can* be delimited by any sequence of "format effectors"
                //which are newline, carriage return, vertical tab, or form feed
                //if we're reading such a character at the beginning of a line
                //it's still part of the ending of the previous line, or the line is empty
                if (sb.length() > 0) { //got first "format effector" char at end of line
                    break; 
                }
            } else if (sb.length() < 100) { //this impl only needs to handle short lines, discard suffix of long line
                sb.append((char)c);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static class VicarHeader {

        public final String name;
        public final Object val;

        public VicarHeader(String name, Object val) {
            this.name = name;
            this.val = val;
        }

        public String toString() {
            return (val instanceof String) ? (String)val : val.toString();
        }

        public int toInt() throws IOException {
            Throwable err = null;
            if (val instanceof Integer) {
                return ((Integer)val).intValue();
            } else if (val instanceof Double) {
                double d = ((Double)val).doubleValue();
                int i = (int)d;
                if (i == d) {
                    return i;
                }
            } else if (val instanceof String) {
                try {
                    return Integer.parseInt((String)val);
                } catch (NumberFormatException ex) {
                    err = ex;
                }
            }
            throw new IOException("error parsing VICAR header \"" + name + "\"+ as integer", err);
        }

        public void toJson(JsonObjectBuilder builder) throws IOException {
            if (val instanceof String) {
                builder.add(name, (String)val);
            } else if (val instanceof Integer) {
                builder.add(name, (Integer)val);
            } else if (val instanceof Double) {
                builder.add(name, (Double)val);
            } else if (val instanceof String[]) {
                var ab = Json.createArrayBuilder();
                for (String v : (String[])val) {
                    ab.add(v);
                }
                builder.add(name, ab);
            } else if (val instanceof Integer[]) {
                var ab = Json.createArrayBuilder();
                for (int v : (Integer[])val) {
                    ab.add(v);
                }
                builder.add(name, ab);
            } else if (val instanceof Double[]) {
                var ab = Json.createArrayBuilder();
                for (double v : (Double[])val) {
                    ab.add(v);
                }
                builder.add(name, ab);
            } else {
                throw new IOException("unsupported type " + val.getClass().getSimpleName() +
                                      " for VICAR header \"" + name + "\"");
            }
        }
    }

    private static String unquote(String val) {
        val = val.substring(1, val.length() - 1);
        val = val.replace("''", "'");
        return val;
    }

    private static void parseVicarHeaders(String txt, List<VicarHeader> headers) throws IOException {

        //https://www-mipl.jpl.nasa.gov/external/VICAR_file_fmt.pdf
        //"[VICAR] Keywords are strings, up to 32 characters in length,
        //and consist of uppercase characters, underscores (_), and numbers (but should start with a letter)"

        String spat   = "'(?:[^']|(?:''))*'"; //single quoted string with 0 or more escaped single quotes
        String ipat = "[+-]?\\d+"; //integer
        String dpat = "[+-]?\\d*[.]\\d*(?:[EeDd][+-]?\\d+)?"; //double
        String aspat = "\\(\\s*(?:" + spat + "\\s*,\\s*)*(?:" + spat + ")?\\s*\\)"; //parenthesized array of strings
        String aipat = "\\(\\s*(?:" + ipat + "\\s*,\\s*)*(?:" + ipat + ")?\\s*\\)"; //parenthesized array of integers
        String adpat = "\\(\\s*(?:" + dpat + "\\s*,\\s*)*(?:" + dpat + ")?\\s*\\)"; //parenthesized array of doubles

        var matcher = Pattern.compile("\\s*(\\w+)\\s*=\\s*(?:" + //\w is [a-zA-Z_0-9], group 1
                                      "(" +  spat + ")|(" +  ipat + ")|(" +  dpat + ")|" + //groups 2,3,4
                                      "(" + aspat + ")|(" + aipat + ")|(" + adpat + "))").matcher(txt); //groups 5,6,7
        
        while (matcher.find()) {
            String name = matcher.group(1);
            String sval = matcher.group(2);
            String ival = matcher.group(3);
            String dval = matcher.group(4);
            String asval = matcher.group(5);
            String aival = matcher.group(6);
            String adval = matcher.group(7);
            String val = null;
            try {
                if ("LBLSIZE".equals(name)) {
                    continue;
                } else if (sval != null) {
                    val = sval;
                    headers.add(new VicarHeader(name, unquote(sval)));
                } else if (ival != null) {
                    val = ival;
                    headers.add(new VicarHeader(name, Integer.parseInt(ival)));
                } else if (dval != null) {
                    val = dval;
                    headers.add(new VicarHeader(name, Double.parseDouble(dval)));
                } else if (asval != null) {
                    val = asval;
                    var am =
                        Pattern.compile("\\s*(" + spat + ")\\s*,?").matcher(asval.substring(1, asval.length() - 1));
                    var l = new ArrayList<String>();
                    while (am.find()) {
                        l.add(unquote(am.group(1)));
                    }
                    headers.add(new VicarHeader(name, l.toArray(new String[l.size()])));
                } else if (aival != null) {
                    val = aival;
                    var am =
                        Pattern.compile("\\s*(" + ipat + ")\\s*,?").matcher(aival.substring(1, aival.length() - 1));
                    var l = new ArrayList<Integer>();
                    while (am.find()) {
                        l.add(Integer.parseInt(am.group(1)));
                    }
                    headers.add(new VicarHeader(name, l.toArray(new Integer[l.size()])));
                } else if (adval != null) {
                    val = adval;
                    var am =
                        Pattern.compile("\\s*(" + dpat + ")\\s*,?").matcher(adval.substring(1, adval.length() - 1));
                    var l = new ArrayList<Double>();
                    while (am.find()) {
                        l.add(Double.parseDouble(am.group(1)));
                    }
                    headers.add(new VicarHeader(name, l.toArray(new Double[l.size()])));
                }
            } catch (NumberFormatException ex) {
                throw new IOException("error parsing number from \"" + val + "\" in VICAR header \"" + name + "\"", ex);
            }
        }
    }
}
