package jpl.mipl.mars.tile_service;

import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.Image;
import java.awt.image.RenderedImage;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.awt.image.ColorModel;
import java.awt.image.SampleModel;
import java.awt.image.Raster;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.media.jai.JAI;
import javax.media.jai.TileCache;

import org.slf4j.Logger;

/**
 * Out-of-core image implementation that loads one rectangular window at a time.
 *
 * Uses subframes to allow handling images that may be larger than Java can normally load
 * (https://bugs.openjdk.java.net/browse/JDK-8078589).
 *
 * The entire image is considered a grid of tileWidth x tileHeight rectangular tiles.  The tiles on the right and bottom
 * borders of the image may be smaller.
 *
 * Rectangular windows are then imposed as a higher-level grid aligned to the tile grid.  For example, every 4x4 group
 * of tiles may form a window.  The windows on the right and bottom borders of the image may be smaller.
 *
 * No data is loaded until a request is made.  Once a window of data is loaded it is cached in memory.  The last loaded
 * window is deallocated on close().  If a data request cannot be served entirely from the currently loaded window other
 * windows are loaded as necessary.  Each window is loaded as a separate subframe from the source ImageReader.
 *
 * The size and shape of the tile window may affect performance depending on the patterns of data access.  JAI
 * algorithms which process tiles in raster order may benefit from a rectangular window equal to one or more full rows
 * of the image.  Recursive quadtree algorithms such as the DZI tile builder may benefit from a square window equal to
 * one of the tile pyramid levels.
 *
 * An additional margin of zero or more pixels is optionally loaded with each window of tiles.
 *
 * Loaded tiles from getTile() are also optionally cached in the JAI tile cache.
 *
 * By default getData() returns data copied into a newly allocated WritableRaster.  Call setAllowDataSharing(true) to
 * allow it to return Raster objects that wrap the underlying window data buffer.  In that case if calling code is not
 * careful that may prevent garbage collection of windows.
 *
 * Thread safe by synchronization.
 **/
public class TileWindowImage implements RenderedImage, AutoCloseable {

    private final ImageReader reader;
    private final int imageIndex;
    private final ImageReadParam readParam;
    private final int width;
    private final int height;
    private final int windowTilesX;
    private final int windowTilesY;
    private final int tileWidth;
    private final int tileHeight;
    private final int windowMarginPixels;
    private final int numXTiles;
    private final int numYTiles;
    private final Map<String,Object> properties = new HashMap<String,Object>();
    private final Vector<RenderedImage> sources = new Vector<RenderedImage>();

    private BufferedImage loadedWindow = null;
    private Rectangle loadedWindowPixelRect = null;

    private boolean allowDataSharing;
    private TileCache jaiCache;

    private Logger log;
    private String pfx;

    public TileWindowImage(ImageReader reader, int imageIndex, ImageReadParam param, int width, int height,
                           int windowTilesX, int windowTilesY, int tileWidth, int tileHeight, int windowMarginPixels) {
        this.reader = reader;
        this.imageIndex = imageIndex;
        readParam = param != null ? param : reader.getDefaultReadParam();
        this.width = width;
        this.height = height;
        this.windowTilesX = windowTilesX;
        this.windowTilesY = windowTilesY;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.windowMarginPixels = windowMarginPixels;
        numXTiles = (int)Math.ceil(((float)width) / tileWidth);
        numYTiles = (int)Math.ceil(((float)height) / tileHeight);
    }
    
    public TileWindowImage(ImageReader reader, ImageReadParam param, int width, int height,
                           int windowTilesX, int windowTilesY, int tileWidth, int tileHeight, int windowMarginPixels) {
        this(reader, 0, param, width, height, windowTilesX, windowTilesY, tileWidth, tileHeight, windowMarginPixels);
    }

    public TileWindowImage(ImageReader reader, int width, int height,
                           int windowTilesX, int windowTilesY, int tileWidth, int tileHeight, int windowMarginPixels) {
        this(reader, null, width, height, windowTilesX, windowTilesY, tileWidth, tileHeight, windowMarginPixels);
    }

    public TileWindowImage(ImageReader reader, int width, int height, int windowTilesX, int windowTilesY,
                           int tileWidth, int tileHeight) {
        this(reader, width, height, windowTilesX, windowTilesY, tileWidth, tileHeight, 0);
    }

