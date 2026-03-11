package com.magnifierglass;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.chrisbanes.photoview.PhotoView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
        int[] ids = {R.id.button_exit, R.id.zoom_panel, R.id.button_bar, R.id.button_pause};
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

    private void cameraPreviewIsPaused(Button playOrPauseButton) {
        playOrPauseButton.setText("▶");
        mMagnifierViewTouchArea.setVisibility(View.INVISIBLE);
        zoomPanelVisibility = mZoomPanel.getVisibility();
        mZoomPanel.setVisibility(View.INVISIBLE);
        mPhotoButton.setVisibility(View.VISIBLE);
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
                    if (d == null) return;
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
    private Animation animScale;
    private ScaleGestureDetector mScaleDetector;

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
            // Reset to active state; surface lifecycle will restart the camera
            if (mFrozenBitmap != null && !mFrozenBitmap.isRecycled()) {
                mFrozenBitmap.recycle();
            }
            mFrozenBitmap = null;
            isPreviewActive = true;
            cameraPreviewIsActive(mPauseButton);
        } else {
            mPhotoView.setAlpha(0f);
        }
    }

    @Override
    protected void onDestroy() {
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

        if (!isPreviewActive && mPhotoView != null) {
            unfreezePreview();
        }

        Log.d(TAG, "onResume called!");
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
