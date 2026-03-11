package com.magnifierglass;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Additional edge-case and robustness tests for {@link ImageStabilizer}.
 */
public class ImageStabilizerEdgeCaseTest {

    private static final int W = 320;
    private static final int H = 240;

    private static byte[] makeFlat(int w, int h, int yValue) {
        byte[] nv21 = new byte[w * h * 3 / 2];
        for (int i = 0; i < w * h; i++) nv21[i] = (byte) yValue;
        for (int i = w * h; i < nv21.length; i++) nv21[i] = (byte) 128;
        return nv21;
    }

    private static byte[] makeCheckerboard(int w, int h, int blockSize) {
        byte[] nv21 = new byte[w * h * 3 / 2];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                nv21[y * w + x] = (byte) (((x / blockSize) + (y / blockSize)) % 2 == 0 ? 220 : 30);
        for (int i = w * h; i < nv21.length; i++) nv21[i] = (byte) 128;
        return nv21;
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyFrameArray_throws() {
        ImageStabilizer.stabilize(new byte[0][], W, H);
    }

    @Test
    public void twoFrames_works() {
        byte[] a = makeCheckerboard(W, H, 16);
        byte[] b = makeCheckerboard(W, H, 16);
        byte[] result = ImageStabilizer.stabilize(new byte[][]{a, b}, W, H);
        assertEquals(W * H * 3 / 2, result.length);
    }

    @Test
    public void uniformFrames_noException() {
        // All-gray frames have zero edge score — stabilizer should not crash
        byte[] gray = makeFlat(W, H, 128);
        byte[][] frames = {gray, gray.clone(), gray.clone()};
        byte[] result = ImageStabilizer.stabilize(frames, W, H);
        assertEquals(W * H * 3 / 2, result.length);
    }

    @Test
    public void allBlack_preservedExactly() {
        byte[] black = makeFlat(W, H, 0);
        byte[][] frames = {black, black.clone()};
        byte[] result = ImageStabilizer.stabilize(frames, W, H);
        // Y channel should be all zeros
        for (int i = 0; i < W * H; i++)
            assertEquals("Y pixel " + i, 0, result[i] & 0xFF);
    }

    @Test
    public void allWhite_preservedExactly() {
        byte[] white = makeFlat(W, H, 255);
        byte[][] frames = {white, white.clone()};
        byte[] result = ImageStabilizer.stabilize(frames, W, H);
        for (int i = 0; i < W * H; i++)
            assertEquals("Y pixel " + i, 255, result[i] & 0xFF);
    }

    @Test
    public void uvPlane_copiedFromReference() {
        // Create two frames with different UV planes
        byte[] a = makeCheckerboard(W, H, 16);
        byte[] b = makeCheckerboard(W, H, 16);
        int uvStart = W * H;
        // Fill UV with distinct patterns
        for (int i = uvStart; i < a.length; i++) {
            a[i] = (byte) 100;
            b[i] = (byte) 200;
        }
        // Both have same Y, so either could be reference — but UV must come from one of them
        byte[] result = ImageStabilizer.stabilize(new byte[][]{a, b}, W, H);
        // UV should be entirely 100 or entirely 200 (from whichever was picked as reference)
        byte uvVal = result[uvStart];
        assertTrue("UV should come from reference frame",
                (uvVal & 0xFF) == 100 || (uvVal & 0xFF) == 200);
        for (int i = uvStart; i < result.length; i++)
            assertEquals("UV plane should be uniform from reference", uvVal, result[i]);
    }

    @Test
    public void sceneChange_rejectedFrame() {
        // One frame is completely different (scene change) — should be rejected
        byte[] checker = makeCheckerboard(W, H, 16);
        byte[] inverted = makeCheckerboard(W, H, 16);
        for (int i = 0; i < W * H; i++)
            inverted[i] = (byte) (255 - (inverted[i] & 0xFF));

        byte[][] frames = {checker, checker.clone(), checker.clone(), inverted};
        byte[] result = ImageStabilizer.stabilize(frames, W, H);

        // Result should be close to the checker (inverted frame rejected)
        long checkerScore = ImageStabilizer.edgeScore(checker, W, H);
        long resultScore = ImageStabilizer.edgeScore(result, W, H);
        assertTrue("Scene change frame should be rejected, sharpness preserved",
                resultScore >= checkerScore * 0.85);
    }

    @Test
    public void smallFrame_doesNotCrash() {
        // Frame smaller than PATCH_SIZE (64) — patch size should adapt
        int w = 32, h = 32;
        byte[] frame = makeCheckerboard(w, h, 4);
        byte[][] frames = {frame, frame.clone()};
        byte[] result = ImageStabilizer.stabilize(frames, w, h);
        assertEquals(w * h * 3 / 2, result.length);
    }

    @Test
    public void outputLength_matchesInput() {
        byte[] frame = makeCheckerboard(W, H, 8);
        byte[][] frames = {frame, frame.clone(), frame.clone(), frame.clone()};
        byte[] result = ImageStabilizer.stabilize(frames, W, H);
        assertEquals("Output NV21 size must match input", frame.length, result.length);
    }

    @Test
    public void edgeScore_zeroForUniform() {
        byte[] flat = makeFlat(W, H, 128);
        assertEquals(0, ImageStabilizer.edgeScore(flat, W, H));
    }

    @Test
    public void edgeScore_symmetricForInvertedPattern() {
        // Inverted checkerboard should have same edge score as original
        byte[] a = makeCheckerboard(W, H, 8);
        byte[] b = new byte[a.length];
        for (int i = 0; i < W * H; i++) b[i] = (byte) (255 - (a[i] & 0xFF));
        for (int i = W * H; i < a.length; i++) b[i] = a[i];
        assertEquals(ImageStabilizer.edgeScore(a, W, H), ImageStabilizer.edgeScore(b, W, H));
    }

    @Test
    public void manyFrames_handledCorrectly() {
        // 16 frames — more than typical FRAME_BUFFER_SIZE
        byte[] base = makeCheckerboard(W, H, 16);
        byte[][] frames = new byte[16][];
        for (int i = 0; i < 16; i++) frames[i] = base.clone();
        byte[] result = ImageStabilizer.stabilize(frames, W, H);
        assertEquals(base.length, result.length);
    }
}