    public TileWindowImage(ImageReader reader, int width, int height, int windowTiles, int tileSize) {
        this(reader, width, height, windowTiles, windowTiles, tileSize, tileSize);
    }

    public synchronized WritableRaster copyData(WritableRaster raster) {
        if (raster == null) {
            loadWindow(0, 0, "copyData(null)");
            raster = loadedWindow.getRaster().createCompatibleWritableRaster(width, height);
        }
        return copyData(raster, getWindowRange(raster.getBounds()));
    }

    private synchronized WritableRaster copyData(WritableRaster raster, Point[] windowRange) {
        Rectangle rect = raster.getBounds();
        for (int x = (int)(windowRange[0].getX()); x <= (int)(windowRange[1].getX()); x++) {
            for (int y = (int)(windowRange[0].getY()); y <= (int)(windowRange[1].getY()); y++) {
                loadWindow(x, y, "copyData() " + toString(rect));
                var srcRect = loadedWindowPixelRect.intersection(rect);
                var dstRect = new Rectangle(srcRect);
                var ulc = loadedWindowPixelRect.getLocation();
                srcRect.translate((int)(-ulc.getX()), (int)(-ulc.getY()));
                var srcRaster = loadedWindow.getData(srcRect).createTranslatedChild(0, 0);
                raster.setDataElements((int)(dstRect.getX()), (int)(dstRect.getY()), srcRaster);
            }
        }
        return raster;
    }

    public synchronized ColorModel getColorModel() {
        if (loadedWindow == null) {
            loadWindow(0, 0, "getColorModel()");
        }
        return loadedWindow.getColorModel();
    }

    public Raster getData() {
        return getData(new Rectangle(0, 0, width, height));
    }
    
    public synchronized Raster getData(Rectangle rect) {

        Point[] windowRange = getWindowRange(rect);

        loadWindow((int)(windowRange[0].getX()), (int)(windowRange[0].getY()), "getData() " + toString(rect));

        if (allowDataSharing && windowRange[0].equals(windowRange[1])) {
            int x = (int)(rect.getX());
            int y = (int)(rect.getY());
            int w = (int)(rect.getWidth());
            int h = (int)(rect.getHeight());
            var ulc = loadedWindowPixelRect.getLocation();
            var windowRect = new Rectangle(x - (int)(ulc.getX()), y - (int)(ulc.getY()), w, h);
            return loadedWindow.getData(windowRect).createTranslatedChild(x, y);
        } else {
            return copyData(loadedWindow.getRaster().createCompatibleWritableRaster(rect), windowRange);
        }
    }

    public int getHeight() {
        return height;
    }

    public int getMinTileX() {
        return 0;
    }

    public int getMinTileY() {
        return 0;
    }

    public int getMinX() {
        return 0;
    }

    public int getMinY() {
        return 0;
    }

    public int getNumXTiles() {
        return numXTiles;
    }

    public int getNumYTiles() {
        return numYTiles;
    }

    public Object getProperty(String name) {
        return properties.containsKey(name) ? properties.get(name) : Image.UndefinedProperty;
    }
    
    public void setProperty(String name, Object value) {
        properties.put(name, value);
    }

    public String[] getPropertyNames() {
        return properties.keySet().toArray(new String[0]);
    }

    public synchronized SampleModel getSampleModel() {
        if (loadedWindow == null) {
            loadWindow(0, 0, "getSampleModel()");
        }

        //unfortunately for certain large images we can't even create a SampleModel instance with the full image size
        //java.lang.IllegalArgumentException: Invalid scanline stride
        //at java.desktop/java.awt.image.ComponentSampleModel.getBufferSize(ComponentSampleModel.java:268)
        var sm = loadedWindow.getSampleModel();
        return ImageInfo.getImageBytes(width, height, sm) < Integer.MAX_VALUE ?
            sm.createCompatibleSampleModel(width, height) : sm;
    }

    public Vector<RenderedImage> getSources()
    {
        return sources;
    }
    
    public synchronized Raster getTile(int tileX, int tileY) {
        Raster tile = jaiCache != null ? jaiCache.getTile(this, tileX, tileY) : null;
        if (tile == null) {
            int x = tileX * tileWidth;
            int y = tileY * tileHeight;
            int r = Math.min(width - 1, x + tileWidth - 1);
            int b = Math.min(height - 1, y + tileHeight - 1);
            int w = r - x + 1;
            int h = b - y + 1;
            tile = getData(new Rectangle(x, y, w, h));
        }
        if (jaiCache != null) {
            jaiCache.add(this, tileX, tileY, tile);
        }
        return tile;
    }
    
