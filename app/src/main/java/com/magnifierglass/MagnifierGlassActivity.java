package com.magnifierglass;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.chrisbanes.photoview.PhotoView;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.magnifierglass.filters.CameraColorFilter;

public class MagnifierGlassActivity extends Activity {
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int STORAGE_PERMISSION_CODE = 101;

    /**
     * Tag name for the Log message.
     */
    private static final String TAG = "MagnifierGlassActivity";

    /**
     * our surface view containing the camera preview image.
     */
    private MagnifierGlassSurface mMagnifierView;

    /**
     * Is the preview running? > Pause Btn + Zoom Btn
     * If not > Play Btn + Photo Share Btn
     */
    private boolean isPreviewActive = true;

    /**
     * stores the brightness level of the screen to restore it after the
     * app gets paused or destroyed.
     */
    private float prevScreenBrightness = -1f;
    private PhotoView mPhotoView;

    private int zoomPanelVisibility = View.VISIBLE;
    private View mMagnifierViewTouchArea;
    private SharedPreferences mSharedPreferences;
    private int mCurrentZoomPercent = -1;
    private static final int ZOOM_STEP = 10;
    private static final SimpleDateFormat SCREENSHOT_DATE_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
    /** Cached frozen bitmap so it survives rotation while paused. */
    private Bitmap mFrozenBitmap;


    private void updateZoomLabel(int percent) {
        TextView label = findViewById(R.id.zoom_label);
        if (label == null) return;
        float factor = mMagnifierView != null ? mMagnifierView.getActualZoomFactor() : -1;
        if (factor < 0) {
            // Fallback before camera is ready — linear estimate from percent
            factor = 1.0f + percent / 100f * 9f;
        }
        label.setText(String.format(java.util.Locale.US, "%.1fx", factor));
    }

    private void applyLocale() {
        LocaleHelper.applyLocale(this);
    }

    private void applyHandedness() {
        String hand = mSharedPreferences.getString(
                getString(R.string.key_preference_handedness), "right");
        boolean leftHanded = "left".equals(hand);
        if (!leftHanded) return; // layouts default to right-handed

        // Only swap views that are direct children of the RelativeLayout
        int[] ids = {R.id.button_exit, R.id.zoom_panel, R.id.button_bar, R.id.button_pause,
                R.id.button_speak};
        for (int id : ids) {
            View v = findViewById(id);
            if (v == null) continue;
            if (!(v.getLayoutParams() instanceof RelativeLayout.LayoutParams)) continue;
            RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) v.getLayoutParams();
            // Swap right/left parent alignment
            boolean wasRight = (p.getRules()[RelativeLayout.ALIGN_PARENT_RIGHT] != 0 ||
                    p.getRules()[RelativeLayout.ALIGN_PARENT_END] != 0);
            boolean wasLeft = (p.getRules()[RelativeLayout.ALIGN_PARENT_LEFT] != 0 ||
                    p.getRules()[RelativeLayout.ALIGN_PARENT_START] != 0);
            // Clear both
            p.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            p.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
            p.removeRule(RelativeLayout.ALIGN_PARENT_END);
            p.removeRule(RelativeLayout.ALIGN_PARENT_START);
            if (wasRight) p.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            if (wasLeft) p.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);

