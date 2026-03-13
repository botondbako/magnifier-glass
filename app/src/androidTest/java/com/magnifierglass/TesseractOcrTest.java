package com.magnifierglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * Instrumented tests for Tesseract OCR running on the device.
 * Renders text onto bitmaps and verifies recognition.
 */
@RunWith(AndroidJUnit4.class)
public class TesseractOcrTest {

    private TessBaseAPI tess;
    private String tessDataPath;

    @Before
    public void setUp() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tessDataPath = ctx.getFilesDir() + "/tesseract_test/";
        File tessDir = new File(tessDataPath + "tessdata");
        File engFile = new File(tessDir, "eng.traineddata");
        if (!engFile.exists()) {
            tessDir.mkdirs();
            // Atomic write: copy to temp file, then rename
            File tmp = new File(tessDir, "eng.traineddata.tmp");
            try (InputStream is = ctx.getAssets().open("tessdata/eng.traineddata");
                 FileOutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }
            if (!tmp.renameTo(engFile)) {
                tmp.delete();
                fail("Failed to rename tessdata temp file");
            }
        }
        tess = new TessBaseAPI();
        assertTrue("Tesseract init failed", tess.init(tessDataPath, "eng"));
    }

    @After
    public void tearDown() {
        if (tess != null) tess.recycle();
    }

    private Bitmap renderText(String text, int width, int height, float textSize) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.MONOSPACE);
        canvas.drawText(text, 20, textSize + 10, paint);
        return bmp;
    }

    private Bitmap renderMultiline(String[] lines, int width, float textSize) {
        int height = (int) ((textSize + 12) * lines.length + 20);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.MONOSPACE);
        float y = textSize + 10;
        for (String line : lines) {
            canvas.drawText(line, 20, y, paint);
            y += textSize + 12;
        }
        return bmp;
    }

    @Test
    public void recognizeSingleWord() {
        Bitmap bmp = renderText("Hello", 400, 100, 48);
        tess.setImage(bmp);
        String result = tess.getUTF8Text().trim();
        bmp.recycle();
        assertEquals("Hello", result);
    }

    @Test
    public void recognizeSentence() {
        Bitmap bmp = renderText("The quick brown fox", 800, 100, 48);
        tess.setImage(bmp);
        String result = tess.getUTF8Text().trim();
        bmp.recycle();
        assertTrue("Expected 'The quick brown fox' but got: " + result,
                result.contains("quick") && result.contains("fox"));
    }

    @Test
    public void recognizeMultipleLines() {
        Bitmap bmp = renderMultiline(new String[]{
                "First line of text",
                "Second line here"
        }, 800, 48);
        tess.setImage(bmp);
        String result = tess.getUTF8Text().trim();
        bmp.recycle();
        assertTrue("Should contain 'First': " + result, result.contains("First"));
        assertTrue("Should contain 'Second': " + result, result.contains("Second"));
    }

    @Test
    public void recognizeNumbers() {
        Bitmap bmp = renderText("12345 67890", 600, 100, 48);
        tess.setImage(bmp);
        String result = tess.getUTF8Text().trim();
        bmp.recycle();
        assertTrue("Should contain '12345': " + result, result.contains("12345"));
        assertTrue("Should contain '67890': " + result, result.contains("67890"));
    }

    @Test
    public void emptyImageReturnsEmpty() {
        Bitmap bmp = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        tess.setImage(bmp);
        String result = tess.getUTF8Text().trim();
        bmp.recycle();
        assertTrue("Blank image should return empty or whitespace, got: " + result,
                result.isEmpty());
    }

    @Test
    public void recognizeLargeText() {
        Bitmap bmp = renderText("MAGNIFIER GLASS APPLICATION", 1200, 120, 56);
        tess.setImage(bmp);
        String result = tess.getUTF8Text().trim().toUpperCase();
        bmp.recycle();
        assertTrue("Should contain 'MAGNIFIER': " + result, result.contains("MAGNIFIER"));
        assertTrue("Should contain 'GLASS': " + result, result.contains("GLASS"));
    }

    @Test
    public void recognizeAfterReuse() {
        Bitmap bmp1 = renderText("Alpha", 400, 100, 48);
        tess.setImage(bmp1);
        String r1 = tess.getUTF8Text().trim();
        bmp1.recycle();

        Bitmap bmp2 = renderText("Beta", 400, 100, 48);
        tess.setImage(bmp2);
        String r2 = tess.getUTF8Text().trim();
        bmp2.recycle();

        assertTrue("First should contain 'Alpha': " + r1, r1.contains("Alpha"));
        assertTrue("Second should contain 'Beta': " + r2, r2.contains("Beta"));
    }

    // --- ocrWithIterator tests ---

    @Test
    public void ocrWithIterator_singleLine() {
        Bitmap bmp = renderText("Hello World", 600, 100, 48);
        String result = MagnifierGlassActivity.ocrWithIterator(
                tess, bmp, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
        bmp.recycle();
        assertNotNull(result);
        assertTrue("Should contain 'Hello': " + result, result.contains("Hello"));
        assertTrue("Should contain 'World': " + result, result.contains("World"));
    }

    @Test
    public void ocrWithIterator_multiBlock() {
        Bitmap bmp = renderMultiline(new String[]{
                "First block text",
                "Second block text"
        }, 800, 48);
        String result = MagnifierGlassActivity.ocrWithIterator(
                tess, bmp, TessBaseAPI.PageSegMode.PSM_AUTO);
        bmp.recycle();
        assertNotNull(result);
        assertTrue("Should contain 'First': " + result, result.contains("First"));
        assertTrue("Should contain 'Second': " + result, result.contains("Second"));
    }

    @Test
    public void ocrWithIterator_blankImage() {
        Bitmap bmp = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888);
        new Canvas(bmp).drawColor(Color.WHITE);
        String result = MagnifierGlassActivity.ocrWithIterator(
                tess, bmp, TessBaseAPI.PageSegMode.PSM_AUTO);
        bmp.recycle();
        // May return null (no iterator) or empty string
        assertTrue("Blank should be null or empty, got: " + result,
                result == null || result.isEmpty());
    }

}
