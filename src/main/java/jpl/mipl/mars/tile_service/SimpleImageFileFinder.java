package jpl.mipl.mars.tile_service;

import java.beans.PropertyChangeEvent;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

import jpl.mipl.mars.viewer.api.Constants;
import jpl.mipl.mars.viewer.api.EdrQuery;
import jpl.mipl.mars.viewer.api.EdrResult;
import jpl.mipl.mars.viewer.api.RdrQuery;
import jpl.mipl.mars.viewer.api.RdrResult;
import jpl.mipl.mars.viewer.api.RdrResultBase;
import jpl.mipl.mars.viewer.api.SolRange;
import jpl.mipl.mars.viewer.finder.FileFinderException;
import jpl.mipl.mars.viewer.finder.MarsImageFileFinder;
import jpl.mipl.mars.viewer.finder.MissionResultWrapper;
import jpl.mipl.mars.viewer.finder.MissionSpecInstrumentInfo;
import jpl.mipl.mars.viewer.finder.MissionSpecUiContext;
import jpl.mipl.mars.viewer.finder.UnderlySelector;
import jpl.mipl.mars.viewer.finder.util.DefaultImageComparator;
import jpl.mipl.mars.viewer.finder.util.DefaultUnderlySelector;
import jpl.mipl.mars.viewer.mesh.api.MeshFramework;
import jpl.mipl.mars.viewer.security.SessionCredential;
import jpl.mipl.mars.viewer.util.FileResourceUtils;

/**
 * <b>Purpose:</b>
 * An extremely simplified, if not moronic, implementation of 
 * the MarsImageFileFinder used for Jade image viewing program.
 *
 *   <PRE>
 *   Copyright 2003, California Institute of Technology.
 *   ALL RIGHTS RESERVED.
 *   U.S. Government Sponsorship acknowledge. 2003.
 *   </PRE>
 *
 * <PRE>
 * ============================================================================
 * <B>Modification History :</B>
 * ----------------------
 *
 * <B>Date              Who              What</B>
 * ----------------------------------------------------------------------------
 * 01/15/2004        Nick          Initial Release
 * 02/10/2004        Nick          Added getEdrThumbnail() method, part of API
 * ============================================================================
 * </PRE> 
 *
 * @class SimpleImageFileFinder
 * @author Nicholas Toole	(Nicholas.T.Toole@jpl.nasa.gov)
 * @version $Id: SimpleImageFileFinder.java,v 1.32 2015/06/18 21:34:24 ntt Exp $
 *
 */
 
public class SimpleImageFileFinder implements MarsImageFileFinder
{    
    //---------------------------------------------------------------------
    
    public final String className_ = "SimpleImageFileFinder";

    /** Name given to images whose type is unrecognized by config settings */
    public static final String DEFAULT_SOURCE_TYPE = "Background";

    protected String _rootPath = File.listRoots()[0].getAbsolutePath();
    protected File   _rootFile;
    protected String _imagePath;
    protected String _imageType;
    protected String _sourcePath;
    protected String _sourceType;
    //protected boolean _isAllThumbnails = false;
    protected boolean _isAllNominal = true;
    
    //protected int _defaultSizeType = Constants.SIZE_TYPE_FULL;
    

    protected final String _defaultSourceType = "FFL";
    
    protected Comparator _comparator;

    protected MissionSpecUiContext      _uiContext;
    protected MissionResultWrapper      _resultWrapper; 
    protected UnderlySelector           _underlySelector;
    protected MissionSpecInstrumentInfo _instrumentInfo;
    //---------------------------------------------------------------------
    /** 
     *  Constructor.
     */
    
    public SimpleImageFileFinder()
    {
        this._comparator = new DefaultImageComparator();
        
        this._uiContext  = null; //new DefaultUiContext(this);
        
        this._resultWrapper = null;
        
        this._instrumentInfo = null;
        
        this._underlySelector = new DefaultUnderlySelector(this);
    }

    //---------------------------------------------------------------------

    /**
     *  Empty implementation.
     *  @param newRoot Path of the root.
     *  @throws FileFinderException If directory specified by newRoot 
     *          parameter does not exist or cannot be read.
     */
    public void setRoot(String newRoot) throws FileFinderException
    {              
        _rootPath = newRoot;        
        if (FileResourceUtils.isLocal(newRoot))
        {
            _rootFile = (new File(_rootPath)).getAbsoluteFile();
        }
        else
        {
            _rootFile = null;
        }
    }
    
    
    //---------------------------------------------------------------------
    
