package com.magnifierglass.filters;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for color filter matrix generators in {@link BaseFilter}.
 * Tests the raw float[] matrices directly to avoid Android framework
 * dependencies (ColorMatrix is not available in local unit tests).
 */
public class ColorFilterTest {

    /** Apply a 4×5 color matrix to an ARGB pixel and return [R,G,B,A]. */
    private static int[] applyMatrix(float[] m, int r, int g, int b, int a) {
        int ro = clamp((int) (m[0]*r + m[1]*g + m[2]*b + m[3]*a + m[4]));
        int go = clamp((int) (m[5]*r + m[6]*g + m[7]*b + m[8]*a + m[9]));
        int bo = clamp((int) (m[10]*r + m[11]*g + m[12]*b + m[13]*a + m[14]));
        int ao = clamp((int) (m[15]*r + m[16]*g + m[17]*b + m[18]*a + m[19]));
        return new int[]{ro, go, bo, ao};
    }

    /** Concatenate two 4×5 color matrices (same as ColorMatrix.postConcat). */
    private static float[] concat(float[] a, float[] b) {
        float[] result = new float[20];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 5; col++) {
                float sum = 0;
                for (int k = 0; k < 4; k++)
                    sum += b[row * 5 + k] * a[k * 5 + col];
                if (col == 4) sum += b[row * 5 + 4];
                result[row * 5 + col] = sum;
            }
        }
        return result;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private final BaseFilter base = new BlackWhiteColorFilter();

    // --- Contrast matrix ---

    @Test
    public void contrastZero_isIdentity() {
        float[] m = base.getContrastMatrix(0f);
        int[] result = applyMatrix(m, 100, 150, 200, 255);
        assertEquals(100, result[0]);
        assertEquals(150, result[1]);
        assertEquals(200, result[2]);
        assertEquals(255, result[3]);
    }

    @Test
    public void contrastNegativeOne_isGrey() {
        float[] m = base.getContrastMatrix(-1f);
        int[] result = applyMatrix(m, 0, 0, 0, 255);
        assertTrue("Should be mid-grey, got " + result[0], Math.abs(result[0] - 128) <= 1);
        int[] result2 = applyMatrix(m, 255, 255, 255, 255);
        assertTrue("Should be mid-grey, got " + result2[0], Math.abs(result2[0] - 128) <= 1);
    }

    @Test
    public void contrastPositive_increasesRange() {
        float[] m = base.getContrastMatrix(1f);
        // Mid-grey should stay mid-grey
        int[] mid = applyMatrix(m, 128, 128, 128, 255);
        assertTrue("Mid-grey should stay near 128", Math.abs(mid[0] - 128) < 5);
        // Dark should get darker
        int[] dark = applyMatrix(m, 50, 50, 50, 255);
        assertTrue("Dark should get darker with contrast", dark[0] < 50);
    }

    // --- Invert matrix ---

    @Test
    public void invert_swapsBlackAndWhite() {
        float[] m = base.getInvertMatrix();
        int[] fromBlack = applyMatrix(m, 0, 0, 0, 255);
        int[] fromWhite = applyMatrix(m, 255, 255, 255, 255);
        assertEquals(255, fromBlack[0]);
        assertEquals(0, fromWhite[0]);
    }

    @Test
    public void invert_roundTrip() {
        float[] inv = base.getInvertMatrix();
        int[] once = applyMatrix(inv, 100, 150, 200, 255);
        int[] twice = applyMatrix(inv, once[0], once[1], once[2], once[3]);
        assertEquals(100, twice[0]);
        assertEquals(150, twice[1]);
        assertEquals(200, twice[2]);
    }

    @Test
    public void invert_preservesAlpha() {
        float[] m = base.getInvertMatrix();
        int[] result = applyMatrix(m, 100, 100, 100, 255);
        assertEquals(255, result[3]);
    }

    // --- Greyscale matrix ---

    @Test
    public void greyscale_equalChannels() {
        float[] m = base.getGreyscaleMatrix();
        int[] result = applyMatrix(m, 100, 150, 200, 255);
        assertEquals("R and G should be equal", result[0], result[1]);
        assertEquals("G and B should be equal", result[1], result[2]);
    }

    @Test
    public void greyscale_preservesNeutral() {
        float[] m = base.getGreyscaleMatrix();
        int[] result = applyMatrix(m, 128, 128, 128, 255);
        // BT.601: 0.299*128 + 0.587*128 + 0.114*128 = 128
        assertEquals(128, result[0]);
    }

    // --- Inverted greyscale matrix ---

    @Test
    public void invertedGreyscale_whiteBecomesBlack() {
        float[] m = base.getInvertedGreyscaledMatrix();
        int[] result = applyMatrix(m, 255, 255, 255, 255);
        // -(0.299+0.587+0.114)*255 + 255 = -255 + 255 = 0
        assertTrue("White should become dark", result[0] < 10);
    }

    @Test
    public void invertedGreyscale_blackBecomesWhite() {
        float[] m = base.getInvertedGreyscaledMatrix();
        int[] result = applyMatrix(m, 0, 0, 0, 255);
        assertEquals(255, result[0]);
    }

    // --- Blue/Yellow matrix ---

    @Test
    public void blueYellow_notGreyscale() {
        BlueYellowColorFilter byf = new BlueYellowColorFilter();
        float[] m = byf.getInvertedBlueYellowMatrix();
        int[] result = applyMatrix(m, 200, 100, 50, 255);
        boolean allSame = (result[0] == result[1]) && (result[1] == result[2]);
        assertFalse("Blue/Yellow should produce non-greyscale output", allSame);
    }

    // --- BlackWhite filter (greyscale + contrast) ---

    @Test
    public void blackWhiteFilter_whiteStaysLight() {
        float[] gs = base.getGreyscaleMatrix();
        float[] ct = base.getContrastMatrix(0.66f);
        float[] combined = concat(gs, ct);
        int[] result = applyMatrix(combined, 255, 255, 255, 255);
        assertTrue("White should remain bright, got " + result[0], result[0] > 200);
    }

    @Test
    public void blackWhiteFilter_blackStaysDark() {
        float[] gs = base.getGreyscaleMatrix();
        float[] ct = base.getContrastMatrix(0.66f);
        float[] combined = concat(gs, ct);
        int[] result = applyMatrix(combined, 0, 0, 0, 255);
        assertTrue("Black should remain dark, got " + result[0], result[0] < 55);
    }

    // --- WhiteBlack filter (greyscale + contrast + invert) ---

    @Test
    public void whiteBlackFilter_invertsBrightness() {
        float[] gs = base.getGreyscaleMatrix();
        float[] ct = base.getContrastMatrix(0.66f);
        float[] inv = base.getInvertMatrix();
        float[] bw = concat(gs, ct);
        float[] wb = concat(bw, inv);
        int[] bwWhite = applyMatrix(bw, 255, 255, 255, 255);
        int[] wbWhite = applyMatrix(wb, 255, 255, 255, 255);
        assertTrue("W/B should invert B/W: white→dark. B/W=" + bwWhite[0] + " W/B=" + wbWhite[0],
                wbWhite[0] < 55 && bwWhite[0] > 200);
    }

    // --- All matrices preserve alpha ---

    @Test
    public void allMatrices_preserveAlpha() {
        float[][] matrices = {
                base.getContrastMatrix(0.5f),
                base.getGreyscaleMatrix(),
                base.getInvertMatrix(),
                base.getInvertedGreyscaledMatrix(),
                new BlueYellowColorFilter().getInvertedBlueYellowMatrix()
        };
        for (float[] m : matrices) {
            int[] result = applyMatrix(m, 128, 128, 128, 255);
            assertEquals("Alpha should be preserved", 255, result[3]);
        }
    }
}
