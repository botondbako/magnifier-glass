package com.magnifierglass.filters;

import android.graphics.ColorMatrix;

/**
 * Base class providing pre-defined color matrices (greyscale, contrast, invert)
 * for use by concrete {@link CameraColorFilter} implementations.
 */
public abstract class BaseFilter implements CameraColorFilter {


    /**
     * Returns a contrast adjustment matrix for use in a {@link ColorMatrix}.
     *
     * @param contrast the contrast value: -1f (grey) to 0f (unchanged) to 1f+ (high contrast)
     */
    float[] getContrastMatrix(float contrast) {
        float scale = contrast + 1.f;
        float translate = (-.5f * scale + .5f) * 255.f;
        return new float[] {
                scale, 0, 0, 0, translate,
                0, scale, 0, 0, translate,
                0, 0, scale, 0, translate,
                0, 0, 0, 1, 0
        };
    }

    /** Returns a color inversion matrix. */
    float[] getInvertMatrix() {
        return new float[] {
                -1,  0,  0,  0, 255,
                0,  -1,  0,  0, 255,
                0,   0, -1,  0, 255,
                0,   0,  0,  1,   0
        };
    }

    /** Returns an inverted greyscale matrix using BT.601 perceptual luminance weights. */
    float[] getInvertedGreyscaledMatrix() {
        return new float[] {
                -0.299f, -0.587f, -0.114f,  0, 255,
                -0.299f, -0.587f, -0.114f,  0, 255,
                -0.299f, -0.587f, -0.114f,  0, 255,
                      0,       0,       0,  1,   0
        };
    }

    /** Returns a greyscale conversion matrix using BT.601 perceptual luminance weights. */
    float[] getGreyscaleMatrix() {
        return new float[] {
                0.299f, 0.587f, 0.114f,  0, 0,
                0.299f, 0.587f, 0.114f,  0, 0,
                0.299f, 0.587f, 0.114f,  0, 0,
                     0,      0,      0,  1, 0
        };
    }
}