            v.setLayoutParams(p);
        }
    }

    private final View.OnClickListener autoFocusClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mMagnifierView.restartAutoFocus();
        }
    };

    private final View.OnClickListener colorModeClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.startAnimation(animScale);

            mMagnifierView.toggleColorMode();
        }
    };

    private final View.OnLongClickListener colorModeLongClickHandler = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            v.startAnimation(animScale);

            mMagnifierView.setColorMode(0);
            return true;
        }
    };

    private final View.OnClickListener pauseClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.startAnimation(animScale);

            final Button btn = (Button) v;

            if (isPreviewActive) {
                btn.setEnabled(false);
                mMagnifierView.pauseWithFocus(new Runnable() {
                    @Override
                    public void run() {
                        isPreviewActive = false;
                        cameraPreviewIsPaused(btn);
                        btn.setEnabled(true);
                    }
                });
            } else {
                unfreezePreview();
            }
        }
    };

    PhotoView getPhotoView() {
        return mPhotoView;
    }

    @VisibleForTesting
    boolean isTtsReady() {
        return mTtsReady;
    }

    /**
     * Restore frozen-mode UI state (visibility, z-order) without re-setting the bitmap
     * or PhotoView scale. Used by onResume/onConfigurationChanged when the frozen bitmap
     * is already set on the PhotoView.
     */
    private void restoreFrozenUi() {
        mPauseButton.setText("▶");
        mMagnifierViewTouchArea.setVisibility(View.GONE);
        zoomPanelVisibility = mZoomPanel.getVisibility();
        mZoomPanel.setVisibility(View.INVISIBLE);
        mPhotoButton.setVisibility(View.VISIBLE);
        mSpeakButton.setVisibility(View.VISIBLE);
        mSpeakButton.bringToFront();
        mPauseButton.bringToFront();
        mPhotoButton.bringToFront();
        mFlashButton.setAlpha(0.25f);
        mFlashButton.getBackground().mutate().setAlpha(64);
        mMagnifierView.setVisibility(View.INVISIBLE);
        mPhotoView.setVisibility(View.VISIBLE);
        mPhotoView.setAlpha(1f);
    }

    private void cameraPreviewIsPaused(Button playOrPauseButton) {
        playOrPauseButton.setText("▶");
        mMagnifierViewTouchArea.setVisibility(View.GONE);
        zoomPanelVisibility = mZoomPanel.getVisibility();
        mZoomPanel.setVisibility(View.INVISIBLE);
        mPhotoButton.setVisibility(View.VISIBLE);
        mSpeakButton.setVisibility(View.VISIBLE);
        // Bring interactive buttons to front so PhotoView's touch handling can't intercept them.
        // Note: bringToFront() re-orders children in the parent — safe as long as no layout
        // rules reference these buttons by ID (e.g. layout_toLeftOf).
        mSpeakButton.bringToFront();
        playOrPauseButton.bringToFront();
        mPhotoButton.bringToFront();
        mFlashButton.setAlpha(0.25f);
        mFlashButton.getBackground().mutate().setAlpha(64);

        // Enable pinch to zoom via PhotoView from https://github.com/chrisbanes/PhotoView
        if (mFrozenBitmap == null || mFrozenBitmap.isRecycled()) {
            mFrozenBitmap = mMagnifierView.getBitmap();
        }
        if (mFrozenBitmap == null) {
            // Freeze failed — revert to active state and restart preview
            isPreviewActive = true;
            mMagnifierView.resumeCameraPreview();
            cameraPreviewIsActive(playOrPauseButton);
            return;
        }
        mPhotoView.setImageBitmap(mFrozenBitmap);

        mMagnifierView.setVisibility(View.INVISIBLE);

        mPhotoView.setVisibility(View.VISIBLE);
        mPhotoView.setAlpha(1f);

        // match preview zoom: set PhotoView scale to match the preview's fill scale
        final float previewScale = mMagnifierView.getPreviewScale();
        if (previewScale > 0) {
            mPhotoView.post(new Runnable() {
                @Override
                public void run() {
                    // previewScale is an absolute pixel ratio (view px / bitmap px).
                    // PhotoView.setScale() is relative to its own fit-to-view base,
                    // so divide out the base to avoid double-scaling.
                    Drawable d = mPhotoView.getDrawable();
                    if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
                    if (mPhotoView.getWidth() <= 0 || mPhotoView.getHeight() <= 0) return;
                    float fitScale = Math.min(
                            (float) mPhotoView.getWidth() / d.getIntrinsicWidth(),
                            (float) mPhotoView.getHeight() / d.getIntrinsicHeight());
                    float targetScale = (fitScale > 0) ? previewScale / fitScale : 1f;
                    float minScale = mPhotoView.getMinimumScale();
                    if (targetScale < minScale) targetScale = minScale;
                    float maxScale = mPhotoView.getMaximumScale();
                    if (targetScale > maxScale) mPhotoView.setMaximumScale(targetScale * 2);
                    mPhotoView.setScale(targetScale, false);
                }
            });
        }
    }

    private void unfreezePreview() {
        if (mTts != null && mTts.isSpeaking()) {
            mTts.stop();
        }
        if (mFrozenBitmap != null && !mFrozenBitmap.isRecycled()) {
            mFrozenBitmap.recycle();
        }
        mFrozenBitmap = null;
        isPreviewActive = true;
        cameraPreviewIsActive(mPauseButton);
        mMagnifierView.resumeCameraPreview();
    }

    private void cameraPreviewIsActive(Button playOrPauseButton) {
        playOrPauseButton.setText("⏸");
        mMagnifierViewTouchArea.setVisibility(View.VISIBLE);
        mZoomPanel.setVisibility(zoomPanelVisibility);
        mPhotoButton.setVisibility(View.GONE);
        mSpeakButton.setVisibility(View.GONE);
        mFlashButton.setAlpha(1f);
        mFlashButton.getBackground().setAlpha(255);

        mMagnifierView.setVisibility(View.VISIBLE);
        mMagnifierView.setAlpha(1.0f);

        if (mPhotoView != null) {
            mPhotoView.setVisibility(View.GONE);
            mPhotoView.setAlpha(0f);
        }
    }

    private final View.OnClickListener openSettingsClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
            startActivity(intent);
        }
    };

    private final View.OnClickListener flashLightClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.startAnimation(animScale);

            mMagnifierView.nextFlashlightMode();
        }
    };
    private final View.OnClickListener screenshotClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.startAnimation(animScale);
            takeScreenshot();
        }
    };

    private final View.OnClickListener speakClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.startAnimation(animScale);
            recognizeAndSpeak();
        }
    };

    private final View.OnClickListener zoomInClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!isPreviewActive) return;
            mCurrentZoomPercent = Math.min(100, mCurrentZoomPercent + ZOOM_STEP);
            mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
            updateZoomLabel(mCurrentZoomPercent);
            mMagnifierView.restartAutoFocus();
        }
    };

    private final View.OnClickListener zoomOutClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!isPreviewActive) return;
            mCurrentZoomPercent = Math.max(0, mCurrentZoomPercent - ZOOM_STEP);
            mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
            updateZoomLabel(mCurrentZoomPercent);
            mMagnifierView.restartAutoFocus();
        }
    };
    /**
     * Store the reference to swap the icon on it if we pause the preview.
     */
    private View mZoomPanel;
    private Button mPhotoButton;
    private Button mPauseButton;
    private View mFlashButton;
    private Button mSpeakButton;
    private Animation animScale;
    private ScaleGestureDetector mScaleDetector;
    private TextToSpeech mTts;
    private String mTessDataPath;
    /** Persistent Tesseract instance — initialized once, reused per OCR invocation. */
    private TessBaseAPI mTess;
    /** Guards against double-tap launching two OCR threads. */
    private boolean mOcrInProgress;
    /** Background OCR thread — tracked so we can avoid leaking the Activity. */
    private Thread mOcrThread;
    /**
     * Set from TTS init callback (may run on a binder thread), read from UI thread.
     * volatile ensures visibility. mTts itself is assigned on the UI thread in onCreate()
     * before the callback can fire, so no additional synchronization is needed.
     */
    private volatile boolean mTtsReady;

    /**
     * sets the brightness value of the screen to 1F
     */
    protected void setBrightnessToMaximum() {
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        prevScreenBrightness = layout.screenBrightness;
        layout.screenBrightness = 1F;
        getWindow().setAttributes(layout);
    }

    /**
     * resets the brightness value to the previous screen value.
     */
    protected void resetBrightnessToPreviousValue() {
        if (prevScreenBrightness < 0)
            return;
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        layout.screenBrightness = prevScreenBrightness;
        getWindow().setAttributes(layout);
        prevScreenBrightness = -1f;
    }

    /**
     * Uses IMMERSIVE_STICKY to auto-hide system bars after a short delay.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    }

    // This function is called when the user accepts or decline the permission.
    // Request Code is used to check which permission called this function.
    // This request code is provided when the user is prompt for permission.

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super
                .onRequestPermissionsResult(requestCode,
                        permissions,
                        grantResults);

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                restartActivity();
            } else {
                Toast.makeText(this,
                        "Camera Permission Denied",
                        Toast.LENGTH_SHORT)
                        .show();
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takeScreenshot();
            } else {
                Toast.makeText(this,
                        "Storage Permission Denied",
                        Toast.LENGTH_SHORT)
                        .show();
            }
        }
    }

    private void restartActivity() {
        Intent intent = new Intent(getApplicationContext(), this.getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // set proper display orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        applyLocale();
        setContentView(R.layout.activity_magnifier_glass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        applyHandedness();

        if (savedInstanceState != null) {
            mCurrentZoomPercent = savedInstanceState.getInt("zoomPercent", -1);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return;
        }

        animScale = AnimationUtils.loadAnimation(this, R.anim.scale);

        copyTessData();
        mTts = new TextToSpeech(this, status -> {
            if (isFinishing() || isDestroyed()) return;
            if (status == TextToSpeech.SUCCESS) {
                Locale locale = LocaleHelper.getLocale(getApplicationContext());
                mTts.setLanguage(locale);
                mTtsReady = true;
                Log.i(TAG, "TTS ready");
            } else {
                Log.e(TAG, "TTS init failed: " + status);
            }
        });

        mMagnifierView = new MagnifierGlassSurface(this);
        mPhotoView = new PhotoView(this);

        List<CameraColorFilter> filterList = new ArrayList<>();
        filterList.add(MagnifierGlassSurface.NO_FILTER);
        filterList.add(MagnifierGlassSurface.BLACK_WHITE_COLOR_FILTER);
        filterList.add(MagnifierGlassSurface.WHITE_BLACK_COLOR_FILTER);
        filterList.add(MagnifierGlassSurface.BLUE_YELLOW_COLOR_FILTER);
        filterList.add(MagnifierGlassSurface.YELLOW_BLUE_COLOR_FILTER);

        mMagnifierView.setCameraColorFilters(filterList);
        FrameLayout previewLayout = getCameraPreviewFrame();
        previewLayout.setBackgroundColor(Color.BLACK);
        previewLayout.addView(mMagnifierView);
        previewLayout.addView(mPhotoView);

        mPhotoView.setAlpha(0f);

        setButtonListeners();

        // Add listeners to the Preview area (left of the buttons to avoid accidental triggering)
        mMagnifierViewTouchArea = findViewById(R.id.camera_preview_touch_area);
        mMagnifierViewTouchArea.setOnClickListener(autoFocusClickHandler);

        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isPreviewActive) return false;
                float factor = detector.getScaleFactor();
                int delta = Math.round((factor - 1.0f) * 100);
                mCurrentZoomPercent = Math.max(0, Math.min(100, mCurrentZoomPercent + delta));
                mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
                updateZoomLabel(mCurrentZoomPercent);
                return true;
            }
        });
        setupTouchZoom();

    }

    private void setupTouchZoom() {
        mMagnifierViewTouchArea.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mScaleDetector.onTouchEvent(event);
                return event.getPointerCount() > 1;
            }
        });
    }

    private FrameLayout getCameraPreviewFrame() {
        return (FrameLayout) findViewById(R.id.camera_preview);
    }

    private void setButtonListeners() {
        findViewById(R.id.settings_button).setOnClickListener(openSettingsClickHandler);

        findViewById(R.id.button_exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Add listeners to the Zoom buttons
        findViewById(R.id.button_zoom_in).setOnClickListener(zoomInClickHandler);
        findViewById(R.id.button_zoom_out).setOnClickListener(zoomOutClickHandler);
        if (mCurrentZoomPercent < 0) {
            mCurrentZoomPercent = Integer.parseInt(mSharedPreferences.getString(
                    getString(R.string.key_preference_default_zoom), "0"));
        }
        updateZoomLabel(mCurrentZoomPercent);

        mPhotoButton = findViewById(R.id.button_photo);
        mPhotoButton.setOnClickListener(screenshotClickHandler);

        mSpeakButton = findViewById(R.id.button_speak);
        mSpeakButton.setOnClickListener(speakClickHandler);

        // Add a listener to the Flash button
        View flashButton = findViewById(R.id.button_flash);
        flashButton.setOnClickListener(flashLightClickHandler);

        // Add a listener to the Color Filter button
        View colorButton = findViewById(R.id.button_color);
        colorButton.setOnClickListener(colorModeClickHandler);
        colorButton.setOnLongClickListener(colorModeLongClickHandler);

        Button pauseButton = findViewById(R.id.button_pause);
        pauseButton.setOnClickListener(pauseClickHandler);

        mMagnifierView.setZoomPanel(findViewById(R.id.zoom_panel));
        mMagnifierView.setFlashButton(flashButton);

        mZoomPanel = findViewById(R.id.zoom_panel);
        mPauseButton = pauseButton;
        mFlashButton = flashButton;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mTts != null && mTts.isSpeaking()) {
            mTts.stop();
        }
        resetBrightnessToPreviousValue();
        Log.d(TAG, "onPause called!");
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Release camera before re-parenting so that mState is guaranteed
        // STATE_CLOSED when the new surface lifecycle calls enableCamera().
        mMagnifierView.releaseCamera();
        setContentView(R.layout.activity_magnifier_glass);
        applyHandedness();
        FrameLayout previewLayout = getCameraPreviewFrame();
        previewLayout.setBackgroundColor(Color.BLACK);
        // re-attach existing views
        if (mMagnifierView.getParent() != null)
            ((android.view.ViewGroup) mMagnifierView.getParent()).removeView(mMagnifierView);
        if (mPhotoView.getParent() != null)
            ((android.view.ViewGroup) mPhotoView.getParent()).removeView(mPhotoView);
        previewLayout.addView(mMagnifierView);
        previewLayout.addView(mPhotoView);
        setButtonListeners();
        mMagnifierViewTouchArea = findViewById(R.id.camera_preview_touch_area);
        mMagnifierViewTouchArea.setOnClickListener(autoFocusClickHandler);
        setupTouchZoom();
        mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
        updateZoomLabel(mCurrentZoomPercent);

        if (!isPreviewActive) {
            // Re-enter frozen state after rotation if bitmap survived
            if (mFrozenBitmap != null && !mFrozenBitmap.isRecycled()) {
                mPhotoView.setImageBitmap(mFrozenBitmap);
                restoreFrozenUi();
            } else {
                isPreviewActive = true;
                cameraPreviewIsActive(mPauseButton);
            }
        } else {
            mPhotoView.setAlpha(0f);
        }
    }

    @Override
    protected void onDestroy() {
        mTtsReady = false;
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
        }
        // Cancel any in-flight OCR thread to release the Activity reference promptly
        if (mOcrThread != null) {
            mOcrThread.interrupt();
            mOcrThread = null;
        }
        if (mTess != null) {
            mTess.recycle();
            mTess = null;
        }
        if (mFrozenBitmap != null && !mFrozenBitmap.isRecycled()) {
            mFrozenBitmap.recycle();
        }
        mFrozenBitmap = null;
        super.onDestroy();
        Log.d(TAG, "onDestroy called!");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSharedPreferences.getBoolean(getResources().getString(R.string.key_preference_max_brightness), false)) {
            setBrightnessToMaximum();
        }

        if (!isPreviewActive && mPhotoView != null && mFrozenBitmap != null
                && !mFrozenBitmap.isRecycled()) {
            // Restore frozen UI without re-setting bitmap/scale
            restoreFrozenUi();
        } else if (!isPreviewActive) {
            // Frozen bitmap was lost — fall back to live preview
            unfreezePreview();
        }

        Log.d(TAG, "onResume called!");
    }

    /** Map app ISO 639-1 codes → Tesseract ISO 639-3 codes. */
    private static final java.util.Map<String, String> TESS_LANG_MAP;
    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("bg", "bul"); m.put("ca", "cat"); m.put("cs", "ces");
        m.put("da", "dan"); m.put("de", "deu"); m.put("et", "est");
        m.put("el", "ell"); m.put("es", "spa"); m.put("fr", "fra");
        m.put("hr", "hrv"); m.put("is", "isl"); m.put("it", "ita");
        m.put("lv", "lav"); m.put("lt", "lit"); m.put("hu", "hun");
        m.put("nl", "nld"); m.put("nb", "nor"); m.put("pl", "pol");
        m.put("pt", "por"); m.put("ro", "ron"); m.put("sq", "sqi");
        m.put("sk", "slk"); m.put("sl", "slv"); m.put("sr", "srp");
        m.put("fi", "fin"); m.put("sv", "swe"); m.put("tr", "tur");
        m.put("uk", "ukr");
        TESS_LANG_MAP = java.util.Collections.unmodifiableMap(m);
    }

    private static final String TESSDATA_URL =
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/";

    /** Returns the Tesseract language string (e.g. "eng+hun") based on available models. */
    private String getTessLangStr() {
        String iso1 = LocaleHelper.getLocale(this).getLanguage();
        String tess = TESS_LANG_MAP.get(iso1);
        if (tess == null || tess.equals("eng")) return "eng";
        java.io.File f = new java.io.File(getFilesDir() + "/tesseract/tessdata/" + tess + ".traineddata");
        return f.exists() ? "eng+" + tess : "eng";
    }

    private void copyTessData() {
        String basePath = getFilesDir() + "/tesseract/";
        java.io.File tessDir = new java.io.File(basePath + "tessdata");
        java.io.File engFile = new java.io.File(tessDir, "eng.traineddata");
        if (engFile.exists()) {
            mTessDataPath = basePath;
            ensureOcrLanguage();
            return;
        }
        if (!tessDir.mkdirs() && !tessDir.isDirectory()) {
            Log.e(TAG, "Failed to create tessdata directory");
            return;
        }
        java.io.File tmp = new java.io.File(tessDir, "eng.traineddata.tmp");
        try (java.io.InputStream is = getAssets().open("tessdata/eng.traineddata");
             java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to copy tessdata", e);
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(engFile)) {
            Log.e(TAG, "Failed to rename tessdata temp file");
            tmp.delete();
            return;
        }
        mTessDataPath = basePath;
        ensureOcrLanguage();
    }

    /**
     * If the menu language has a Tesseract model and it's not yet downloaded,
     * download it in the background. Reinitializes Tesseract when done.
     */
    private void ensureOcrLanguage() {
        String iso1 = LocaleHelper.getLocale(this).getLanguage();
        String tess = TESS_LANG_MAP.get(iso1);
        if (tess == null || tess.equals("eng")) return;
        java.io.File tessDir = new java.io.File(getFilesDir() + "/tesseract/tessdata");
        java.io.File dest = new java.io.File(tessDir, tess + ".traineddata");
        if (dest.exists()) return;
        final String lang = tess;
        Toast.makeText(this, getString(R.string.ocr_downloading, iso1.toUpperCase(Locale.ROOT)),
                Toast.LENGTH_LONG).show();
        new Thread(() -> {
            java.io.File tmp = new java.io.File(tessDir, lang + ".traineddata.tmp");
            try {
                java.net.URL url = new java.net.URL(TESSDATA_URL + lang + ".traineddata");
                javax.net.ssl.HttpsURLConnection conn =
                        (javax.net.ssl.HttpsURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                // Android 7 lacks newer root CAs (e.g. Let's Encrypt ISRG Root X1).
                // Use a permissive TrustManager for this public GitHub download.
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String t) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String t) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }}, null);
                conn.setSSLSocketFactory(sc.getSocketFactory());
                try (java.io.InputStream is = conn.getInputStream();
                     java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                }
                if (!tmp.renameTo(new java.io.File(tessDir, lang + ".traineddata"))) {
                    Log.e(TAG, "Failed to rename downloaded tessdata: " + lang);
                    tmp.delete();
                    return;
                }
                Log.i(TAG, "Downloaded tessdata: " + lang);
                // Reinit Tesseract with the new language on the UI thread
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (mTess != null) { mTess.recycle(); mTess = null; }
                    Toast.makeText(MagnifierGlassActivity.this,
                            R.string.ocr_download_complete, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to download tessdata: " + lang, e);
                tmp.delete();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        Toast.makeText(MagnifierGlassActivity.this,
                                R.string.ocr_download_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void recognizeAndSpeak() {
        // Fix #3: guard against double-tap
        if (mOcrInProgress) return;
        if (mFrozenBitmap == null || mFrozenBitmap.isRecycled()) {
            // The stabilization pipeline may have recycled our original capture
            // via updatePhotoViewBitmap(); re-acquire from the PhotoView.
            Drawable d = mPhotoView.getDrawable();
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) mFrozenBitmap = b;
            }
        }
        if (mFrozenBitmap == null || mFrozenBitmap.isRecycled()) return;
        if (mTessDataPath == null) {
            Toast.makeText(this, R.string.ocr_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mTtsReady) {
            Toast.makeText(this, R.string.tts_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        if (mTts.isSpeaking()) {
            mTts.stop();
            return;
        }
        mOcrInProgress = true;
        mSpeakButton.setEnabled(false);
        mSpeakButton.setAlpha(0.5f);
        final Bitmap bmp = mFrozenBitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (bmp == null) {
            Toast.makeText(this, R.string.ocr_failed, Toast.LENGTH_SHORT).show();
            resetSpeakButton();
            return;
        }
        // OCR with eng + menu language; Tesseract auto-selects the best match per word.
        if (mTess == null) {
            mTess = new TessBaseAPI();
            if (!mTess.init(mTessDataPath, getTessLangStr())) {
                mTess.recycle();
                mTess = null;
                bmp.recycle();
                Toast.makeText(this, R.string.ocr_failed, Toast.LENGTH_SHORT).show();
                resetSpeakButton();
                return;
            }
        }
        // Fix #1: use WeakReference to avoid leaking the Activity from the background thread
        final WeakReference<MagnifierGlassActivity> weakThis = new WeakReference<>(this);
        final TessBaseAPI tess = mTess;
        mOcrThread = new Thread(() -> {
            // Preprocess: convert to grayscale and apply adaptive threshold
            // to improve recognition of handwriting and low-contrast text.
            Bitmap gray = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(gray);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            c.drawBitmap(bmp, 0, 0, new android.graphics.Paint() {{ setColorFilter(new ColorMatrixColorFilter(cm)); }});
            bmp.recycle();
            // Increase contrast: remap [64,192] → [0,255]
            ColorMatrix contrast = new ColorMatrix(new float[]{
                    2f, 0, 0, 0, -128,
                    0, 2f, 0, 0, -128,
                    0, 0, 2f, 0, -128,
                    0, 0, 0, 1, 0});
            Bitmap enhanced = Bitmap.createBitmap(gray.getWidth(), gray.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(enhanced);
            c2.drawBitmap(gray, 0, 0, new android.graphics.Paint() {{ setColorFilter(new ColorMatrixColorFilter(contrast)); }});
            gray.recycle();

            String result;
            try {
                result = ocrWithIterator(tess, enhanced, TessBaseAPI.PageSegMode.PSM_AUTO);
                // Fallback: if auto-segmentation found nothing, retry as single block
                if (result != null && result.isEmpty()) {
                    tess.setImage(enhanced);
                    result = ocrWithIterator(tess, enhanced, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
                }
            } catch (Exception e) {
                Log.e(TAG, "OCR failed", e);
                result = null;
            } finally {
                enhanced.recycle();
            }
            final String text = result;
            MagnifierGlassActivity activity = weakThis.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                MagnifierGlassActivity a = weakThis.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                if (text == null) {
                    Toast.makeText(a, R.string.ocr_failed, Toast.LENGTH_SHORT).show();
                } else if (text.isEmpty()) {
                    Toast.makeText(a, R.string.ocr_no_text, Toast.LENGTH_SHORT).show();
                } else {
                    Locale detected = detectLanguage(text);
                    int langResult = a.mTts.setLanguage(detected);
                    if (langResult == TextToSpeech.LANG_MISSING_DATA
                            || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Fall back to menu language
                        Locale fallback = LocaleHelper.getLocale(a);
                        int fbResult = a.mTts.setLanguage(fallback);
                        if (fbResult == TextToSpeech.LANG_MISSING_DATA
                                || fbResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Toast.makeText(a, R.string.tts_language_unavailable, Toast.LENGTH_SHORT).show();
                            a.resetSpeakButton();
                            return;
                        }
                    }
                    a.mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ocr");
                }
                a.resetSpeakButton();
            });
        });
        mOcrThread.start();
    }

    /**
     * Run OCR with the given page segmentation mode and return text in reading order.
     * Uses ResultIterator to walk blocks and paragraphs.
     */
    private static String ocrWithIterator(TessBaseAPI tess, Bitmap bmp, int psm) {
        tess.setPageSegMode(psm);
        tess.setImage(bmp);
        tess.getUTF8Text(); // trigger recognition
        ResultIterator ri = tess.getResultIterator();
        if (ri == null) return null;
        StringBuilder sb = new StringBuilder();
        ri.begin();
        do {
            if (ri.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) {
                if (sb.length() > 0) sb.append(".\n");
            } else if (ri.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)) {
                if (sb.length() > 0) sb.append(".\n");
            }
            String word = ri.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
            if (word != null && !word.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n')
                    sb.append(' ');
                sb.append(word);
            }
        } while (ri.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));
        ri.delete();
        return sb.toString().trim();
    }

    private void resetSpeakButton() {
        mOcrInProgress = false;
        mSpeakButton.setAlpha(1f);
        mSpeakButton.setEnabled(true);
    }

    /**
     * Detect language from OCR'd text by checking for Hungarian-specific characters.
     * Falls back to English if no Hungarian markers are found.
     */
    private static Locale detectLanguage(String text) {
        // Hungarian double-acute letters (ő, ű) are unique to Hungarian
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0151' || c == '\u0171' || c == '\u0150' || c == '\u0170') {
                return new Locale("hu");
            }
        }
        // Common Hungarian accented chars shared with other languages,
        // but a high density suggests Hungarian
        int hunChars = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ("áéíóöúüÁÉÍÓÖÚÜ".indexOf(c) >= 0) hunChars++;
            }
        }
        if (letters > 0 && hunChars * 100 / letters > 5) {
            return new Locale("hu");
        }
        return Locale.ENGLISH;
    }

    /**
     * See https://stackoverflow.com/questions/2661536/how-to-programmatically-take-a-screenshot-in-android#5651242
     */
    private void takeScreenshot() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
            return;
        }
        try {
            String timeStamp = SCREENSHOT_DATE_FORMAT.format(new Date());
            String imageFileName = "JPEG_" + timeStamp + ".jpg";
            Bitmap bitmap = mMagnifierView.getBitmap();
            if (bitmap == null) return;
            mMagnifierView.playActionSoundShutter();
            Uri uri = Util.saveImageOnAllAPIs(bitmap, this, "", imageFileName, MagnifierGlassSurface.JPEG_QUALITY);
            bitmap.recycle();
            if (uri != null)
                openScreenshot(uri);
        } catch (Throwable e) {
            Log.e(TAG, "takeScreenshot failed", e);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("zoomPercent", mCurrentZoomPercent);
    }

    /**
     * See https://stackoverflow.com/questions/2661536/how-to-programmatically-take-a-screenshot-in-android#5651242
     */
    private void openScreenshot(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}
