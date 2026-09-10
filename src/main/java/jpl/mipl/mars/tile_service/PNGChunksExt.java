package jpl.mipl.mars.tile_service;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import javax.imageio.stream.ImageInputStream;

/**
 * @author Marsette Vona
 */
class PNGChunksExt extends PNGChunks {

    public PNGChunksExt(DataInputStream dis) {
        super(dis);
    }

    public PNGChunksExt(InputStream is) {
        super(is);
    }

    public PNGChunksExt(ImageInputStream iis) {
        super(iis);
    }

    @Override public long bytesAvailable() throws IOException {
        if (input instanceof InputStream) {
            return ((InputStream)input).available();
        } else if (input instanceof ImageInputStream) {
            var iis = (ImageInputStream)input;
            long len = iis.length(); //LRUCacheImageInputStream implements length()
            if (len >= 0) {
                return len - iis.getStreamPosition();
            }
        }
        throw new IOException("cannot get bytes available for " + input.getClass().getName());
    }

    public ImageInfo parseMetadata(boolean maskBlack) throws IOException {
        int width = -1, height = -1, bands = -1, bytesPerSample = -1, significantBits = -1;
        float gamma = Float.NaN;
        boolean hasAlpha = false;
        if (!parseMagic()) return null;
        boolean done = false;
        while (!done && bytesAvailable() > 0) {
            int len = parseChunkLength();
            switch (parseChunkType()) {
                case "IHDR": {
                    width = parseInt();
                    height = parseInt();
                    significantBits = parseByte();
                    switch (parseByte()) {
                        case 0: { //grayscale
                            bands = 1;
                            bytesPerSample = significantBits > 8 ? 2 : 1;
                            break;
                        }
                        case 2: { //RGB
                            bands = 3;
                            bytesPerSample = significantBits > 8 ? 2 : 1;
                            break;
                        }
                        case 3: { //PLT
                            bands = 3;
                            significantBits = 8;
                            bytesPerSample = 1;
                            break;
                        }
                        case 4: { //gray + alpha
                            bands = 2;
                            bytesPerSample = significantBits > 8 ? 2 : 1;
                            hasAlpha = true;
                            break;
                        }
                        case 6:  { //RGBA
                            bands = 4;
                            bytesPerSample = significantBits > 8 ? 2 : 1;
                            hasAlpha = true;
                            break;
                        }
                        default: return null;
                    }
                    parseByte(); //compression
                    parseByte(); //filter
                    parseByte(); //interlace
                    break;
                }
                case "gAMA": {
                    gamma = parseFloat();
                    break;
                }
                case "sRGB": {
                    parseByte();
                    //gamma = 0.45f;
                    break;
                }
                case "IDAT": {
                    done = true;
                    break;
                }
                default: {
                    input.skipBytes(len);
                    break;
                }
            }
            if (!done) {
                input.readFully(buf, 0, 4); //CRC
            }
        }
        if (width > 0 && height > 0 && bands > 0 && bytesPerSample > 0) {
            return new ImageInfo(width, height, bands, bytesPerSample, significantBits, gamma, hasAlpha, maskBlack);
        }
        return null;
    }

    public ImageInfo parseMetadata() throws IOException {
        return parseMetadata(false);
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out.println("spew chunks: PNGChunksExt <inputFile>");
                System.out.println("spew metadata: PNGChunksExt <inputFile> -m");
                System.out.println("set gamma: PNGChunksExt <inputFile> -g N out.png");
                System.out.println("drop chunks: PNGChunksExt <inputFile> -d chunk1,chunk2,... out.png");
                return;
            }
            if (args.length > 1 && "-md".equals(args[1])) {
                try (var is = new FileInputStream(args[0])) {
                    var inst = new PNGChunksExt(is);
                    System.out.println(inst.parseMetadata().toString());
                }
            } else {
                PNGChunks.main(args);
            }
        } catch (Exception ex) {
            System.err.println(ex.toString());
        }
    }
}
