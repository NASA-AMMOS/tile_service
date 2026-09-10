package jpl.mipl.mars.tile_service;

import java.util.List;
import java.util.Arrays;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * @author Marsette Vona
 */
class PNGChunks {

    protected final DataInput input;

    protected final byte[] buf = new byte[32768];

    public PNGChunks(DataInput di) {
        input = di;
    }

    public PNGChunks(DataInputStream dis) {
        input = dis;
    }

    public PNGChunks(InputStream is) {
        this(new DataInputStream(is));
    }

    public long bytesAvailable() throws IOException {
        if (input instanceof InputStream) {
            return ((InputStream)input).available();
        }
        throw new IOException("cannot get bytes available for " + input.getClass().getName());
    }

    public boolean parseMagic() throws IOException {
        input.readFully(buf, 0, 8);
        return
            buf[0] == (byte)0x89 &&
            buf[1] == (byte)'P' &&
            buf[2] == (byte)'N' &&
            buf[3] == (byte)'G' &&
            buf[4] == (byte)0x0d &&
            buf[5] == (byte)0x0a &&
            buf[6] == (byte)0x1a &&
            buf[7] == (byte)0x0a;
    }

    public int parseChunkLength() throws IOException {
        input.readFully(buf, 0, 4);
        return ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public String parseChunkType() throws IOException {
        input.readFully(buf, 0, 4);
        return new String(buf, 0, 4, StandardCharsets.UTF_8);
    }

    public float parseFloat() throws IOException {
        input.readFully(buf, 0, 4);
        return Integer.toUnsignedLong(ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()) / 1e5f;
    }

    public int parseInt() throws IOException {
        input.readFully(buf, 0, 4);
        return ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public int parseByte() throws IOException {
        input.readFully(buf, 0, 1);
        return Byte.toUnsignedInt(buf[0]);
    }

    public String spewChunk(String type) throws IOException {
        switch (type) {
            case "IHDR": {
                String ret = "w=" + parseInt() + " h=" + parseInt() + " d=" + parseByte();
                ret += " color=";
                switch (parseByte()) {
                    case 0: ret += "gray"; break;
                    case 2: ret += "RGB"; break;
                    case 3: ret += "PLT"; break;
                    case 4: ret += "gray + alpha"; break;
                    case 6: ret += "RGBA"; break;
                    default: ret += "(invalid)"; break;
                }
                ret += " compression=" + parseByte();
                ret += " filter=" + parseByte();
                ret += " interlace=";
                switch (parseByte()) {
                    case 0: ret += "none"; break;
                    case 1: ret += "Adam7"; break;
                    default: ret += "(invalid)"; break;
                }
                return ret;
            }
            case "gAMA": return "gamma=" + parseFloat();
            case "cHRM": return
                "white=(" + parseFloat() + ", " + parseFloat() + ")" +
                " red=(" + parseFloat() + ", " + parseFloat() + ")" +
                " green=(" + parseFloat() + ", " + parseFloat() + ")" +
                " blue=(" + parseFloat() + ", " + parseFloat() + ")";
            case "sRGB": {
                switch (parseByte()) {
                    case 0: return "Perceptual";
                    case 1: return "Relative colorimetric";
                    case 2: return "Saturation";
                    case 3: return "Absolute colorimetric";
                    default: return "(invalid)";
                }
            }
        }
        return null;
    }

    public void spewChunks() throws IOException {
        if (!parseMagic()) throw new IOException("invalid PNG magic");
        int n = 0;
        while (bytesAvailable() > 0) {
            int len = parseChunkLength();
            String type = parseChunkType();
            String val = spewChunk(type);
            if (val == null) input.skipBytes(len);
            input.readFully(buf, 0, 4); //read CRC
            System.out.format("chunk %d: %s%s %d bytes crc %02X%02X%02X%02X\n", n++, type,
                              val != null ? (" " + val) : "", len, buf[0], buf[1], buf[2], buf[3]);
        }
    }

    public void filter(OutputStream os, List<String> dropChunks, float setGamma) throws IOException {

        DataOutputStream output = new DataOutputStream(os);

        if (!parseMagic()) throw new IOException("invalid PNG magic");
        output.write(buf, 0, 8); //write magic

        boolean wroteGamma = false;
        CRC32 crc = new CRC32();

        while (bytesAvailable() > 0) {

            int len = parseChunkLength();
            String type = parseChunkType();

            if (dropChunks == null || !dropChunks.contains(type)) {

                crc.reset();

                if (!Float.isNaN(setGamma) && !wroteGamma && type.equals("IDAT")) {

                    //write chunk data length
                    ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(4);
                    output.write(buf, 0, 4);
                    //crc.update(buf, 0, 4); //CRC is not computed on length

                    //write chunk type
                    byte[] raw = "gAMA".getBytes(StandardCharsets.UTF_8);
                    output.write(raw);
                    crc.update(raw);

                    //write gamma value
                    ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.BIG_ENDIAN).putLong(Math.round(setGamma * 1e5));
                    output.write(buf, 4, 4);
                    crc.update(buf, 4, 4);

                    //write CRC
                    ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.BIG_ENDIAN).putLong(crc.getValue());
                    output.write(buf, 4, 4);

                    wroteGamma = true;
                    crc.reset();
                }

                //copy chunk length
                ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(len);
                output.write(buf, 0, 4);
                //crc.update(buf, 0, 4); //CRC is not computed on length

                //copy chunk type
                byte[] raw = type.getBytes(StandardCharsets.UTF_8);
                crc.update(raw);
                output.write(raw);

                if (Float.isNaN(setGamma) || !type.equals("gAMA")) { //copy chunk data and CRC
                    for (int nr = len + 4, nc = 0; nr > 0; nr -= nc) {
                        nc = Math.min(nr, buf.length);
                        input.readFully(buf, 0, nc);
                        output.write(buf, 0, nc);
                    }
                } else { //change gamma chunk

                    input.skipBytes(len + 4); //skip data and CRC

                    //write gamma value
                    ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.BIG_ENDIAN).putLong(Math.round(setGamma * 1e5));
                    output.write(buf, 4, 4);
                    crc.update(buf, 4, 4);

                    //write CRC
                    ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.BIG_ENDIAN).putLong(crc.getValue());
                    output.write(buf, 4, 4);

                    wroteGamma = true;
                }
            } else input.skipBytes(len + 4); //skip data and CRC
        }
    }
        
    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out.println("spew chunks: PNGChunks <inputFile>");
                System.out.println("set gamma: PNGChunks <inputFile> -g N out.png");
                System.out.println("drop chunks: PNGChunks <inputFile> -d chunk1,chunk2,... out.png");
                return;
            }
            try (FileInputStream is = new FileInputStream(args[0])) {
                PNGChunks inst = new PNGChunks(is);
                if (args.length == 4 && "-g".equals(args[1])) {
                    float gamma = Float.parseFloat(args[2]);
                    System.out.println("setting gamma " + gamma);
                    try (FileOutputStream os = new FileOutputStream(args[3])) {
                        inst.filter(os, null, gamma);
                    }
                } else if (args.length == 4 && "-d".equals(args[1])) {
                    List<String> chunks = Arrays.asList(args[2].split(","));
                    System.out.println("dropping chunks: " + String.join(",", chunks));
                    try (FileOutputStream os = new FileOutputStream(args[3])) {
                        inst.filter(os, chunks, Float.NaN);
                    }
                } else {
                    inst.spewChunks();
                }
            }
        } catch (Exception ex) {
            System.err.println(ex.toString());
        }
    }
}
