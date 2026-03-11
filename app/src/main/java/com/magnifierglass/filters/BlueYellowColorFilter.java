package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/** Blue-on-yellow high-contrast color filter. */
public class BlueYellowColorFilter extends BaseFilter {

    @Override
    public void filter(ColorMatrix colorMatrix) {
        float[] blueYellowMatrix = getInvertedBlueYellowMatrix();
        colorMatrix.postConcat(new ColorMatrix(blueYellowMatrix));
    }

    /** Returns a blue-yellow color mapping matrix. */
    float[] getInvertedBlueYellowMatrix() {
        return new float[] {
                 3,        3,       1,    0, -512,
                 3,        3,       1,    0, -512,
            -0.75f,     0.0f,    0.7f,    0,  128,
                 0,        0,       0,    1,    0
        };
    }
}