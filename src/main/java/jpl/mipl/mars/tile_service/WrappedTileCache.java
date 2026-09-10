package jpl.mipl.mars.tile_service;

import java.util.Comparator;
import java.awt.Point;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import javax.media.jai.TileCache;

public class WrappedTileCache implements TileCache {
    
    public final TileCache inner;
    
    public WrappedTileCache(TileCache inner) {
        this.inner = inner;
    }
    
    @Override
    public void add(RenderedImage owner, int tileX, int tileY, Raster data) {
        //System.out.println("add tile " + owner + " " + tileX + ", " + tileY +
        //                   " w=" + data.getWidth() + " h=" + data.getHeight() + " " + data);
        //for (var ste : (new Throwable()).getStackTrace()) System.out.println(ste);
        inner.add(owner, tileX, tileY, data);
    }
    
    @Override
    public void add(RenderedImage owner, int tileX, int tileY, Raster data, Object tileCacheMetric) {
        //System.out.println("add tile " + owner + " " + tileX + ", " + tileY +
        //                   " w=" + data.getWidth() + " h=" + data.getHeight() + " metric=" + tileCacheMetric +
        //                   " " + data);
        inner.add(owner, tileX, tileY, data, tileCacheMetric);
    }
    
    @Override
    public void addTiles(RenderedImage owner, Point[] tileIndices, Raster[] tiles, Object tileCacheMetric) {
        //System.out.println("addTiles");
        inner.addTiles(owner, tileIndices, tiles, tileCacheMetric);
    }
    
    @Override
    public void flush() {
        //System.out.println("flush");
        inner.flush();
    }
    
    @Override
    public long getMemoryCapacity() {
        //System.out.println("getMemoryCapacity");
        return inner.getMemoryCapacity();
    }
    
    @Override
    public float getMemoryThreshold() {
        //System.out.println("getMemoryThreshold");
        return inner.getMemoryThreshold();
    }

    //private volatile int lastX = -1;

    @Override
    public Raster getTile(RenderedImage owner, int tileX, int tileY) {
        var ret = inner.getTile(owner, tileX, tileY);
        //if (tileX != lastX) {
        //    System.out.println("getTile " + owner + " " + tileX + ", " + tileY + " " + ret);
        //    lastX = tileX;
        //}
        //for (var ste : (new Throwable()).getStackTrace()) System.out.println(ste);
        return ret;
    }
    
    @Override
    public int getTileCapacity() {
        //System.out.println("getTileCapacity");
        return inner.getTileCapacity();
    }
    
    @Override
    public Comparator getTileComparator() {
        //System.out.println("getTileComparator");
        return inner.getTileComparator();
    }
    
    @Override
    public Raster[] getTiles(RenderedImage owner) {
        //System.out.println("getTiles");
        return inner.getTiles(owner);
    }
    
    @Override
    public Raster[] getTiles(RenderedImage owner, Point[] tileIndices) {
        //System.out.println("getTiles");
        return inner.getTiles(owner, tileIndices);
    }
    
    @Override
    public void memoryControl() {
        //System.out.println("memoryControl");
        inner.memoryControl();
    }
    
    @Override
    public void remove(RenderedImage owner, int tileX, int tileY) {
        //System.out.println("remove");
        inner.remove(owner, tileX, tileY);
    }
    
    @Override
    public void removeTiles(RenderedImage owner) {
        //System.out.println("removeTiles");
        inner.removeTiles(owner);
    }
    
    @Override
    public void setMemoryCapacity(long memoryCapacity) {
        //System.out.println("setMemoryCapacity " + memoryCapacity);
        inner.setMemoryCapacity(memoryCapacity);
    }
    
    @Override
    public void setMemoryThreshold(float memoryThreshold) {
        //System.out.println("setMemoryThreshold " + memoryThreshold);
        inner.setMemoryThreshold(memoryThreshold);
    }
    
    @Override
    public void setTileCapacity(int tileCapacity) {
        //System.out.println("setTileCapacity " + tileCapacity);
        inner.setTileCapacity(tileCapacity);
    }
    
    @Override
    public void setTileComparator(Comparator comparator) {
        //System.out.println("setTileComparator");
        inner.setTileComparator(comparator);
    }
}

