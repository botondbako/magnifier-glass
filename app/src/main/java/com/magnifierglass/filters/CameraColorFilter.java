package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/** Color filter applied to the camera preview via {@link android.graphics.ColorMatrix}. */
public interface CameraColorFilter {
    /**
     * Filters the given matrix by using {@link ColorMatrix}.postConcat.
     * The given colorMatrix is handled as a reference so we don't need return value.
     * @param colorMatrix the color matrix you wanna change
     */
    void filter(ColorMatrix colorMatrix);
}
