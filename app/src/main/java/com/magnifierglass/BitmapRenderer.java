package com.magnifierglass;

import android.graphics.Bitmap;

/**
 * Callback interface for receiving rendered bitmaps from background threads.
 */
public interface BitmapRenderer {
    /**
     * Called when a bitmap has been rendered and is ready for display.
     * @param bitmap the rendered bitmap
     */
    void renderBitmap(Bitmap bitmap);
}