    public int getTileGridXOffset() {
        return 0;
    }
    
    public int getTileGridYOffset() {
        return 0;
    }
    
    public int getTileHeight() {
        return tileHeight;
    }
    
    public int getTileWidth() {
        return tileWidth;
    }
    
    public int getWidth() {
        return width;
    }

    public synchronized void close() {
        loadedWindow = null;
        loadedWindowPixelRect = null;
    }

    public void useJAICache(boolean use) {
        jaiCache = use ? JAI.getDefaultInstance().getTileCache() : null;
    }

    public void useJAICache(TileCache cache) {
        jaiCache = cache;
    }

    public void setAllowDataSharing(boolean allow) {
        allowDataSharing = allow;
    }

    public void setLogger(Logger log, String pfx) {
        this.log = log;
        this.pfx = pfx != null ? pfx : "";
    }

    private synchronized void loadWindow(int wx, int wy, String whence) {

        int windowWidth = windowTilesX * tileWidth;
        int windowHeight = windowTilesY * tileHeight;

        int x = Math.min(width - 1, Math.max(0, wx * windowWidth - windowMarginPixels));
        int y = Math.min(height - 1, Math.max(0, wy * windowHeight - windowMarginPixels));

        int r = Math.min(width - 1, (wx + 1) * windowWidth - 1 + windowMarginPixels);
        int w = r - x + 1;

        int b = Math.min(height- 1, (wy + 1) * windowHeight - 1 + windowMarginPixels);
        int h = b - y + 1;

        var rect = new Rectangle(x, y, w, h);

        if (loadedWindow != null && loadedWindowPixelRect.equals(rect)) {
            return;
        }

        try {
            if (log != null) log.debug(pfx + "loading tile window " + wx + ", " + wy +
                                       " (" + x + ", " + y + ")px " + w + "x" + h + "px: " + whence);
            readParam.setSourceRegion(rect);
            loadedWindow = reader.read(imageIndex, readParam);
            loadedWindowPixelRect = rect;
        } catch (IOException ex) {
            throw new RuntimeException("error loading tile window: " + whence, ex);
        }
    }

    private Point[] getWindowRange(Rectangle rect) {

        int windowWidth = windowTilesX * tileWidth;
        int windowHeight = windowTilesY * tileHeight;

        int l = Math.min(width - 1, Math.max(0, (int)(rect.getX())));
        int t = Math.min(height - 1, Math.max(0, (int)(rect.getY())));
        int r = Math.min(width - 1, l + (int)(rect.getWidth()) - 1);
        int b = Math.min(height - 1, t + (int)(rect.getHeight()) - 1);

        var ulc = new Point(l / windowWidth, t / windowHeight); //integer division
        var lrc = new Point(r / windowWidth, b / windowHeight);

        if (windowMarginPixels > 0) {
            int maxWindowX = (numXTiles - 1) / windowTilesX; //integer division
            int maxWindowY = (numYTiles - 1) / windowTilesY;
            if (ulc.getX() < maxWindowX && ulc.getX() < lrc.getX() &&
                l >= (ulc.getX() + 1) * windowWidth - windowMarginPixels) {
                ulc.translate(1, 0);
            }
            if (ulc.getY() < maxWindowY && ulc.getY() < lrc.getY() &&
                t >= (ulc.getY() + 1) * windowHeight - windowMarginPixels) {
                ulc.translate(0, 1);
            }
            if (lrc.getX() > 0 && lrc.getX() > ulc.getX() &&
                r <= lrc.getX() * windowWidth - 1 + windowMarginPixels) {
                lrc.translate(-1, 0);
            }
            if (lrc.getY() > 0 && lrc.getY() > ulc.getY() &&
                b <= lrc.getY() * windowHeight - 1 + windowMarginPixels) {
                lrc.translate(0, -1);
            }
        }

        return new Point[] { ulc, lrc };
    }

    private static String toString(Rectangle rect) {
        return "x=" + (int)(rect.getX()) + ", y=" + (int)(rect.getY()) + ", w=" + (int)(rect.getWidth()) +
            ", h=" + (int)(rect.getHeight());
    }
}
