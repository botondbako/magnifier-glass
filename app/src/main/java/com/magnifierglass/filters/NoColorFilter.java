package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/** No-op filter that leaves colors unchanged. */
public class NoColorFilter implements CameraColorFilter {
    @Override
    public void filter(ColorMatrix colorMatrix) {

    }
}
