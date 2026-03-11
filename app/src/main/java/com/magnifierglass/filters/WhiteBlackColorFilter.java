package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/** Inverted high-contrast filter: white-on-black. */
public class WhiteBlackColorFilter extends BlackWhiteColorFilter {
    @Override
    public void filter(ColorMatrix colorMatrix) {
        super.filter(colorMatrix);

        float[] inverted = getInvertMatrix();
        colorMatrix.postConcat(new ColorMatrix(inverted));
    }
}