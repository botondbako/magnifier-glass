package com.magnifierglass.threads;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import com.magnifierglass.BitmapRenderer;
import com.magnifierglass.NativeYuvDecoder;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders a bitmap from raw NV21 YUV data on a background thread.
 * Instance count is limited via CAS to prevent memory exhaustion.
 */
public class BitmapCreateThread implements Runnable {

    /** Maximum concurrent bitmap creation threads. */
    public static final int MAX_INSTANCES = 3;

    /** Active instance count, managed via CAS. */
    private static final AtomicInteger instanceCounter = new AtomicInteger(0);

    /** Resets the instance counter. Visible for testing only. */
    static void resetInstanceCounter() {
        instanceCounter.set(0);
    }

    private final int previewWidth;
    private final int previewHeight;
    private final BitmapRenderer renderer;
    private final byte[] yuvDataArray;
    private final boolean useRgb;
    private final boolean mirror;

    private BitmapCreateThread(byte[] yuvDataArray, BitmapRenderer renderer,
                               int previewWidth, int previewHeight, boolean useRgb, boolean mirror) {
        this.yuvDataArray = yuvDataArray;
        this.renderer = renderer;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        this.useRgb = useRgb;
        this.mirror = mirror;
    }

    /**
     * Returns a new instance, or null if MAX_INSTANCES are already running.
     * Uses compare-and-set to avoid TOCTOU race on the instance counter.
     */
    public static BitmapCreateThread getInstance(byte[] yuvDataArray, BitmapRenderer renderer, int previewWidth, int previewHeight, boolean useRgb, boolean mirror) {
        int current;
        do {
            current = instanceCounter.get();
            if (current >= MAX_INSTANCES) {
                Log.d("BitmapCreateThread", "Thread Creation blocked, because we reached our MAX_INSTANCES.");
                return null;
            }
        } while (!instanceCounter.compareAndSet(current, current + 1));

        return new BitmapCreateThread(yuvDataArray, renderer, previewWidth, previewHeight, useRgb, mirror);
    }

    private Bitmap createBitmap(byte[] yuvData) {
        int[] rgb = new int[previewWidth * previewHeight];
        if(!useRgb) this.decodeYuvWithNativeYuvToGreyScale(rgb, yuvData, previewWidth, previewHeight);
        else this.decodeYuvToRgb(rgb, yuvData, previewWidth, previewHeight);

        Bitmap bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(rgb, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        if (mirror) {
            bitmap = flipBitmapHorizontally(bitmap);
        }
        return bitmap;
    }

    /**
     * Mirrors a bitmap horizontally (left-right flip) for front-facing camera.
     *
     * @param bitmap the source bitmap
     * @return a new horizontally flipped bitmap
     */
    private static Bitmap flipBitmapHorizontally(Bitmap bitmap) {
        Matrix m = new Matrix();
        m.preScale(-1, 1);
        Bitmap flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, false);
        bitmap.recycle();
        return flipped;
    }

    private void decodeYuvWithNativeYuvToGreyScale(int[] rgb, byte[] yuvData, int width, int height) {
        NativeYuvDecoder.YUVtoRGBGreyscale(yuvData, width, height, rgb);
    }

    private void decodeYuvToRgb(int[] rgb, byte[] nv21, int width, int height) {
        int frameSize = width * height;

        // Convert YUV to RGB
        for (int i = 0; i < height; i++)
            for (int j = 0; j < width; j++) {
                int y = (0xff & ((int) nv21[i * width + j]));
                int u = (0xff & ((int) nv21[frameSize + (i >> 1) * width + (j & ~1)]));
                int v = (0xff & ((int) nv21[frameSize + (i >> 1) * width + (j & ~1) + 1]));
                y = y < 16 ? 16 : y;

                // @source http://www.wordsaretoys.com/2013/10/18/making-yuv-conversion-a-little-faster/
                // @thanks John Jared (https://codetracer.co/profile/109)
                int a0 = 1192 * (y - 16);
                int a1 = 1634 * (v - 128);
                int a2 = 832 * (v - 128);
                int a3 = 400 * (u - 128);
                int a4 = 2066 * (u - 128);

                int r = (a0 + a1) >> 10;
                int g = (a0 - a2 - a3) >> 10;
                int b = (a0 + a4) >> 10;

                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);

                rgb[i * width + j] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
    }

    @Override
    public void run() {
        try {
            Bitmap bitmap = createBitmap(yuvDataArray);
            renderer.renderBitmap(bitmap);
        } catch (Throwable t) {
            Log.e("BitmapCreateThread", "Bitmap creation failed", t);
        } finally {
            instanceCounter.decrementAndGet();
        }
    }
}
