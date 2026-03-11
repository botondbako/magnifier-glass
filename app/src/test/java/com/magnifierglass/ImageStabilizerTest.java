package com.magnifierglass;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link ImageStabilizer} using synthetic NV21 buffers.
 *
 * Strategy: generate a sharp reference pattern, create shifted copies (simulating
 * hand shake), optionally add noise, run the stabilizer, and verify the output
 * is at least as sharp as any single input frame.
 */
public class ImageStabilizerTest {

    private static final int W = 320;
    private static final int H = 240;

    /**
     * Generate a synthetic NV21 frame with a sharp checkerboard pattern in Y.
     * The pattern has strong edges that are easy to measure.
     * UV plane is filled with 128 (neutral gray).
     */
    private static byte[] makeCheckerboard(int w, int h, int blockSize) {
        int pixels = w * h;
        byte[] nv21 = new byte[pixels * 3 / 2];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean white = ((x / blockSize) + (y / blockSize)) % 2 == 0;
                nv21[y * w + x] = (byte) (white ? 220 : 30);
            }
        // UV plane: neutral
        for (int i = pixels; i < nv21.length; i++) nv21[i] = (byte) 128;
        return nv21;
    }

    /**
     * Create a shifted view by generating the checkerboard at an offset.
     * No artificial borders — simulates real camera capturing a slightly different view.
     */
    private static byte[] makeShiftedCheckerboard(int w, int h, int blockSize, int dx, int dy) {
        int pixels = w * h;
        byte[] nv21 = new byte[pixels * 3 / 2];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean white = (((x + dx) / blockSize) + ((y + dy) / blockSize)) % 2 == 0;
                nv21[y * w + x] = (byte) (white ? 220 : 30);
            }
        for (int i = pixels; i < nv21.length; i++) nv21[i] = (byte) 128;
        return nv21;
    }

    /**
     * Create a shifted copy of an NV21 frame (Y channel only, UV copied as-is).
     * Pixels that fall outside the source are filled with 128 (mid-gray).
     */
    private static byte[] shiftFrame(byte[] src, int w, int h, int dx, int dy) {
        int pixels = w * h;
        byte[] dst = new byte[src.length];
        // fill Y with mid-gray
        for (int i = 0; i < pixels; i++) dst[i] = (byte) 128;
        for (int y = 0; y < h; y++) {
            int srcY = y + dy;
            if (srcY < 0 || srcY >= h) continue;
            for (int x = 0; x < w; x++) {
                int srcX = x + dx;
                if (srcX < 0 || srcX >= w) continue;
                dst[y * w + x] = src[srcY * w + srcX];
            }
        }
        // copy UV
        System.arraycopy(src, pixels, dst, pixels, pixels / 2);
        return dst;
    }

    /** Add random noise to Y channel. */
    private static byte[] addNoise(byte[] src, int w, int h, int amplitude, long seed) {
        byte[] dst = src.clone();
        java.util.Random rng = new java.util.Random(seed);
        int pixels = w * h;
        for (int i = 0; i < pixels; i++) {
            int v = (dst[i] & 0xFF) + rng.nextInt(amplitude * 2 + 1) - amplitude;
            dst[i] = (byte) Math.max(0, Math.min(255, v));
        }
        return dst;
    }

    /** Compute PSNR between two Y channels in the central region (avoids border artifacts). */
    private static double psnr(byte[] a, byte[] b, int w, int h) {
        int margin = 10;
        double mse = 0;
        int count = 0;
        for (int y = margin; y < h - margin; y++) {
            for (int x = margin; x < w - margin; x++) {
                int i = y * w + x;
                int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
                mse += diff * diff;
                count++;
            }
        }
        mse /= count;
        if (mse == 0) return Double.POSITIVE_INFINITY;
        return 10 * Math.log10(255.0 * 255.0 / mse);
    }

    @Test
    public void singleFrame_returnedUnchanged() {
        byte[] frame = makeCheckerboard(W, H, 8);
        byte[] result = ImageStabilizer.stabilize(new byte[][]{frame}, W, H);
        assertArrayEquals(frame, result);
    }

    @Test
    public void identicalFrames_noBlur() {
        byte[] frame = makeCheckerboard(W, H, 8);
        byte[][] frames = {frame, frame.clone(), frame.clone()};
        byte[] result = ImageStabilizer.stabilize(frames, W, H);
        long refScore = ImageStabilizer.edgeScore(frame, W, H);
        long resultScore = ImageStabilizer.edgeScore(result, W, H);
        // output should be at least as sharp as input
        assertTrue("Identical frames should not lose sharpness. ref=" + refScore + " result=" + resultScore,
                resultScore >= refScore * 0.99);
    }

    @Test
    public void shiftedFrames_alignedCorrectly() {
        byte[] ref = makeCheckerboard(W, H, 16);
        int[][] shifts = {{0,0}, {2,0}, {-1,1}, {0,-2}, {3,1}};
        byte[][] frames = new byte[shifts.length][];
        for (int i = 0; i < shifts.length; i++)
            frames[i] = makeShiftedCheckerboard(W, H, 16, shifts[i][0], shifts[i][1]);

        byte[] result = ImageStabilizer.stabilize(frames, W, H);

        long refScore = ImageStabilizer.edgeScore(ref, W, H);
        long resultScore = ImageStabilizer.edgeScore(result, W, H);

        // The stabilized output should be at least 90% as sharp as the original
        assertTrue("Shifted frames should align. ref=" + refScore + " result=" + resultScore,
                resultScore >= refScore * 0.90);

        // PSNR vs reference should be high (>30 dB) in the central region
        double db = psnr(ref, result, W, H);
        assertTrue("PSNR vs reference should be >30 dB, got " + db, db > 30);
    }

    @Test
    public void noisyFrames_reducedNoise() {
        byte[] clean = makeCheckerboard(W, H, 16);
        byte[][] frames = new byte[6][];
        for (int i = 0; i < frames.length; i++)
            frames[i] = addNoise(clean, W, H, 20, 1000 + i);

        byte[] result = ImageStabilizer.stabilize(frames, W, H);

        // PSNR of result vs clean should be higher than any single noisy frame
        double bestSinglePsnr = 0;
        for (byte[] f : frames) {
            double p = psnr(clean, f, W, H);
            if (p > bestSinglePsnr) bestSinglePsnr = p;
        }
        double resultPsnr = psnr(clean, result, W, H);
        assertTrue("Averaging should reduce noise. single=" + bestSinglePsnr + " result=" + resultPsnr,
                resultPsnr > bestSinglePsnr);
    }

    @Test
    public void noisyShiftedFrames_sharpAndDenoised() {
        byte[] clean = makeCheckerboard(W, H, 16);
        int[][] shifts = {{0,0}, {1,0}, {-1,1}, {2,-1}, {0,2}};
        byte[][] frames = new byte[shifts.length][];
        for (int i = 0; i < shifts.length; i++)
            frames[i] = addNoise(makeShiftedCheckerboard(W, H, 16, shifts[i][0], shifts[i][1]),
                    W, H, 15, 2000 + i);

        byte[] result = ImageStabilizer.stabilize(frames, W, H);

        long cleanScore = ImageStabilizer.edgeScore(clean, W, H);
        long resultScore = ImageStabilizer.edgeScore(result, W, H);

        // Should recover at least 80% of clean edge sharpness
        assertTrue("Noisy+shifted should recover sharpness. clean=" + cleanScore + " result=" + resultScore,
                resultScore >= cleanScore * 0.80);
    }

    @Test
    public void noisyShiftedFrames_multiFrameBetterThanSingle() {
        int[][] shifts = {{0,0}, {2,0}, {-1,1}, {0,-2}, {3,1}};
        byte[][] cleanShifted = new byte[shifts.length][];
        byte[][] frames = new byte[shifts.length][];
        for (int i = 0; i < shifts.length; i++) {
            cleanShifted[i] = makeShiftedCheckerboard(W, H, 16, shifts[i][0], shifts[i][1]);
            frames[i] = addNoise(cleanShifted[i], W, H, 20, 3000 + i);
        }

        byte[] result = ImageStabilizer.stabilize(frames, W, H);

        double bestSinglePsnr = 0;
        for (int i = 0; i < shifts.length; i++) {
            double p = psnr(cleanShifted[i], frames[i], W, H);
            if (p > bestSinglePsnr) bestSinglePsnr = p;
        }

        double bestResultPsnr = 0;
        for (int i = 0; i < shifts.length; i++) {
            double p = psnr(cleanShifted[i], result, W, H);
            if (p > bestResultPsnr) bestResultPsnr = p;
        }

        assertTrue("Result PSNR (" + String.format("%.1f", bestResultPsnr)
                + ") should exceed single frame (" + String.format("%.1f", bestSinglePsnr) + ")",
                bestResultPsnr > bestSinglePsnr);
    }

    @Test
    public void edgeScore_sharpHigherThanBlurry() {
        byte[] sharp = makeCheckerboard(W, H, 8);
        // "blurry" = uniform gray
        byte[] blurry = new byte[W * H * 3 / 2];
        for (int i = 0; i < W * H; i++) blurry[i] = (byte) 128;

        long sharpScore = ImageStabilizer.edgeScore(sharp, W, H);
        long blurryScore = ImageStabilizer.edgeScore(blurry, W, H);
        assertTrue("Sharp should score higher than blurry", sharpScore > blurryScore);
    }
}
