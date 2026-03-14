package com.magnifierglass;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.magnifierglass.filters.CameraColorFilter;

public class MagnifierGlassActivity extends Activity {
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int STORAGE_PERMISSION_CODE = 101;

    /**
     * Tag name for the Log message.
     */
    private static final String TAG = "MagnifierGlassActivity";

    private static final float FLASH_ALPHA_ON = 1f;
    private static final int FLASH_BG_ALPHA_ON = 255;
    private static final float FLASH_ALPHA_OFF = 0.25f;
    private static final int FLASH_BG_ALPHA_OFF = 64;

    private void setFlashButtonAppearance(boolean on) {
        mFlashButton.setAlpha(on ? FLASH_ALPHA_ON : FLASH_ALPHA_OFF);
        mFlashButton.getBackground().mutate().setAlpha(on ? FLASH_BG_ALPHA_ON : FLASH_BG_ALPHA_OFF);
    }

    /**
     * our surface view containing the camera preview image.
     */
    private MagnifierGlassSurface mMagnifierView;

    /**
     * Is the preview running? > Pause Btn + Zoom Btn
     * If not > Play Btn + Photo Share Btn
     * UI-thread-only — no synchronization needed.
     */
    private boolean isPreviewActive = true;
    /** True while a freeze transition is in progress (prevents re-entrant gestures). */
    private boolean isFreezing = false;

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
    private int mZoomStep = 10;
    private boolean mVolumeZoom = true;
    private static final String SCREENSHOT_DATE_PATTERN = "yyyyMMdd_HHmmss";
    /** Cached frozen bitmap so it survives rotation while paused. UI-thread-only. */
    private Bitmap mFrozenBitmap;
    /** Locale applied in onCreate — used to detect changes in onResume. */
    private Locale mAppliedLocale;
    /** UI-thread-only — updated in onCreate, onResume, onConfigurationChanged. */
    private android.content.Context mLocalizedContext;

    private void reloadZoomStep() {
        try {
            mZoomStep = Integer.parseInt(mSharedPreferences.getString(
                    getString(R.string.key_preference_zoom_step), "10"));
        } catch (NumberFormatException e) {
            mZoomStep = 10;
        }
        mVolumeZoom = mSharedPreferences.getBoolean(
                getString(R.string.key_preference_volume_zoom), true);
    }

