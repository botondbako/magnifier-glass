package com.magnifierglass.threads;

import android.graphics.Bitmap;

import com.magnifierglass.BitmapRenderer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Tests for BitmapCreateThread CAS concurrency and YUV→Bitmap pipeline.
 * Uses Robolectric for Bitmap support without an emulator.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BitmapCreateThreadInstrumentedTest {

    @Before
    public void resetCounter() {
        BitmapCreateThread.resetInstanceCounter();
    }

    private static byte[] makeNv21(int w, int h, int yVal) {
        byte[] data = new byte[w * h * 3 / 2];
        for (int i = 0; i < w * h; i++) data[i] = (byte) yVal;
        for (int i = w * h; i < data.length; i++) data[i] = (byte) 128;
        return data;
    }

    @Test
    public void getInstance_respectsMaxInstances() {
        int w = 4, h = 4;
        byte[] yuv = makeNv21(w, h, 128);
        AtomicInteger rendered = new AtomicInteger(0);
        BitmapRenderer renderer = bmp -> rendered.incrementAndGet();

        List<BitmapCreateThread> held = new ArrayList<>();
        for (int i = 0; i < BitmapCreateThread.MAX_INSTANCES; i++) {
            BitmapCreateThread t = BitmapCreateThread.getInstance(yuv, renderer, w, h, true, false);
            assertNotNull("Should get instance " + i, t);
            held.add(t);
        }

        assertNull("Should be null when at max",
                BitmapCreateThread.getInstance(yuv, renderer, w, h, true, false));

        held.get(0).run();
        assertNotNull("Should get instance after one completes",
                BitmapCreateThread.getInstance(yuv, renderer, w, h, true, false));

        // Clean up
        for (int i = 1; i < held.size(); i++) held.get(i).run();
    }

    @Test
    public void concurrentGetInstance_neverExceedsMax() throws InterruptedException {
        int w = 4, h = 4;
        byte[] yuv = makeNv21(w, h, 128);
        AtomicInteger maxObserved = new AtomicInteger(0);
        AtomicInteger nullCount = new AtomicInteger(0);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        CountDownLatch holdRenderers = new CountDownLatch(1);

        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        BitmapRenderer renderer = bmp -> {
            int c = concurrentCount.incrementAndGet();
            int prev;
            do { prev = maxObserved.get(); } while (c > prev && !maxObserved.compareAndSet(prev, c));
            try { holdRenderers.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            concurrentCount.decrementAndGet();
        };

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    BitmapCreateThread t = BitmapCreateThread.getInstance(yuv, renderer, w, h, true, false);
                    attempted.countDown();
                    if (t != null) t.run(); else nullCount.incrementAndGet();
                } catch (Exception e) {
                    fail("Exception: " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue("All threads should attempt getInstance", attempted.await(5, TimeUnit.SECONDS));
        holdRenderers.countDown();
        assertTrue("Should complete within 10s", done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue("Some requests should have been blocked, got " + nullCount.get(),
                nullCount.get() >= threads - BitmapCreateThread.MAX_INSTANCES);
        assertTrue("Max concurrent <= MAX_INSTANCES, was " + maxObserved.get(),
                maxObserved.get() <= BitmapCreateThread.MAX_INSTANCES);
    }

    @Test
    public void counterResetsAfterException() {
        int w = 4, h = 4;
        BitmapRenderer renderer = bmp -> { throw new RuntimeException("boom"); };
        byte[] yuv = makeNv21(w, h, 128);

        BitmapCreateThread t = BitmapCreateThread.getInstance(yuv, renderer, w, h, true, false);
        assertNotNull(t);
        t.run(); // exception caught internally, counter decremented

        List<BitmapCreateThread> held = new ArrayList<>();
        BitmapRenderer noop = bmp -> {};
        for (int i = 0; i < BitmapCreateThread.MAX_INSTANCES; i++) {
            BitmapCreateThread t2 = BitmapCreateThread.getInstance(yuv, noop, w, h, true, false);
            assertNotNull("Slot " + i + " should be available after exception", t2);
            held.add(t2);
        }
        for (BitmapCreateThread x : held) x.run();
    }

    @Test
    public void rgbMode_producesCorrectBitmapSize() {
        int w = 8, h = 6;
        byte[] yuv = makeNv21(w, h, 200);
        final Bitmap[] result = {null};

        BitmapCreateThread t = BitmapCreateThread.getInstance(yuv, bmp -> result[0] = bmp, w, h, true, false);
        assertNotNull(t);
        t.run();

        assertNotNull("Bitmap should be rendered", result[0]);
        assertEquals(w, result[0].getWidth());
        assertEquals(h, result[0].getHeight());
    }

    @Test
    public void mirror_flipsBitmap() {
        int w = 8, h = 4;
        byte[] yuv = new byte[w * h * 3 / 2];
        for (int row = 0; row < h; row++)
            for (int col = 0; col < w; col++)
                yuv[row * w + col] = (byte) (col < w / 2 ? 235 : 16);
        for (int i = w * h; i < yuv.length; i++) yuv[i] = (byte) 128;

        final Bitmap[] normal = {null};
        final Bitmap[] mirrored = {null};

        BitmapCreateThread t1 = BitmapCreateThread.getInstance(yuv, bmp -> normal[0] = bmp, w, h, true, false);
        t1.run();
        BitmapCreateThread t2 = BitmapCreateThread.getInstance(yuv, bmp -> mirrored[0] = bmp, w, h, true, true);
        t2.run();

        int normalLeft = (normal[0].getPixel(0, 0) >> 16) & 0xFF;
        int mirroredLeft = (mirrored[0].getPixel(0, 0) >> 16) & 0xFF;
        assertTrue("Normal left should be bright: " + normalLeft, normalLeft > 200);
        assertTrue("Mirrored left should be dark: " + mirroredLeft, mirroredLeft < 30);
    }

    @Test
    public void greyscaleMode_nativeErrorDecrementsCounter() {
        // Greyscale mode uses JNI (NativeYuvDecoder). In Robolectric the native
        // lib isn't loaded, so run() catches the UnsatisfiedLinkError internally.
        // Verify the instance counter is properly decremented after the error.
        int w = 4, h = 4;
        byte[] yuv = makeNv21(w, h, 128);

        BitmapCreateThread t = BitmapCreateThread.getInstance(yuv, bmp -> {}, w, h, false, false);
        assertNotNull(t);
        t.run(); // UnsatisfiedLinkError caught internally by run()

        // Counter should be decremented — verify we can still get MAX_INSTANCES
        List<BitmapCreateThread> held = new ArrayList<>();
        for (int i = 0; i < BitmapCreateThread.MAX_INSTANCES; i++) {
            BitmapCreateThread t2 = BitmapCreateThread.getInstance(yuv, bmp -> {}, w, h, true, false);
            assertNotNull("Slot " + i + " should be available after native error", t2);
            held.add(t2);
        }
        for (BitmapCreateThread x : held) x.run();
    }
}
