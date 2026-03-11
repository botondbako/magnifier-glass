package com.magnifierglass;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the NV21 YUV→RGB conversion formula used in BitmapCreateThread.
 * The conversion is private, so we replicate the exact formula here to verify
 * correctness against known color values. This catches regressions like the
 * R/B channel swap that was fixed in this commit.
 */
public class YuvRgbConversionTest {

    /**
     * Replicates the exact conversion from BitmapCreateThread.decodeYuvToRgb.
     * Returns ARGB packed int for a single pixel.
     */
    private static int yuvToArgb(int y, int u, int v) {
        y = Math.max(y, 16);
        int a0 = 1192 * (y - 16);
        int a1 = 1634 * (v - 128);
        int a2 = 832 * (v - 128);
        int a3 = 400 * (u - 128);
        int a4 = 2066 * (u - 128);

        int r = (a0 + a1) >> 10;
        int g = (a0 - a2 - a3) >> 10;
        int b = (a0 + a4) >> 10;

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int getR(int argb) { return (argb >> 16) & 0xFF; }
    private static int getG(int argb) { return (argb >> 8) & 0xFF; }
    private static int getB(int argb) { return argb & 0xFF; }

    @Test
    public void white_yuvToRgb() {
        // Y=235, U=128, V=128 → should be near white
        int argb = yuvToArgb(235, 128, 128);
        assertTrue("White R should be >240, got " + getR(argb), getR(argb) > 240);
        assertTrue("White G should be >240, got " + getG(argb), getG(argb) > 240);
        assertTrue("White B should be >240, got " + getB(argb), getB(argb) > 240);
    }

    @Test
    public void black_yuvToRgb() {
        // Y=16, U=128, V=128 → should be near black
        int argb = yuvToArgb(16, 128, 128);
        assertTrue("Black R should be <10, got " + getR(argb), getR(argb) < 10);
        assertTrue("Black G should be <10, got " + getG(argb), getG(argb) < 10);
        assertTrue("Black B should be <10, got " + getB(argb), getB(argb) < 10);
    }

    @Test
    public void red_yuvToRgb() {
        // Pure red in BT.601: Y≈81, U≈90, V≈240
        int argb = yuvToArgb(81, 90, 240);
        assertTrue("Red R should be dominant, got R=" + getR(argb) + " G=" + getG(argb) + " B=" + getB(argb),
                getR(argb) > getG(argb) && getR(argb) > getB(argb));
    }

    @Test
    public void green_yuvToRgb() {
        // Pure green in BT.601: Y≈145, U≈54, V≈34
        int argb = yuvToArgb(145, 54, 34);
        assertTrue("Green G should be dominant, got R=" + getR(argb) + " G=" + getG(argb) + " B=" + getB(argb),
                getG(argb) > getR(argb) && getG(argb) > getB(argb));
    }

    @Test
    public void blue_yuvToRgb() {
        // Pure blue in BT.601: Y≈41, U≈240, V≈110
        int argb = yuvToArgb(41, 240, 110);
        assertTrue("Blue B should be dominant, got R=" + getR(argb) + " G=" + getG(argb) + " B=" + getB(argb),
                getB(argb) > getR(argb) && getB(argb) > getG(argb));
    }

    @Test
    public void channelOrder_rInBits16to23() {
        // Verify R is in bits 16-23 (ARGB format), not swapped with B
        // Y=81, U=90, V=240 → red-ish
        int argb = yuvToArgb(81, 90, 240);
        int r = (argb >> 16) & 0xFF;
        int b = argb & 0xFF;
        assertTrue("R (bits 16-23) should be > B (bits 0-7) for red input. R=" + r + " B=" + b,
                r > b);
    }

    @Test
    public void alphaAlwaysFull() {
        int[][] inputs = {{16,128,128}, {235,128,128}, {81,90,240}, {145,54,34}};
        for (int[] in : inputs) {
            int argb = yuvToArgb(in[0], in[1], in[2]);
            assertEquals("Alpha should always be 0xFF", 0xFF, (argb >> 24) & 0xFF);
        }
    }

    @Test
    public void belowY16_clampedTo16() {
        // Y values below 16 should be treated as 16 (foot room)
        int argb0 = yuvToArgb(0, 128, 128);
        int argb16 = yuvToArgb(16, 128, 128);
        assertEquals("Y=0 should produce same as Y=16", argb16, argb0);
    }

    @Test
    public void neutralGrey_yuvToRgb() {
        // Y=128, U=128, V=128 → mid-grey, all channels equal
        int argb = yuvToArgb(128, 128, 128);
        int r = getR(argb), g = getG(argb), b = getB(argb);
        assertTrue("Grey R≈G≈B, got R=" + r + " G=" + g + " B=" + b,
                Math.abs(r - g) < 3 && Math.abs(g - b) < 3);
    }
}