    /** Set TTS language with English fallback if the requested locale is unavailable. */
    private void setTtsLanguageWithFallback(Locale locale) {
        int result = mTts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS language unavailable for " + locale + ", falling back to English");
            mTts.setLanguage(Locale.ENGLISH);
        }
    }

    private void updateZoomLabel(int percent, boolean announce) {
        TextView label = findViewById(R.id.zoom_label);
        if (label == null) return;
        float factor = mMagnifierView != null ? mMagnifierView.getActualZoomFactor() : -1;
        if (factor < 0) {
            // Fallback before camera is ready — linear estimate from percent
            factor = 1.0f + percent / 100f * 9f;
        }
        String text = String.format(Locale.US, "%.1fx", factor);
        label.setText(text);
        // Announce zoom level for accessibility (only on user-initiated changes,
        // don't interrupt OCR speech)
        if (announce && mTtsReady && mTts != null && !mTts.isSpeaking()) {
            setTtsLanguageWithFallback(LocaleHelper.getLocale(this));
            String num = (Math.abs(factor - Math.round(factor)) < 0.01f)
                    ? String.valueOf(Math.round(factor))
                    : String.format(Locale.US, "%.1f", factor);
            String spoken = num + " " + getLocalizedString(R.string.zoom_times_suffix);
            mTts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "zoom");
        }
    }

    /** Speak a message, retrying up to 3 times with 500 ms delay if TTS isn't ready yet. */
    private static void speakWhenReady(WeakReference<MagnifierGlassActivity> ref,
                                       String msg, Locale locale, String utteranceId, int attempt) {
        MagnifierGlassActivity self = ref.get();
        if (self == null || self.isFinishing() || self.isDestroyed()) return;
        if (self.mTtsReady && self.mTts != null) {
            self.setTtsLanguageWithFallback(locale);
            self.mTts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        } else if (attempt < 3) {
            View root = self.findViewById(R.id.magnifier_glass_root);
            if (root != null) {
                root.postDelayed(() -> speakWhenReady(ref, msg, locale, utteranceId, attempt + 1), 500);
            }
        }
    }

    /** Speak a status message in the menu language. */
    private void speakStatus(String msg, String utteranceId) {
        if (mTtsReady && mTts != null) {
            setTtsLanguageWithFallback(LocaleHelper.getLocale(this));
            mTts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }

    /** Get a string resource resolved in the menu language. */
    private String getLocalizedString(int resId) {
        if (mLocalizedContext == null) updateLocalizedContext();
        return mLocalizedContext.getString(resId);
    }

    private void updateLocalizedContext() {
        Locale locale = LocaleHelper.getLocale(this);
        Configuration conf = new Configuration(getResources().getConfiguration());
        conf.setLocale(locale);
        mLocalizedContext = createConfigurationContext(conf);
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

    /**
     * Attempts to freeze the preview with autofocus. Manages isPreviewActive
     * and button state. Returns true if freeze was initiated.
     * Note: pauseWithFocus may invoke the callback synchronously on autofocus failure.
     */
    private boolean tryFreezePreview() {
        if (!isPreviewActive || isFreezing) return false;
        isFreezing = true;
        isPreviewActive = false;
        mPauseButton.setEnabled(false);
        if (!mMagnifierView.pauseWithFocus(() -> {
            isFreezing = false;
            cameraPreviewIsPaused(mPauseButton);
            mPauseButton.setEnabled(true);
        })) {
            isFreezing = false;
            isPreviewActive = true;
            mPauseButton.setEnabled(true);
            return false;
        }
        return true;
    }

    private final GestureDetector.OnDoubleTapListener mPhotoViewDoubleTapListener =
            new GestureDetector.OnDoubleTapListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) { return false; }
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (!isPreviewActive) {
                        mPauseButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        unfreezePreview();
                        return true;
                    }
                    return false;
                }
                @Override
                public boolean onDoubleTapEvent(MotionEvent e) { return false; }
            };

    private final View.OnClickListener colorModeClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (animScale != null) v.startAnimation(animScale);

            mMagnifierView.toggleColorMode();
        }
    };

    private final View.OnLongClickListener colorModeLongClickHandler = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (animScale != null) v.startAnimation(animScale);

            mMagnifierView.setColorMode(0);
            return true;
        }
    };

    private final View.OnClickListener pauseClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (animScale != null) v.startAnimation(animScale);

            if (isPreviewActive) {
                tryFreezePreview();
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
     * Common frozen-mode UI state: hide live controls, show frozen controls,
     * bring interactive buttons to front so PhotoView can't intercept them.
     */
    private void applyFrozenUiState() {
        mPauseButton.setText("▶");
        mMagnifierViewTouchArea.setVisibility(View.GONE);
        zoomPanelVisibility = mZoomPanel.getVisibility();
        mZoomPanel.setVisibility(View.INVISIBLE);
        mPhotoButton.setVisibility(View.VISIBLE);
        mSpeakButton.setVisibility(View.VISIBLE);
        // bringToFront() re-orders children in the parent — safe as long as no layout
        // rules reference these buttons by ID (e.g. layout_toLeftOf).
        mSpeakButton.bringToFront();
        mPauseButton.bringToFront();
        mPhotoButton.bringToFront();
        setFlashButtonAppearance(false);
    }

    /**
     * Restore frozen-mode UI state (visibility, z-order) without re-setting the bitmap
     * or PhotoView scale. Used by onResume/onConfigurationChanged when the frozen bitmap
     * is already set on the PhotoView.
     */
    private void restoreFrozenUi() {
        applyFrozenUiState();
        mMagnifierView.setVisibility(View.INVISIBLE);
        mPhotoView.setVisibility(View.VISIBLE);
        mPhotoView.setAlpha(1f);
    }

    private void cameraPreviewIsPaused(Button playOrPauseButton) {
        applyFrozenUiState();
        playOrPauseButton.announceForAccessibility(
                getLocalizedString(R.string.announce_frozen));

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
        if (isFreezing) return;
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
        mPauseButton.announceForAccessibility(
                getLocalizedString(R.string.announce_resumed));
    }

    private void cameraPreviewIsActive(Button playOrPauseButton) {
        playOrPauseButton.setText("⏸");
        mMagnifierViewTouchArea.setVisibility(View.VISIBLE);
        mZoomPanel.setVisibility(zoomPanelVisibility);
        mPhotoButton.setVisibility(View.GONE);
        mSpeakButton.setVisibility(View.GONE);

        // Reflect actual torch state — auto-torch may have turned it off while frozen
        setFlashButtonAppearance(mMagnifierView.isFlashOn());

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
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
            startActivity(intent);
        }
    };

    private final View.OnClickListener flashLightClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (animScale != null) v.startAnimation(animScale);

            mMagnifierView.nextFlashlightMode();
        }
    };
    private final View.OnClickListener screenshotClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (animScale != null) v.startAnimation(animScale);
            takeScreenshot();
        }
    };

    private final View.OnClickListener speakClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (animScale != null) v.startAnimation(animScale);
            recognizeAndSpeak();
        }
    };

    private void zoomIn() {
        if (!isPreviewActive) return;
        mCurrentZoomPercent = Math.min(100, mCurrentZoomPercent + mZoomStep);
        mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
        updateZoomLabel(mCurrentZoomPercent, true);
        mMagnifierView.restartAutoFocus();
    }

    private void zoomOut() {
        if (!isPreviewActive) return;
        mCurrentZoomPercent = Math.max(0, mCurrentZoomPercent - mZoomStep);
        mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
        updateZoomLabel(mCurrentZoomPercent, true);
        mMagnifierView.restartAutoFocus();
    }

    private final View.OnClickListener zoomInClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            zoomIn();
        }
    };

    private final View.OnClickListener zoomOutClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            zoomOut();
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
    private GestureDetector mGestureDetector;

    private final ScaleGestureDetector.SimpleOnScaleGestureListener mScaleListener =
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (!isPreviewActive) return false;
                    float factor = detector.getScaleFactor();
                    int delta = Math.round((factor - 1.0f) * 100);
                    mCurrentZoomPercent = Math.max(0, Math.min(100, mCurrentZoomPercent + delta));
                    mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
                    updateZoomLabel(mCurrentZoomPercent, true);
                    return true;
                }
            };
    private volatile TextToSpeech mTts;
    private volatile String mTessDataPath;
    /** Persistent Tesseract instance — initialized once, reused per OCR invocation. */
    private TessBaseAPI mTess;
    /** Lock protecting mTess — held during OCR and during recycle/nulling. */
    private final ReentrantLock mTessLock = new ReentrantLock();
    /** Set in onDestroy so the OCR thread can clean up mTess if it outlives the timeout. */
    private volatile boolean mShuttingDown;
    /** Guards against double-tap launching two OCR threads. UI-thread-only. */
    private boolean mOcrInProgress;
    /** Last successfully recognized OCR text, for repeat-speak via long-press. UI-thread-only. */
    private String mLastOcrText;
    /** Background OCR thread — tracked so we can avoid leaking the Activity. */
    private Thread mOcrThread;
    /** Background tessdata download thread — interrupted in onDestroy. */
    private volatile Thread mDownloadThread;
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
     * Volume keys zoom during live preview; when frozen, they fall through
     * to the default handler (system volume) since camera zoom is irrelevant.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mVolumeZoom && isPreviewActive) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                zoomIn();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                zoomOut();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
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
        mAppliedLocale = LocaleHelper.getLocale(this);
        updateLocalizedContext();
        setContentView(R.layout.activity_magnifier_glass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        applyHandedness();

        if (savedInstanceState != null) {
            mCurrentZoomPercent = savedInstanceState.getInt("zoomPercent", -1);
            mLastOcrText = savedInstanceState.getString("lastOcrText");
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return;
        }

        float animatorScale = Settings.Global.getFloat(
                getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (animatorScale > 0f) {
            animScale = AnimationUtils.loadAnimation(this, R.anim.scale);
        }

        // Copy tessdata off the UI thread to avoid ANR on first launch (~15 MB)
        new Thread(() -> {
            copyTessData();
        }, "tessdata-copy").start();
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
        mPhotoView.setOnDoubleTapListener(mPhotoViewDoubleTapListener);

        setButtonListeners();

        // Touch area: double-tap freeze, single-tap autofocus, pinch-to-zoom
        mMagnifierViewTouchArea = findViewById(R.id.camera_preview_touch_area);

        mScaleDetector = new ScaleGestureDetector(this, mScaleListener);
        setupTouchZoom();

    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchZoom() {
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isPreviewActive) return false;
                if (tryFreezePreview()) {
                    mPauseButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isPreviewActive) {
                    mMagnifierView.restartAutoFocus();
                    return true;
                }
                return false;
            }
        });
        // Accessibility: click triggers autofocus for TalkBack / switch access users.
        // The onTouch handler below consumes all touch events, so this click listener
        // is only reachable via accessibility services (performClick from TalkBack).
        mMagnifierViewTouchArea.setOnClickListener(v -> {
            if (isPreviewActive) mMagnifierView.restartAutoFocus();
        });
        mMagnifierViewTouchArea.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isPreviewActive) mScaleDetector.onTouchEvent(event);
                boolean gestureHandled = mGestureDetector.onTouchEvent(event);
                // GestureDetector returns true on ACTION_DOWN, claiming the full
                // sequence for single/double-tap detection and pinch-to-zoom.
                return event.getPointerCount() > 1 || gestureHandled;
            }
        });
    }

    private FrameLayout getCameraPreviewFrame() {
        return (FrameLayout) findViewById(R.id.camera_preview);
    }

    private void setButtonListeners() {
        findViewById(R.id.settings_button).setOnClickListener(openSettingsClickHandler);

        View exitButton = findViewById(R.id.button_exit);
        exitButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            // Skip toast when TalkBack is active — it already announces the contentDescription
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null || !am.isTouchExplorationEnabled()) {
                Toast.makeText(this, getLocalizedString(R.string.button_exit_description), Toast.LENGTH_SHORT).show();
            }
        });
        exitButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                finish();
                return true;
            }
        });

        // Add listeners to the Zoom buttons
        findViewById(R.id.button_zoom_in).setOnClickListener(zoomInClickHandler);
        findViewById(R.id.button_zoom_out).setOnClickListener(zoomOutClickHandler);
        if (mCurrentZoomPercent < 0) {
            // Restore zoom: preset > last session > default setting
            if (mSharedPreferences.contains(getString(R.string.key_preference_preset_zoom))) {
                mCurrentZoomPercent = mSharedPreferences.getInt(
                        getString(R.string.key_preference_preset_zoom), 0);
            } else {
                mCurrentZoomPercent = mSharedPreferences.getInt(
                        getString(R.string.key_preference_zoom_percent), -1);
            }
            if (mCurrentZoomPercent < 0) {
                // default_zoom is a String (DropDownPreference) — unlike the int keys above
                try {
                    mCurrentZoomPercent = Integer.parseInt(mSharedPreferences.getString(
                            getString(R.string.key_preference_default_zoom), "0"));
                } catch (NumberFormatException e) {
                    mCurrentZoomPercent = 0;
                }
            }
        }
        updateZoomLabel(mCurrentZoomPercent, false);

        mPhotoButton = findViewById(R.id.button_photo);
        mPhotoButton.setOnClickListener(screenshotClickHandler);

        mSpeakButton = findViewById(R.id.button_speak);
        mSpeakButton.setOnClickListener(speakClickHandler);
        mSpeakButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (mLastOcrText != null && mTtsReady && mTts != null) {
                    setTtsLanguageWithFallback(LocaleHelper.getLocale(MagnifierGlassActivity.this));
                    mTts.speak(mLastOcrText, TextToSpeech.QUEUE_FLUSH, null, "ocr");
                }
                return true;
            }
        });

        // Add a listener to the Flash button
        View flashButton = findViewById(R.id.button_flash);
        flashButton.setOnClickListener(flashLightClickHandler);

        // Add a listener to the Color Filter button
        View colorButton = findViewById(R.id.button_color);
        colorButton.setOnClickListener(colorModeClickHandler);
        colorButton.setOnLongClickListener(colorModeLongClickHandler);

        Button pauseButton = findViewById(R.id.button_pause);
        pauseButton.setOnClickListener(pauseClickHandler);

        mZoomPanel = findViewById(R.id.zoom_panel);
        mPauseButton = pauseButton;
        mFlashButton = flashButton;

        mMagnifierView.setZoomPanel(mZoomPanel);
        mMagnifierView.setFlashButton(mFlashButton);
        mMagnifierView.setFlashStateListener(this::setFlashButtonAppearance);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isFreezing = false;
        if (mTts != null && mTts.isSpeaking()) {
            mTts.stop();
        }
        // Persist last-used zoom percent and flash state so they survive app restart
        if (mCurrentZoomPercent >= 0 && mMagnifierView != null) {
            mSharedPreferences.edit()
                    .putInt(getString(R.string.key_preference_zoom_percent), mCurrentZoomPercent)
                    .putBoolean(getString(R.string.key_preference_flash_state), mMagnifierView.isFlashOn())
                    .apply();
        }
        resetBrightnessToPreviousValue();
        Log.d(TAG, "onPause called!");
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        isFreezing = false;
        updateLocalizedContext();
        // Release camera before re-parenting so that mState is guaranteed
        // STATE_CLOSED when the new surface lifecycle calls enableCamera().
        mMagnifierView.releaseCamera();
        setContentView(R.layout.activity_magnifier_glass);
        applyHandedness();
        FrameLayout previewLayout = getCameraPreviewFrame();
        previewLayout.setBackgroundColor(Color.BLACK);
        // re-attach existing views
        if (mMagnifierView.getParent() != null)
            ((ViewGroup) mMagnifierView.getParent()).removeView(mMagnifierView);
        if (mPhotoView.getParent() != null)
            ((ViewGroup) mPhotoView.getParent()).removeView(mPhotoView);
        previewLayout.addView(mMagnifierView);
        previewLayout.addView(mPhotoView);
        mPhotoView.setOnDoubleTapListener(mPhotoViewDoubleTapListener);
        setButtonListeners();
        mMagnifierViewTouchArea = findViewById(R.id.camera_preview_touch_area);
        mScaleDetector = new ScaleGestureDetector(this, mScaleListener);
        setupTouchZoom();
        mMagnifierView.setZoomLevelPercent(mCurrentZoomPercent);
        updateZoomLabel(mCurrentZoomPercent, false);

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
        mShuttingDown = true;
        mTtsReady = false;
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
        }
        // Cancel any in-flight OCR thread and wait for it to release mTess
        if (mOcrThread != null) {
            mOcrThread.interrupt();
            try { mOcrThread.join(500); } catch (InterruptedException ignored) {}
            mOcrThread = null;
        }
        // Cancel any in-flight tessdata download
        if (mDownloadThread != null) {
            mDownloadThread.interrupt();
            try { mDownloadThread.join(300); } catch (InterruptedException ignored) {}
            mDownloadThread = null;
        }
        try {
            if (mTessLock.tryLock(500, TimeUnit.MILLISECONDS)) {
                try {
                    if (mTess != null) {
                        mTess.recycle();
                        mTess = null;
                    }
                } finally {
                    mTessLock.unlock();
                }
            } else {
                Log.w(TAG, "Could not acquire mTessLock in onDestroy — OCR thread will clean up");
            }
        } catch (InterruptedException ignored) {
        }
        if (mFrozenBitmap != null && !mFrozenBitmap.isRecycled()) {
            mFrozenBitmap.recycle();
        }
        mFrozenBitmap = null;
        mMagnifierView.setFlashStateListener(null);
        super.onDestroy();
        Log.d(TAG, "onDestroy called!");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the menu language changed in Settings, recreate to apply new locale.
        // Safe: mAppliedLocale is set in onCreate from the same getLocale() call,
        // so equals() will match after recreate completes.
        Locale current = LocaleHelper.getLocale(this);
        if (mAppliedLocale != null && !current.equals(mAppliedLocale)) {
            if (mFrozenBitmap != null && !mFrozenBitmap.isRecycled()) {
                mFrozenBitmap.recycle();
            }
            mFrozenBitmap = null;
            recreate();
            return;
        }
        reloadZoomStep();
        updateLocalizedContext();
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
    @VisibleForTesting
    static final Map<String, String> TESS_LANG_MAP;
    static {
        Map<String, String> m = new HashMap<>();
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
        TESS_LANG_MAP = Collections.unmodifiableMap(m);
    }

    private static final String TESSDATA_URL =
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/";

    /** Returns the Tesseract language string (e.g. "eng+hun") based on available models. */
    private String getTessLangStr() {
        String iso1 = LocaleHelper.getLocale(this).getLanguage();
        String tess = TESS_LANG_MAP.get(iso1);
        if (tess == null || tess.equals("eng")) return "eng";
        File f = new File(getFilesDir() + "/tesseract/tessdata/" + tess + ".traineddata");
        return f.exists() ? "eng+" + tess : "eng";
    }

    private void copyTessData() {
        String basePath = getFilesDir() + "/tesseract/";
        File tessDir = new File(basePath + "tessdata");
        File engFile = new File(tessDir, "eng.traineddata");
        if (engFile.exists()) {
            mTessDataPath = basePath;
            ensureOcrLanguage();
            return;
        }
        if (!tessDir.mkdirs() && !tessDir.isDirectory()) {
            Log.e(TAG, "Failed to create tessdata directory");
            return;
        }
        File tmp = new File(tessDir, "eng.traineddata.tmp");
        try (InputStream is = getAssets().open("tessdata/eng.traineddata");
             FileOutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
        } catch (IOException e) {
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
     * <p>
     * May be called from a background thread (via copyTessData). Captures locale
     * and UI strings eagerly, then posts UI work to the main thread.
     */
    private void ensureOcrLanguage() {
        Locale locale = LocaleHelper.getLocale(getApplicationContext());
        String iso1 = locale.getLanguage();
        String tess = TESS_LANG_MAP.get(iso1);
        if (tess == null || tess.equals("eng")) return;
        File tessDir = new File(getFilesDir() + "/tesseract/tessdata");
        File dest = new File(tessDir, tess + ".traineddata");
        if (dest.exists()) return;
        final String lang = tess;
        // Use application context for getResources() — safe from background threads
        // (Activity.getResources() can race with config changes on older Android)
        Configuration conf = new Configuration(getApplicationContext().getResources().getConfiguration());
        conf.setLocale(locale);
        android.content.Context localizedCtx = getApplicationContext().createConfigurationContext(conf);
        final String toastMsg = localizedCtx.getString(R.string.ocr_downloading,
                iso1.toUpperCase(Locale.ROOT));
        final String waitMsg = localizedCtx.getString(R.string.ocr_downloading_wait);
        runOnUiThread(() -> {
            Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show();
            speakWhenReady(new WeakReference<>(this), waitMsg, locale, "download", 0);
        });
        Thread dl = new Thread(() -> {
            File tmp = new File(tessDir, lang + ".traineddata.tmp");
            try {
                SSLSocketFactory sslFactory = getIsrgSslContext().getSocketFactory();
                // Manual redirect loop so every hop uses our custom SSLSocketFactory
                // (setInstanceFollowRedirects may create a new connection without it)
                URL url = new URL(TESSDATA_URL + lang + ".traineddata");
                HttpsURLConnection conn = null;
                boolean downloaded = false;
                for (int redirects = 0; redirects < 5; redirects++) {
                    java.net.URLConnection raw = url.openConnection();
                    if (!(raw instanceof HttpsURLConnection)) {
                        throw new IOException("Unexpected non-HTTPS redirect to " + url);
                    }
                    conn = (HttpsURLConnection) raw;
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setSSLSocketFactory(sslFactory);
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (location == null) throw new IOException("Redirect without Location");
                        url = new URL(url, location);
                        if (!"https".equals(url.getProtocol())) {
                            throw new IOException("Refusing non-HTTPS redirect to " + url);
                        }
                        String host = url.getHost();
                        if (!host.endsWith(".githubusercontent.com") && !host.endsWith(".github.com")) {
                            throw new IOException("Refusing redirect to untrusted host: " + host);
                        }
                        continue;
                    }
                    if (code != 200) {
                        InputStream err = conn.getErrorStream();
                        if (err != null) err.close();
                        conn.disconnect();
                        throw new IOException("HTTP " + code);
                    }
                    try (InputStream is = conn.getInputStream();
                         FileOutputStream os = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                    } finally {
                        conn.disconnect();
                    }
                    downloaded = true;
                    break;
                }
                if (!downloaded) throw new IOException("Too many redirects");
                if (!tmp.renameTo(new File(tessDir, lang + ".traineddata"))) {
                    Log.e(TAG, "Failed to rename downloaded tessdata: " + lang);
                    tmp.delete();
                    return;
                }
                Log.i(TAG, "Downloaded tessdata: " + lang);
                // Reinit Tesseract with the new language on the UI thread
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    // tryLock: if OCR is running, skip — next invocation will reinit
                    if (mTessLock.tryLock()) {
                        try {
                            if (mTess != null) { mTess.recycle(); mTess = null; }
                        } finally {
                            mTessLock.unlock();
                        }
                    }
                    Toast.makeText(MagnifierGlassActivity.this,
                            getLocalizedString(R.string.ocr_download_complete), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to download tessdata: " + lang, e);
                tmp.delete();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        String msg = getLocalizedString(R.string.ocr_download_failed);
                        Toast.makeText(MagnifierGlassActivity.this, msg, Toast.LENGTH_SHORT).show();
                        speakStatus(msg, "download_error");
                    }
                });
            }
        }, "tessdata-download");
        mDownloadThread = dl;
        if (mShuttingDown) {
            dl.interrupt();
            return;
        }
        dl.start();
    }

    /** Cached SSLContext with system CAs + bundled ISRG Root X1 for Android 7 compat.
     *  Note: bundled certificate expires 2035-06-04. */
    private SSLContext mIsrgSslContext;
    private final Object mSslLock = new Object();

    private SSLContext getIsrgSslContext() throws GeneralSecurityException, IOException {
        synchronized (mSslLock) {
            if (mIsrgSslContext != null) return mIsrgSslContext;
            KeyStore ks = KeyStore.getInstance(
                    KeyStore.getDefaultType());
            ks.load(null, null);
            try (InputStream caInput = getApplicationContext().getResources().openRawResource(R.raw.isrg_root_x1)) {
                ks.setCertificateEntry("isrg_root_x1",
                        CertificateFactory.getInstance("X.509")
                                .generateCertificate(caInput));
            }
            TrustManagerFactory sysTmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sysTmf.init((KeyStore) null);
            int aliasIdx = 0;
            for (TrustManager tm : sysTmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    for (X509Certificate c :
                            ((X509TrustManager) tm).getAcceptedIssuers()) {
                        ks.setCertificateEntry("system_" + aliasIdx++, c);
                    }
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            mIsrgSslContext = SSLContext.getInstance("TLS");
            mIsrgSslContext.init(null, tmf.getTrustManagers(), null);
            return mIsrgSslContext;
        }
    }

    private void recognizeAndSpeak() {
        // Guard against double-tap (UI-thread-only flag)
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
            Toast.makeText(this, getLocalizedString(R.string.ocr_not_ready), Toast.LENGTH_SHORT).show();
            speakStatus(getLocalizedString(R.string.ocr_not_ready), "ocr_wait");
            return;
        }
        if (!mTtsReady) {
            Toast.makeText(this, getLocalizedString(R.string.tts_not_ready), Toast.LENGTH_SHORT).show();
            return;
        }
        if (mTts.isSpeaking()) {
            mTts.stop();
            return;
        }
        mOcrInProgress = true;
        mSpeakButton.setEnabled(false);
        mSpeakButton.setAlpha(0.5f);
        Toast.makeText(this, getLocalizedString(R.string.ocr_in_progress), Toast.LENGTH_SHORT).show();
        speakStatus(getLocalizedString(R.string.ocr_in_progress), "ocr_wait");
        // Snapshot the bitmap reference before spawning the background thread.
        // Both this read and any recycle/null happen on the UI thread, so no race here;
        // the copy below ensures the OCR thread gets its own independent bitmap.
        final Bitmap frozen = mFrozenBitmap;
        if (frozen == null || frozen.isRecycled()) {
            resetSpeakButton();
            return;
        }
        final Bitmap bmp;
        try {
            bmp = frozen.copy(Bitmap.Config.ARGB_8888, false);
        } catch (IllegalStateException | OutOfMemoryError e) {
            Log.w(TAG, "Failed to copy frozen bitmap: " + e.getMessage());
            resetSpeakButton();
            return;
        }
        if (bmp == null) {
            Toast.makeText(this, getLocalizedString(R.string.ocr_failed), Toast.LENGTH_SHORT).show();
            resetSpeakButton();
            return;
        }
        // Initialize Tesseract under lock (UI thread) — non-blocking to avoid jank
        if (!mTessLock.tryLock()) {
            bmp.recycle();
            Toast.makeText(this, getLocalizedString(R.string.ocr_not_ready), Toast.LENGTH_SHORT).show();
            resetSpeakButton();
            return;
        }
        try {
            if (mTess == null) {
                mTess = new TessBaseAPI();
                if (!mTess.init(mTessDataPath, getTessLangStr())) {
                    mTess.recycle();
                    mTess = null;
                    bmp.recycle();
                    Toast.makeText(this, getLocalizedString(R.string.ocr_failed), Toast.LENGTH_SHORT).show();
                    resetSpeakButton();
                    return;
                }
            }
        } finally {
            mTessLock.unlock();
        }
        final WeakReference<MagnifierGlassActivity> weakThis = new WeakReference<>(this);
        final Locale menuLocale = LocaleHelper.getLocale(this);
        mOcrThread = new Thread(() -> {
            // Preprocess: grayscale + contrast enhancement
            Bitmap gray;
            Bitmap enhanced;
            try {
                gray = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(gray);
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0);
                Paint grayPaint = new Paint();
                grayPaint.setColorFilter(new ColorMatrixColorFilter(cm));
                c.drawBitmap(bmp, 0, 0, grayPaint);
                bmp.recycle();
                // Increase contrast: remap [64,192] → [0,255]
                ColorMatrix contrast = new ColorMatrix(new float[]{
                        2f, 0, 0, 0, -128,
                        0, 2f, 0, 0, -128,
                        0, 0, 2f, 0, -128,
                        0, 0, 0, 1, 0});
                enhanced = Bitmap.createBitmap(gray.getWidth(), gray.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c2 = new Canvas(enhanced);
                Paint contrastPaint = new Paint();
                contrastPaint.setColorFilter(new ColorMatrixColorFilter(contrast));
                c2.drawBitmap(gray, 0, 0, contrastPaint);
                gray.recycle();
                gray = null;
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OOM during image preprocessing", e);
                MagnifierGlassActivity a = weakThis.get();
                if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                    a.runOnUiThread(a::resetSpeakButton);
                }
                return;
            }

            String result;
            try {
                mTessLock.lockInterruptibly();
            } catch (InterruptedException e) {
                enhanced.recycle();
                MagnifierGlassActivity a = weakThis.get();
                if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                    a.runOnUiThread(a::resetSpeakButton);
                }
                return;
            }
            try {
                if (mTess == null) {
                    enhanced.recycle();
                    MagnifierGlassActivity a = weakThis.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        a.runOnUiThread(a::resetSpeakButton);
                    }
                    return;
                }
                try {
                    result = ocrWithIterator(mTess, enhanced, TessBaseAPI.PageSegMode.PSM_AUTO);
                    if (result != null && result.isEmpty()) {
                        result = ocrWithIterator(mTess, enhanced, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OCR failed", e);
                    result = null;
                } finally {
                    enhanced.recycle();
                }
            } finally {
                mTessLock.unlock();
                // If onDestroy timed out waiting for us, clean up mTess now
                if (mShuttingDown && mTessLock.tryLock()) {
                    try {
                        if (mTess != null) { mTess.recycle(); mTess = null; }
                    } finally {
                        mTessLock.unlock();
                    }
                }
            }
            final String text = result;
            MagnifierGlassActivity activity = weakThis.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                MagnifierGlassActivity a = weakThis.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                if (text == null) {
                    Toast.makeText(a, a.getLocalizedString(R.string.ocr_failed), Toast.LENGTH_SHORT).show();
                    a.speakStatus(a.getLocalizedString(R.string.ocr_failed), "ocr");
                } else if (text.isEmpty() || !isReadableText(text)) {
                    Toast.makeText(a, a.getLocalizedString(R.string.ocr_no_text), Toast.LENGTH_SHORT).show();
                    a.speakStatus(a.getLocalizedString(R.string.ocr_no_text), "ocr");
                } else {
                    a.setTtsLanguageWithFallback(menuLocale);
                    a.mLastOcrText = text;
                    // QUEUE_FLUSH: interrupt any "please wait" status still playing
                    a.mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ocr");
                }
                a.resetSpeakButton();
            });
        }, "ocr");
        mOcrThread.start();
    }

    /**
     * Run OCR with the given page segmentation mode and return text in reading order.
     * Uses ResultIterator to walk blocks and paragraphs.
     */
    @VisibleForTesting
    static String ocrWithIterator(TessBaseAPI tess, Bitmap bmp, int psm) {
        tess.setPageSegMode(psm);
        tess.setImage(bmp);
        tess.getUTF8Text(); // trigger recognition
        ResultIterator ri = tess.getResultIterator();
        if (ri == null) return null;
        StringBuilder sb = new StringBuilder();
        ri.begin();
        do {
            if (ri.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) {
                if (sb.length() > 0) {
                    char last = sb.charAt(sb.length() - 1);
                    if (last != '.' && last != '!' && last != '?') sb.append('.');
                    sb.append('\n');
                }
            } else if (ri.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)) {
                if (sb.length() > 0) {
                    char last = sb.charAt(sb.length() - 1);
                    if (last != '.' && last != '!' && last != '?') sb.append('.');
                    sb.append('\n');
                }
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

    /**
     * Returns true if the OCR result looks like real text rather than noise.
     * Checks: (1) at least half the code points are letters/digits/spaces,
     * (2) at least one word has 2+ letters, (3) not too short.
     * Uses code points to correctly handle combining characters and supplementary planes.
     */
    static boolean isReadableText(String text) {
        if (text.length() < 3) return false;
        int readable = 0;
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            total++;
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) readable++;
            i += Character.charCount(cp);
        }
        if (readable * 2 < total) return false;
        // Require at least one word with 2+ consecutive letters
        int consecutive = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                consecutive++;
                if (consecutive >= 2) return true;
            } else {
                consecutive = 0;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private void resetSpeakButton() {
        mOcrInProgress = false;
        mSpeakButton.setAlpha(1f);
        mSpeakButton.setEnabled(true);
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
            String timeStamp = new SimpleDateFormat(SCREENSHOT_DATE_PATTERN, Locale.US).format(new Date());
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
        if (mLastOcrText != null) {
            // Truncate to avoid TransactionTooLargeException on huge OCR results
            String text = mLastOcrText.length() > 10000
                    ? mLastOcrText.substring(0, 10000) : mLastOcrText;
            outState.putString("lastOcrText", text);
        }
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
