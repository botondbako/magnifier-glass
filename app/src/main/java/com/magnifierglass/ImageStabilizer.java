package com.magnifierglass;

/**
 * Multi-frame image stabilization for NV21 camera preview buffers.
 *
 * <p><b>Algorithm:</b></p>
 * <ol>
 *   <li><b>Reference selection:</b> The frame with the highest edge contrast
 *       (sum of absolute horizontal Y differences) is chosen as reference.</li>
 *   <li><b>Block-matching alignment:</b> Each other frame's translation offset (dx, dy)
 *       is estimated via SAD (Sum of Absolute Differences) on a central luminance patch
 *       within a small search window.</li>
 *   <li><b>Quality gating:</b> Frames whose SAD exceeds a threshold (2× the best match)
 *       are excluded to prevent ghosting from scene changes or large motion.</li>
 *   <li><b>Aligned averaging:</b> Accepted frames' Y channels are shifted and averaged.
 *       Noise reduces by √N while registered edges stay sharp. UV is taken from the
 *       reference (chroma is low-frequency).</li>
 * </ol>
 */
public class ImageStabilizer {

    /** Side length of the central patch used for block matching. */
    private static final int PATCH_SIZE = 64;
    /** Maximum pixel shift to search in each direction. */
    private static final int SEARCH_RANGE = 4;
    /**
     * Maximum acceptable mean absolute difference per pixel in the match patch.
     * Frames above this are too different (motion blur, large shift) and would
     * blur the result if averaged in.
     */
    private static final int MAX_SAD_PER_PIXEL = 14;
    /**
     * Extra weight given to the sharpest (reference) frame so that its detail
     * dominates the average even when other frames have sub-pixel misalignment.
     */
    private static final int REF_WEIGHT = 3;

    /**
     * Stabilize multiple NV21 frames into a single sharp output.
     *
     * @param frames array of NV21 byte buffers (Y plane followed by interleaved VU)
     * @param width  preview width in pixels
     * @param height preview height in pixels
     * @return stabilized NV21 frame, or the sharpest single frame if only one is provided
     */
    public static byte[] stabilize(byte[][] frames, int width, int height) {
        int count = frames.length;
        if (count == 0) throw new IllegalArgumentException("No frames");
        if (count == 1) return frames[0].clone();

        int pixels = width * height;

        // Step 1: Find sharpest frame (highest horizontal edge energy in Y channel)
        int bestIdx = 0;
        long bestScore = -1;
        for (int f = 0; f < count; f++) {
            long score = edgeScore(frames[f], width, height);
            if (score > bestScore) { bestScore = score; bestIdx = f; }
        }
        byte[] ref = frames[bestIdx];

        // Step 2: Block-matching — find (dx, dy) per frame via SAD on central patch
        int ps = Math.min(PATCH_SIZE, Math.min(width, height) / 2);
        int sr = SEARCH_RANGE;
        int cx = width / 2 - ps / 2;
        int cy = height / 2 - ps / 2;
        int[] dx = new int[count], dy = new int[count];
        long[] matchSad = new long[count];

        for (int f = 0; f < count; f++) {
            if (f == bestIdx) continue;
            byte[] frame = frames[f];
            long minSad = Long.MAX_VALUE;
            for (int sy = -sr; sy <= sr; sy++) {
                int frmBaseY = cy + sy;
                if (frmBaseY < 0 || frmBaseY + ps > height) continue;
                for (int sx = -sr; sx <= sr; sx++) {
                    int frmBaseX = cx + sx;
                    if (frmBaseX < 0 || frmBaseX + ps > width) continue;
                    long sad = 0;
                    for (int py = 0; py < ps; py++) {
                        int rowRef = (cy + py) * width + cx;
                        int rowFrm = (frmBaseY + py) * width + frmBaseX;
                        for (int px = 0; px < ps; px++)
                            sad += Math.abs((ref[rowRef + px] & 0xFF) - (frame[rowFrm + px] & 0xFF));
                    }
                    if (sad < minSad) { minSad = sad; dx[f] = sx; dy[f] = sy; }
                }
            }
            matchSad[f] = minSad;
        }

        // Compute rejection threshold: 2× the best (lowest) non-reference SAD,
        // but also enforce an absolute per-pixel limit so that when all frames
        // are poorly aligned the stabilizer falls back to the sharpest frame.
        long sadThreshold = Long.MAX_VALUE;
        for (int f = 0; f < count; f++) {
            if (f != bestIdx && matchSad[f] > 0 && matchSad[f] < sadThreshold)
                sadThreshold = matchSad[f];
        }
        sadThreshold *= 2;
        long absoluteLimit = (long) MAX_SAD_PER_PIXEL * ps * ps;
        if (sadThreshold > absoluteLimit) sadThreshold = absoluteLimit;

        // Step 3: Average aligned Y channels, skipping poorly matched frames.
        // The reference frame gets extra weight (REF_WEIGHT) so its sharpness
        // dominates even when other frames have sub-pixel misalignment.
        int[] sumY = new int[pixels];
        int[] cnt = new int[pixels];
        for (int f = 0; f < count; f++) {
            if (f != bestIdx && matchSad[f] > sadThreshold) continue;
            int w = (f == bestIdx) ? REF_WEIGHT : 1;
            byte[] frame = frames[f];
            int ox = dx[f], oy = dy[f];
            int yStart = Math.max(0, -oy);
            int yEnd = Math.min(height, height - oy);
            int xStart = Math.max(0, -ox);
            int xEnd = Math.min(width, width - ox);
            for (int y = yStart; y < yEnd; y++) {
                int dstRow = y * width;
                int srcRow = (y + oy) * width + ox;
                for (int x = xStart; x < xEnd; x++) {
                    sumY[dstRow + x] += (frames[f][srcRow + x] & 0xFF) * w;
                    cnt[dstRow + x] += w;
                }
            }
        }

        // Step 4: Build result NV21 — averaged Y + reference UV
        byte[] result = new byte[pixels * 3 / 2];
        for (int i = 0; i < pixels; i++)
            result[i] = cnt[i] > 0 ? (byte)(sumY[i] / cnt[i]) : ref[i];
        System.arraycopy(ref, pixels, result, pixels, pixels / 2);

        // Quality gate: if averaging lost significant sharpness (>15%), fall back to reference.
        // A moderate drop is normal from noise reduction; only reject clear blur from misalignment.
        if (edgeScore(result, width, height) < edgeScore(ref, width, height) * 85 / 100) {
            return ref.clone();
        }
        return result;
    }

    /**
     * Compute edge sharpness score on the Y channel.
     * A small border margin (SEARCH_RANGE + 1 pixels) is skipped to avoid
     * out-of-bounds access from the 3×3 kernel. A box blur is applied first
     * to suppress noise — real edges survive the blur while single-pixel
     * noise is attenuated, making this metric robust for reference frame
     * selection in noisy conditions.
     */
    static long edgeScore(byte[] nv21, int width, int height) {
        int margin = Math.max(SEARCH_RANGE + 1, 2);
        long score = 0;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin - 1; x++) {
                // 3×3 box average at (x, y) and (x+1, y)
                int sum0 = 0, sum1 = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int row = (y + dy) * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        sum0 += nv21[row + x + dx] & 0xFF;
                        sum1 += nv21[row + x + 1 + dx] & 0xFF;
                    }
                }
                score += Math.abs(sum0 - sum1);
            }
        }
        return score;
    }
}
