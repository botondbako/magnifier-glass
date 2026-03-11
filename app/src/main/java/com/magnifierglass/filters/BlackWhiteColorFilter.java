package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/** High-contrast black-on-white color filter (greyscale + contrast boost). */
public class BlackWhiteColorFilter extends BaseFilter {
    /**
     * our default contrast level
     */
    private static final float CONTRAST_LEVEL = 0.66f;

    @Override
    public void filter(ColorMatrix colorMatrix) {
        float[] contrast = getContrastMatrix(CONTRAST_LEVEL);
        float[] greyscale = getGreyscaleMatrix();

        colorMatrix.postConcat(new ColorMatrix(greyscale));
        colorMatrix.postConcat(new ColorMatrix(contrast));
    }
}
