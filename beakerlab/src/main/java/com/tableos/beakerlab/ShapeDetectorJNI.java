package com.tableos.beakerlab;

import android.graphics.Bitmap;

public class ShapeDetectorJNI {
    
    static {
        System.loadLibrary("beakerlab_jni");
    }
    
    /**
     * Initialize the shape detector
     * @return true if initialization successful, false otherwise
     */
    public static native boolean init();
    
    /**
     * Cleanup the shape detector
     */
    public static native void cleanup();
    
    /**
     * Detect shapes in a bitmap and return JSON result
     * @param bitmap Input bitmap
     * @return JSON string containing detection results
     */
    public static native String detectShapesFromBitmap(Bitmap bitmap);
    
    /**
     * Annotate image with detection results
     * @param bitmap Input bitmap
     * @return Annotated bitmap with detection results drawn
     */
    public static native Bitmap annotateImage(Bitmap bitmap);
    
    /**
     * Get version information
     * @return Version string
     */
    public static native String getVersion();
}