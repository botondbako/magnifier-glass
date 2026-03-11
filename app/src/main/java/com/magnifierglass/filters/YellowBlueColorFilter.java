package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/** Inverted blue-yellow filter: yellow-on-blue. */
public class YellowBlueColorFilter extends BlueYellowColorFilter {
    @Override
    public void filter(ColorMatrix colorMatrix) {
        super.filter(colorMatrix);
        float[] inverted = getInvertMatrix();
        colorMatrix.postConcat(new ColorMatrix(inverted));
    }
}