    /**
     *  Empty implementation, returns null.
     *  @return null
     */
    public String getRoot()
    {
        return  _rootPath;
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Empty implementation, returns null.
     *  @return null
     */
    public File getRootFile()
    {
        return _rootFile;
    }

    //---------------------------------------------------------------------

    /**
     *  Returns string identifier of the image file finder type.
     *  @return Root of file organinzation.
     */
    public String getType()
    {
        return "GENERIC_SIMPLE";
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Empty implementation, returns null.
     *  @return null
     *  @throws FileFinderException if file finder error occurs
     */
    
    public EdrResult queryEdrs(EdrQuery request) throws FileFinderException
    {
        return (EdrResult) null;
    }
    
    //---------------------------------------------------------------------
    
    public String getRegex(String filename, boolean matchEye)
    {
        return "\\w+";
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns List containing (1) nothing, if no image path (2)
     *  image path, if it exists and source doesn't exist,
     *  (3) image path followed by source path, if source exists.
     *  @param request Unused, can be null.
     *  @return RdrResult containing image paths contained with this finder.
     *  @throws FileFinderException if file finder error occurs
     */
    public RdrResult queryRdrs(RdrQuery request)  throws FileFinderException
    {
        RdrResultBase result = new RdrResultBase(this);
        
        if (_imagePath != null)
            result.addRdr("files", _imagePath, true);
        if (_sourcePath != null)    
            result.addRdr("files", _sourcePath, true);
        
        return result;
    }
    
    //---------------------------------------------------------------------
       
    /**
     *  Returns the basename of a filepath, ie returns filename from filepath
     *  @param path Filepath of the file whose name is to be extracted.
     *  @return Base name of the file represented by path
     */
    public String extractFilename(String path)
    {
        String filename = path;
        int index = path.lastIndexOf(File.separator);
        if (index != -1)
        {
            filename = path.substring(index+1);
            if (filename == null)
                filename = path;
        }
        
        return filename;
    }

    //---------------------------------------------------------------------
    
    /**
     *  Empty implementation, returns null.
     *  @param imgPath Complete path of the edr file.
     *  @param type Type of file (choose from MarsImageFileFinder.EDR_TYPE, 
     *				         MarsImageFileFinder.RDR_TYPE)
     *  @return Null string.
     */
    public String extractInstrument(String imgPath, short type)
    {
        return (String) null;
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns the image type as denoted by the file organization and the
     *  imgPath parameter.
     *  @param imgPath Complete path of the edr file.
     *  @param type Type of file (choose from MarsImageFileFinder.EDR_TYPE, 
     *					 MarsImageFileFinder.RDR_TYPE)
     *  @return Image type as determined by filename and org.  Null if cannot be
     *          determined.
     */
    public String extractImageType(String imgPath, short type)
    {
//        File imgParam = (new File(imgPath)).getAbsoluteFile();
//        String pathParam = imgParam.getAbsolutePath();
        String pathParam = FileResourceUtils.getFullpath(imgPath);

        if (_imagePath != null && _imagePath.equals(pathParam))
        {
            return _imageType;
        }
        else if (_sourcePath != null && 
                 _sourcePath.equals(pathParam))
        {
            return _sourceType;
        }
        else
        {
            return _defaultSourceType;
        }

    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Empty implementation, returns null.
     *  @param imgPaths List of paths of the edr file.
     *  @param type Type of file (choose from MarsImageFileFinder.EDR_TYPE, 
     *					 MarsImageFileFinder.RDR_TYPE)
     *  @return List of image types as determined by filename and org.  
     */
    public List extractImageTypes(List imgPaths, short type)
    {
        return (List) null;
    }
    
    //---------------------------------------------------------------------        
    //---------------------------------------------------------------------
	
    /**
     *  Empty implementation, returns null.
     *  @param imgPath Complete path of the edr file.
     *  @param type Type of file (choose from MarsImageFileFinder.EDR_TYPE, 
     *					 MarsImageFileFinder.RDR_TYPE)
     *  @return Camera eye type as determined by organization. 
     */
    public int extractEyeType(String imgPath, short type)
    {
        return Constants.EYE_TYPE_NONE;
    }
    public int translateEyeStringToType(String eyeStr)
    {
        return Constants.EYE_TYPE_NONE;
    }
    //---------------------------------------------------------------------
    
    /**
     *  Determines if image represented by imgFile is a thumbnail.
     *  @param imgFile Filename of the image.
     *  @return True if file is a thumbnail, false otherwise.
     */
    public boolean isThumbnail(String imgFile)
    {
        return false;
        //return _defaultSizeType == Constants.SIZE_TYPE_THUMBNAIL;        
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Determines if image represented by imgFile is a thumbnail.
     *  @param imgFile Filename of the image.
     *  @return True if file is a thumbnail, false otherwise.
     */
    public boolean isNominal(String imgFile)
    {
        return _isAllNominal;
    }
    
    //---------------------------------------------------------------------
    
//    /**
//     *  Determines if product type represented by type is a thumbnail.
//     *  @param type Product type.
//     *  @return True if file is a thumbnail, false otherwise.
//     */
    public boolean isThumbnailType(String type)
    {
        return false;
//        return _defaultSizeType == Constants.SIZE_TYPE_THUMBNAIL; 
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns a String array of instrument names. 
     *  @return String array of instrument types, first element an empty string.
     */
    public String[] getInstrumentTypes()
    {
        return new String[0];
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns a String array of RDR types. 
     *  @return String array of RDR types.
     */
    public String[] getRdrTypes()
    {
        return new String[0];
    }

    
    //---------------------------------------------------------------------
    
    /**
     *  Returns a String array of camera eye types. 
     *  @return String array of camera eye types.
     */
    public String[] getCameraEyeTypes()
    {
        return new String[0];
    }

    
    //---------------------------------------------------------------------
    
    /**
     *  Given a List of image file names, uses isThumbnail method to determine
     *  if file should be included in the returned Vector.  This method is
     *  non-destructive to the input List.
     *  @param inImages List of image pathnames 
     */
    public List removeThumbnails(List inImages)
    {
        return new Vector(inImages);
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns true if this object and other is of same class type
     *  and have the same root.
     *  @param other MartImageFileFinder object to be compared to
     *  @return True if class and state are same, false otherwise
     */
    public boolean equals(MarsImageFileFinder other)
    {
        if (this == other)
            return true;
        if (this.getClass().getName().equals(other.getClass().getName()))
            return true;
        return false;
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Formats the SOL as a string based on the directory structure
     *  @param Sol day as integer
     *  @return string representation of the sol parameter
     */
    public String formatSol(int sol)
    {
        return sol+"";
    }

    
    //---------------------------------------------------------------------
    
    /**
     *  Returns the path of the source EDR if rdrPath parameter is
     *  either the image or its source, else the same rdrPath is
     *  returned.
     *
     *  @param rdrPath Path of the RDR product.
     *  @return Path of the corresponding source, or same as parameter.
     */
    public String getSourceProductPath(String rdrPath) throws FileFinderException
    {
//        File imgParam = (new File(rdrPath)).getAbsoluteFile();
//        String pathParam = imgParam.getAbsolutePath();
        String pathParam = FileResourceUtils.getFullpath(rdrPath);

        if (_sourcePath != null)
        {
            if (_imagePath.equals(pathParam) ||
                    (_sourcePath.equals(pathParam)))
            {
                return _sourcePath;
            }  
        }
        return rdrPath;      
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Empty implementation, returns true.
     *  @param Product type of the image
     *  @return True
     */
    public boolean isTypeNominal(String type)
    {
        return _isAllNominal;
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns true if the type parameter is a source, false otherwise.
     *  @param Product type of the image
     *  @return True is type is source, false otherwise.
     */
    public boolean isTypeSource(String type)
    {
        if (_imageType == null) return true;

        if (_imageType.equalsIgnoreCase(type))
        {
            if (_sourcePath == null)
                return true;
            else
                return false;
        }

        return true;
    }
    
    //---------------------------------------------------------------------

    /**
     *  Returns a list of the SOL range of a given file finder.
     *  @return Solar day range
     */
    //public java.util.List getSolRange()
    public SolRange getSolRange()
    {
        return new SolRange();
//        java.util.List list = new ArrayList();
//        list.add("N/A");
//        return list;
    }

//    //---------------------------------------------------------------------
//
//    /**
//     *  Returns a list of the SOL range of a given file finder.
//     *  @return Solar day range
//     */
//    public String getInitialSol()
//    {
//        return "N/A";
//    }

    //---------------------------------------------------------------------

    public void setImage(String filename, String filetype) throws 
                                                        FileFinderException
    {
        if (filename == null || filename.equals(""))
        {
            throw new FileFinderException("Filename parameter cannot "+
                                          "be null or empty string.", 
                                          Constants.MISSING_PARAMETER);
        }

        if (filetype == null || filetype.equals(""))
        {
            throw new FileFinderException("Filetype parameter cannot "+
                                          "be null or empty string.", 
                                          Constants.MISSING_PARAMETER);            
        }
        
        _imagePath = filename;
        _imageType = filetype;
        
        resetSource();
    }

    //---------------------------------------------------------------------

    public String getImage()
    {
        return _imagePath;        
    }

    //---------------------------------------------------------------------

    public void setSource(String filename, String filetype) throws 
                                                        FileFinderException
    {
        if (filename == null)
        {
            resetSource();
            return;
        }
        
        /*
        if (filename == null || filename.equals(""))
        {
            throw new IllegalArgumentException(className_+
                      "::setSource(): filename parameter cannot "+
                      "be null or empty string.");
        }
        */

        if (filetype == null || filetype.equals(""))
        {
            throw new FileFinderException("Filetype parameter cannot "+
                                          "be null or empty string.", 
                                          Constants.MISSING_PARAMETER);            
        }
        
//        File imageFile = (new File(filename)).getAbsoluteFile();
//        if (!imageFile.canRead())
//        {
//            throw new FileFinderException("Filename '"+filename+"' " +
//                         "cannot be read.", Constants.FILE_NOT_FOUND);                    
//        }
//        _sourcePath = imageFile.getAbsolutePath();
        
        if (!FileResourceUtils.resourceExists(filename))
        {
            throw new FileFinderException("Filepath '"+filename+"' " +
                    "cannot be read.", Constants.FILE_NOT_FOUND);    
        }
        _sourcePath = FileResourceUtils.getFullpath(filename);
        _sourceType = filetype;
    }

    //---------------------------------------------------------------------

    protected void resetSource()
    {
        _sourcePath = null;
        _sourceType = null;
    }
    //---------------------------------------------------------------------

    public void setSource(String filename) throws FileFinderException
    {
        setSource(filename, DEFAULT_SOURCE_TYPE) ;//_defaultSourceType);
    }    

    //---------------------------------------------------------------------

    public String getSource()
    {
        return _sourcePath;
    }

    //---------------------------------------------------------------------

//    public void setAllThumbnails(boolean allThumbs)
//    {
//        
//        _isAllThumbnails = allThumbs;
//    }
//
//    //---------------------------------------------------------------------
//
//    public boolean isAllThumbnails()
//    {
//        return _isAllThumbnails;
//    }

    //---------------------------------------------------------------------
    
    //---------------------------------------------------------------------
    
    /**
     *  Returns the visualization type as denoted by the file organization 
     *  and the imgPath parameter.  By default, image type is returned.
     *  @param imgPath Complete path of the file.
     *  @return Projection type as determined by organization. 
     */
    public int extractVisualizationType(String imagePath)
    {
        return Constants.VISUALIZATION_TYPE_IMAGE;
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Implementation of the PropertyChangeListener interface.  For
     *  interaction with the MarsImageViewModel.  Method is called whenever
     *  a change is made to a model property.
     *  @param evt A PropertyChangeEvent object describing the event 
     *               source and the property that has changed.
     */
    
    public void propertyChange(PropertyChangeEvent pce)
    {
        String propName = pce.getPropertyName();
        
        //--------------------------------                          
        //--------------------------------
    }        

    //---------------------------------------------------------------------
   
    /** 
     *  Dummy implementation.  Returns null.
     *  @param edrPath Absolute path of image file
     *  @return Null.
     */
    
    public String getEdrThumbnail(String edrPath, List searchList)
    {
        return null;
    }

    //---------------------------------------------------------------------
    
    /** 
     * Dummy implementation.  Returns false.
     * @param left Left image path
     * @param right Right image path
     * @return false
     */
    
    public boolean isStereoPair(String left, String right)
    {
        return false;
    }

    //---------------------------------------------------------------------
    
    /**
     * No groups are defined for this file finder.  Returns null.
     * @param Path to an image file
     * @return null
     */
    
    public String getGroupId(String imagePath)
    {
        return null;
    }

    //---------------------------------------------------------------------
    
    /**
     * No overlay groups are defined for this file finder.  Returns null.
     * @param Path to an image file
     * @return null
     */
    
    public String getOverlayId(String imagePath)
    {
        return "simple_filefinder_common_overlayid";
    }
    
    //---------------------------------------------------------------------
    
    /**
     *  Dummy implementation.  Returns null.
     *  @param imgPath Complete path of the product file.
     *  @param type Type of file (choose from MarsImageFileFinder.EDR_TYPE, 
     *                   MarsImageFileFinder.RDR_TYPE)
     *  @return Null.
     */
    
    public String extractVersion(String imgPath, short type)
    {
        return null;
    }
    
    //---------------------------------------------------------------------
    
    /**
     * Returns file finders instance of a comparator for sorting
     * files according to some set of rules.
     * @return File finder's image comparator.
     */
    
    public Comparator getImageComparator()
    {
        return this._comparator;
    }
    
    //---------------------------------------------------------------------

    /**
     * Returns true if the filename is not null.
     * @param imagePath Path to image
     * @return True if image path is not null.
     */
    
    public boolean isProductNameFormatted(String imagePath)
    {
        return (imagePath != null);
    }
    
    //---------------------------------------------------------------------
    
    /**
     * Returns SCLK extract from filename.
     * @param imagePath Product path
     * @return SCLK of image, or null if not extractable
     */
    
    public String extractSclk(String imagePath)
    {
        return null;
        
    }
    
    //---------------------------------------------------------------------

    public String extractInstrumentCategory(String imagePath)
    {
        return null;
    }

    //---------------------------------------------------------------------
    
    public String extractInstrumentId(String imgPath)
    {
        return null;
    }

    //---------------------------------------------------------------------
    
    public String getProductNamespace()
    {
        return null;
    }
    
    //---------------------------------------------------------------------
    
    /**
     * @param imagePath Path to image
     * @return Numeric id of geometry type
     */
    
    public int extractGeometryType(String imagePath)
    {
        return Constants.GEOMETRY_TYPE_NONE;
    }
    
    //---------------------------------------------------------------------
    
    /**
     * @param imagePath Path to image
     * @return Numeric id of size type
     */
    
    public int extractSizeType(String imagePath)
    {
        return Constants.SIZE_TYPE_NONE;
    }
    
    //---------------------------------------------------------------------
    
    /**
     * @param imagePath Path to image
     * @return Numeric id of projection type
     */
    
    public int extractProjectionType(String imagePath)
    {
        return Constants.PROJECTION_TYPE_NONE;
    }
    
    //---------------------------------------------------------------------
    
    public MissionSpecUiContext getUIContext()
    {
        return _uiContext;
    }
    
    //---------------------------------------------------------------------
    
    public MissionResultWrapper getResultWrapper()
    {
        return _resultWrapper;
    }

    //---------------------------------------------------------------------
    
    public MissionSpecInstrumentInfo getInstrumentInfo()
    {
        return this._instrumentInfo;
    }
    
    //---------------------------------------------------------------------
    
    public UnderlySelector getUnderlySelector()
    {
        return _underlySelector;
    }
    
    //---------------------------------------------------------------------
    
    public boolean isLocal()
    {
        return true;
    }
    
    //---------------------------------------------------------------------
    
    public void setCredential(SessionCredential creds) 
    {        
    }
    
    //---------------------------------------------------------------------
    
    public SessionCredential getCredential()
    {
        return null;
    }
    
    //---------------------------------------------------------------------
    
    public int[] getProjectionTypes() { return new int[0]; }
    
    public int[] getSizeTypes() { return new int[0]; }
    
    public int[] getGeometryTypes() { return new int[0]; }
    
    public int[] getEyeTypes() { return new int[0]; }
    
    //---------------------------------------------------------------------
    
    public MeshFramework getMeshFramework()
    {
        return null;
    }
    
    //---------------------------------------------------------------------
}
