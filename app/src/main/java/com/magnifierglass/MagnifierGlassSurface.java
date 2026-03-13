package com.magnifierglass;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.media.MediaActionSound;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.preference.PreferenceManager;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.magnifierglass.filters.BlackWhiteColorFilter;
import com.magnifierglass.filters.BlueYellowColorFilter;
import com.magnifierglass.filters.CameraColorFilter;
import com.magnifierglass.filters.NoColorFilter;
import com.magnifierglass.filters.WhiteBlackColorFilter;
import com.magnifierglass.filters.YellowBlueColorFilter;
import com.magnifierglass.threads.BitmapCreateThread;


public class MagnifierGlassSurface extends SurfaceView implements SurfaceHolder.Callback, BitmapRenderer {

    /**
     * The debug Tag identifier for the whole class.
     */
    private static final String TAG = "MagnifierGlassSurface";

    /**
     * The JPEG quality used when saving screenshots.
     */
    public static final int JPEG_QUALITY = 90;

    /**
     * Camera state: Device is closed.
     */
    public static final int STATE_CLOSED = 0;

    /**
     * Camera state: Device is opened, but is not capturing.
     */
    public static final int STATE_OPENED = 1;

    /**
     * Camera state: Showing camera preview.
     */
    public static final int STATE_PREVIEW = 2;

    /**
     * Max initial width for the camera preview to avoid performance and ram/cache issues.
     * Afterwards the preview width can be selected from the available sizes in the settings activity.
     */
    private static final int MAX_INITIAL_PREVIEW_RESOLUTION_WIDTH = 1280;
    /**
     * Number of recent preview frames to retain for multi-frame image stabilization.
     * A larger buffer gives more candidates for sharpest-frame selection and
     * more data for aligned averaging, at the cost of memory (~1.4 MB per frame
     * at 1280×720 NV21). 8 frames ≈ 11 MB.
     */
    private static final int FRAME_BUFFER_SIZE = 8;
    private final Activity mActivity;
    private final SharedPreferences mSharedPreferences;

    private MediaActionSound mSound;

    /**
     * Weak singleton reference. Only one MagnifierGlassSurface exists at a time
     * (created in Activity.onCreate). SettingsActivity reads it via getInstance().
     */
    private static volatile WeakReference<MagnifierGlassSurface> mInstanceRef;

    private CharSequence[] availablePreviewWidths;

    /** Thread pool for bitmap creation to avoid creating a new Thread per frame. */
    private volatile ExecutorService mBitmapExecutor = Executors.newFixedThreadPool(
            BitmapCreateThread.MAX_INSTANCES);

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // SurfaceView.onLayout is intentionally not called — we override the
        // child layout entirely in keepCameraAspectRatioInView.
        if (changed) {
            keepCameraAspectRatioInView(this, left, top, right, bottom);
        }
    }

    /**
     * Adjusts layout to match the camera preview aspect ratio.
     * Note: this only affects the camera preview with no filter.
     *
     * @param child the view whose layout should be adjusted
     */
    private void keepCameraAspectRatioInView(View child, int left, int top, int right, int bottom) {

        final int width = right - left;
        final int height = bottom - top;

        int previewWidth = mCameraPreviewWidth;
        if(previewWidth == 0) previewWidth = width;
        int previewHeight = mCameraPreviewHeight;
        if(previewHeight == 0) previewHeight = height;

        // Swap dimensions when display orientation rotates the preview by 90°/270°
        int orientation = computeDisplayOrientation(mActivity);
        if (orientation == 90 || orientation == 270) {
            int tmp = previewWidth;
            previewWidth = previewHeight;
            previewHeight = tmp;
        }

        // Center the child SurfaceView within the parent.
        if (width * previewHeight < height * previewWidth) {

            // abort if height is 0
            if(previewHeight == 0) return;

            final int scaledChildWidth = previewWidth * height / previewHeight;

            left = (width - scaledChildWidth) / 2;
            top = 0;
            right = (width + scaledChildWidth) / 2;
            bottom = height;

            child.layout(left, top, right, bottom);
        } else {

            // abort if width is 0
            if(previewWidth == 0) return;

            final int scaledChildHeight = previewHeight * width / previewWidth;

            left = 0;
            top = (height - scaledChildHeight) / 2;
            right = width;
            bottom = (height + scaledChildHeight) / 2;

            child.layout(left, top, right, bottom);
        }

        // re-scale matrix with given values
        final float tmpScaleX = (right - left) / (float) previewWidth;
        final float tmpScaleY = (bottom - top) / (float) previewHeight;

        if(tmpScaleX != scaleX || tmpScaleY != scaleY) {
            scaleX = tmpScaleX;
            scaleY = tmpScaleY;
            if(scaleMatrix == null) scaleMatrix = new Matrix();
            scaleMatrix.setScale(scaleX, scaleY, 0, 0);
        }

        updateFilterDrawMatrix();
    }

    private SurfaceHolder mHolder;

    /**
     * The camera device reference.
     * An instance will be created if the surface is created.
     * We'll close the camera reference if the surface gets destroyed.
     */
    private Camera mCamera;

    /**
     * defines the current zoom level of the camera.
     */
    private int mCameraCurrentZoomLevel;

    /**
     * if true the flashlight should be on.
     */
    private boolean mCameraFlashMode;
    /**
     * stores the value of the devices max zoom level of the camera.
     */
    private int mCameraMaxZoomLevel = 1;

    /** Camera zoom ratios (percentage values, e.g. 100 = 1x, 400 = 4x). */
    private List<Integer> mCameraZoomRatios;

    private int mPendingZoomPercent = -1;

    /**
     * the width of the display.
     */
    private int mDisplayWidth;

    /**
     * the height of the display
     */
    private int mDisplayHeight;

    /**
     * the maximum possible width of the camera preview that we'll use.
     */
    private int mCameraPreviewWidth;

    /**
     * the maximum possible height of the camera preview that we'll use.
     */
    private int mCameraPreviewHeight;

    /**
     * is the current camera a selfie camera, i.e. is it mirrored?
     */
    private boolean mCameraIsFrontFacing;

    /** Camera sensor orientation (hardware constant, -1 until first camera open). */
    private int mCameraSensorOrientation = -1;

    /**
     * the display orientation applied to the camera preview.
     */
    private int mDisplayOrientation;

    /**
     * the paint object which has the colorFilter assigned. We will use it
     * to apply the different color modes to the rendered preview bitmap.
     */
    private Paint mColorFilterPaint;

    /**
     * the current state of the camera device.
     * i.e. open, closed or preview.
     */
    private volatile int mState;


    /**
     * The current filter for the camera.
     * The filter is an interface which takes some bytes as the param and
     * converts the bits to make several different color effects.
     */
    private List<CameraColorFilter> mCameraColorFilterList;
    private int mCurrentColorFilterIndex;

    /**
     * const for the blue yellow color filter {@link BlueYellowColorFilter}
     */
    public final static CameraColorFilter BLUE_YELLOW_COLOR_FILTER = new BlueYellowColorFilter();

    /**
     * const for the yellow blue color filter {@link YellowBlueColorFilter}
     */
    public final static CameraColorFilter YELLOW_BLUE_COLOR_FILTER = new YellowBlueColorFilter();

    /**
     * const for the b/w color filter {@link BlackWhiteColorFilter}
     */
    public final static CameraColorFilter BLACK_WHITE_COLOR_FILTER = new BlackWhiteColorFilter();

    /**
     * const for the w/b color filter {@link WhiteBlackColorFilter}
     */
    public final static CameraColorFilter WHITE_BLACK_COLOR_FILTER = new WhiteBlackColorFilter();

    /**
     * const for the no color filter {@link NoColorFilter}
     */
    public final static CameraColorFilter NO_FILTER = new NoColorFilter();

    /**
     * stores the YUV image (format NV21) when onPreviewFrame was called
     */
    private byte[] mCameraPreviewBufferData;

    /**
     * Circular buffer of recent NV21 preview frames for multi-frame stabilization.
     * Filled continuously in {@link #mCameraPreviewCallbackHandler} and consumed
     * by {@link ImageStabilizer#stabilize} when the user pauses the preview.
     */
    private byte[][] mFrameBuffer;
    /** Write index into {@link #mFrameBuffer} (wraps at FRAME_BUFFER_SIZE). */
    private int mFrameBufferIndex;
    /** Number of valid frames currently stored (up to FRAME_BUFFER_SIZE). */
    private int mFrameBufferCount;
    /** Lock guarding mFrameBuffer, mFrameBufferIndex, mFrameBufferCount and mCameraPreviewBufferData. */
    private final Object mFrameLock = new Object();

    /**
     * after the onPreviewFrame was called we'll generate a
     * bitmap for usage in onDraw.
     */
    private volatile Bitmap mCameraPreviewBitmapBuffer;


    /**
     * Callback for camera preview frames.
     * Each frame is copied into a pre-allocated slot in the circular
     * {@link #mFrameBuffer} for stabilization. A separate clone is kept
     * in {@link #mCameraPreviewBufferData} for live bitmap rendering
     * (since the buffer thread may read it while the next callback fires).
     */
    private final Camera.PreviewCallback mCameraPreviewCallbackHandler = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, Camera camera) {
            synchronized (mFrameLock) {
                if (mFrameBuffer != null) {
                    byte[] slot = mFrameBuffer[mFrameBufferIndex];
                    if (slot == null || slot.length != data.length) {
                        slot = new byte[data.length];
                        mFrameBuffer[mFrameBufferIndex] = slot;
                    }
                    System.arraycopy(data, 0, slot, 0, data.length);
                    mFrameBufferIndex = (mFrameBufferIndex + 1) % FRAME_BUFFER_SIZE;
                    if (mFrameBufferCount < FRAME_BUFFER_SIZE) mFrameBufferCount++;
                }
                // Clone for live rendering — the BitmapCreateThread reads this
                // on a background thread, so it must not alias a reusable slot.
                mCameraPreviewBufferData = data.clone();
            }
            if (!hasActiveFilterEnabled()) {
                invalidate();
                return;
            }

            runBitmapCreateThread(false);
        }
    };
    /**
     * reference to the zoom panel.
     * <p>
     * We hide the zoom panel if zoom is not supported
     * by the device camera.
     */
    private View zoomPanel;
    /**
     * reference to the flash button.
     * <p>
     * We hide the flash button if flashlight isn't supported.
     */
    private View flashButtonView;

    /** The currently active camera ID. */
    private int mCameraId;

    /**
     * Image Scaling costs much cpu and memory.
     * We store the values here for use in onDraw:
     */
    private float scaleX;
    private float scaleY;
    private Matrix scaleMatrix;
    /** Combined rotate+mirror+scale matrix for drawing filtered preview in onDraw. */
    private Matrix mFilterDrawMatrix;

    /**
     * @param context activity
     */
    public MagnifierGlassSurface(Context context) {
        super(context);
        mActivity = (Activity) context;
        mInstanceRef = new WeakReference<>(this);
        Log.d(TAG, "MagnifierGlassSurface instantiated");

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mCurrentColorFilterIndex = mSharedPreferences.getInt(getResources().getString(R.string.key_preference_color_mode), 0);

        mColorFilterPaint = new Paint();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = mActivity.getWindowManager().getCurrentWindowMetrics().getBounds();
            mDisplayWidth = bounds.width();
            mDisplayHeight = bounds.height();
        } else {
            Point sizePoint = new Point();
            mActivity.getWindowManager().getDefaultDisplay().getRealSize(sizePoint);
            mDisplayWidth = sizePoint.x;
            mDisplayHeight = sizePoint.y;
        }

        setWillNotDraw(false);

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
    }

    public static MagnifierGlassSurface getInstance() {
        return mInstanceRef != null ? mInstanceRef.get() : null;
    }

    /**
     * Open and return a camera instance, trying the given ID first.
     */
    private Camera getCameraInstance(int cameraId) {
        final int numOfCameras = Camera.getNumberOfCameras();
        Log.d(TAG, "There're " + numOfCameras + " cameras on your device. You want camera " + cameraId);

        for (int id = cameraId; id < numOfCameras; id++) {
            try {
                Camera c = Camera.open(id);
                mCameraId = id;
                return c;
            } catch (Exception e) {
                Log.w(TAG, "Camera " + id + " unavailable", e);
            }
        }
        Log.e(TAG, "No camera could be opened");
        return null;
    }

    /**
     * Return camera with the user's preferred ID (default: 0, back camera).
     */
    private Camera getCameraInstance() {
        return getCameraInstance(getPreferredCameraId());
    }

    public int getPreferredCameraId() {
        return Integer.parseInt(mSharedPreferences.getString(getResources().getString(R.string.key_preference_camera_id), "0"));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "called surfaceCreated, display orientation: " + getResources().getConfiguration().orientation);
    }

    @Override
    // This method is always called at least once, after surfaceCreated.
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        final int orientation = getResources().getConfiguration().orientation;
        Log.d(TAG, "called surfaceChanged, display orientation: " + orientation);
        enableCamera();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "called surfaceDestroyed. Storing settings");

        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(getResources().getString(R.string.key_preference_zoom_level), mCameraCurrentZoomLevel);
        editor.putInt(getResources().getString(R.string.key_preference_color_mode), mCurrentColorFilterIndex);
        editor.apply();

        releaseCamera();
    }

    /**
     * Returns the best camera preview size up to {@link #MAX_INITIAL_PREVIEW_RESOLUTION_WIDTH},
     * or the user's preferred width from settings.
     *
     * @param parameters the camera parameters to receive all supported preview sizes.
     * @return Camera.Size or null if no sizes are available.
     */
    private Camera.Size getBestPreviewSize(Camera.Parameters parameters) {
        Camera.Size result = null;
        final int UNINITIALIZED_WIDTH = -1;
        int preferredPreviewWidth = Integer.parseInt(mSharedPreferences.getString(getResources().getString(R.string.key_preference_preview_resolution), String.valueOf(UNINITIALIZED_WIDTH)));

        List<Camera.Size> size = parameters.getSupportedPreviewSizes();
        Collections.sort(size, new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size lhs, Camera.Size rhs) {
                return Integer.compare(lhs.width, rhs.width);
            }
        });

        if (size.isEmpty()) return null;

        ArrayList<String> availablePreviewWidths = new ArrayList<>();
        for (int i = 0; i < size.size(); i++) {
            Log.d(TAG, "Size: " + size.get(i).width + " * " + size.get(i).height);

            final int currentWidth = size.get(i).width;
            if (i == 0 || currentWidth != size.get(i-1).width )
                availablePreviewWidths.add(String.valueOf(currentWidth));
            if (currentWidth == preferredPreviewWidth) {
                result = size.get(i);
            } else if (preferredPreviewWidth == UNINITIALIZED_WIDTH
                    && currentWidth <= MAX_INITIAL_PREVIEW_RESOLUTION_WIDTH
                    && (result == null || currentWidth > result.width)) {
                result = size.get(i);
            }
        }
        if (result == null)
            result = size.get(size.size() - 1);
        this.availablePreviewWidths = availablePreviewWidths.toArray(new CharSequence[0]);
        Log.d(TAG, "got maximum preview size of " + result.width + "*" + result.height);
        return result;
    }

    /**
     * Opens and enables the camera.
     * Returns immediately if already open or if the camera cannot be acquired.
     */
    private void enableCamera() {
        if (mState != STATE_CLOSED) return;

        if (mCamera == null) {
            mCamera = getCameraInstance();
        }
        if (mCamera == null) return;
        mState = STATE_OPENED;

        Camera.Parameters parameters = mCamera.getParameters();
        if (parameters.isZoomSupported()) {
            mCameraMaxZoomLevel = parameters.getMaxZoom();
            mCameraZoomRatios = parameters.getZoomRatios();
        } else {
            getZoomPanel().setVisibility(View.INVISIBLE);
        }
        Camera.Size size = getBestPreviewSize(parameters);

        if (!getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            getFlashButtonView().setVisibility(View.INVISIBLE);
        }

        int cameraPreviewFormat = parameters.getPreviewFormat();
        if (cameraPreviewFormat != ImageFormat.NV21) parameters.setPreviewFormat(ImageFormat.NV21);

        // no sizes found? something went wrong
        if (size == null) {
            releaseCamera();
            return;
        }

        mCameraPreviewWidth = size.width;
        mCameraPreviewHeight = size.height;
        parameters.setPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);

        if (mCameraPreviewBitmapBuffer != null && !mCameraPreviewBitmapBuffer.isRecycled()) {
            mCameraPreviewBitmapBuffer.recycle();
        }
        mCameraPreviewBitmapBuffer = Bitmap.createBitmap(mCameraPreviewWidth, mCameraPreviewHeight, Bitmap.Config.ARGB_8888);

        // Fallback: create scaleMatrix if onLayout hasn't been called yet
        if(scaleMatrix == null) {
            scaleX = mDisplayWidth / (float) mCameraPreviewWidth;
            scaleY = mDisplayHeight / (float) mCameraPreviewHeight;
            Log.w(TAG, "Matrix scaled created before onLayout was called");
            scaleMatrix = new Matrix();
            scaleMatrix.setScale(scaleX, scaleY, 0, 0);
        }

        parameters.setRecordingHint(true);

        // enable continuous autofocus by default
        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        }

        setCameraDisplayAndFaceOrientation(mActivity);

        mCamera.setParameters(parameters);

        // pre-define some variables for image processing.
        mCameraPreviewBufferData = new byte[mCameraPreviewWidth * mCameraPreviewHeight * 3 / 2];
        mFrameBuffer = new byte[FRAME_BUFFER_SIZE][];
        mFrameBufferIndex = 0;
        mFrameBufferCount = 0;

        // The Surface has been created, now tell the
        // camera where to draw the preview.
        try {
            mCamera.setPreviewDisplay(mHolder);
        } catch (IOException e) {
            Log.e(TAG, "setPreviewDisplay failed", e);
            releaseCamera();
            return;
        }
        mCamera.setPreviewCallback(mCameraPreviewCallbackHandler);

        try {
            mCamera.startPreview();
        } catch (RuntimeException e) {
            Log.e(TAG, "startPreview failed", e);
            releaseCamera();
            return;
        }

        mState = STATE_PREVIEW;

        // cancelAutoFocus is required on some devices to kick-start FOCUS_MODE_CONTINUOUS_PICTURE
        try { mCamera.cancelAutoFocus(); } catch (RuntimeException ignored) {}

        if (mPendingZoomPercent >= 0) {
            setZoomLevelPercent(mPendingZoomPercent);
        } else if (mCameraCurrentZoomLevel > 0) {
            setCameraZoomLevel(mCameraCurrentZoomLevel);
        }
        if (mCurrentColorFilterIndex > 0) {
            mCurrentColorFilterIndex--;
            // decrease index because
            // the toggle causes the increment
            toggleColorMode();
        }

        if (mSharedPreferences.getBoolean(getResources().getString(R.string.key_preference_auto_torch), false)) {
            mCameraFlashMode = true;
            turnFlashlightOn();
        }

        Log.d(TAG, "Thread done. Camera successfully started");
    }

    private void setCameraDisplayAndFaceOrientation(Activity activity) {
        if (mCamera == null) return;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);
        mCameraIsFrontFacing = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
        mCameraSensorOrientation = info.orientation;

        mDisplayOrientation = computeDisplayOrientation(activity);
        mCamera.setDisplayOrientation(mDisplayOrientation);
        updateFilterDrawMatrix();
    }

    /**
     * Compute the display orientation from the current window rotation and
     * the stored camera sensor orientation.  Safe to call even when the camera
     * is closed (returns 0 if sensor orientation is unknown).
     */
    private int computeDisplayOrientation(Activity activity) {
        if (mCameraSensorOrientation < 0) return 0;

        int rotation;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rotation = activity.getDisplay().getRotation();
        } else {
            rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        }
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:   degrees = 0;   break;
            case Surface.ROTATION_90:  degrees = 90;  break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        if (mCameraIsFrontFacing) {
            return (360 - (mCameraSensorOrientation + degrees) % 360) % 360;
        } else {
            return (mCameraSensorOrientation - degrees + 360) % 360;
        }
    }

    /**
     * Recompute the combined matrix used by onDraw for filtered preview frames.
     * Raw callback data is in sensor orientation; this matrix applies rotation,
     * front-camera mirror, and scaling to map it onto the view.
     */
    private void updateFilterDrawMatrix() {
        if (mCameraPreviewWidth == 0 || mCameraPreviewHeight == 0) return;
        if (getWidth() == 0 || getHeight() == 0) return;
        int pw = mCameraPreviewWidth;
        int ph = mCameraPreviewHeight;
        mFilterDrawMatrix = new Matrix();
        mFilterDrawMatrix.setRotate(mDisplayOrientation, pw / 2f, ph / 2f);
        if (mCameraIsFrontFacing) {
            mFilterDrawMatrix.postScale(-1, 1, pw / 2f, ph / 2f);
        }
        RectF bounds = new RectF(0, 0, pw, ph);
        mFilterDrawMatrix.mapRect(bounds);
        mFilterDrawMatrix.postTranslate(-bounds.left, -bounds.top);
        mFilterDrawMatrix.postScale(getWidth() / bounds.width(), getHeight() / bounds.height());
    }

    public void releaseCamera() {
        Log.d(TAG, "releasing the camera.");

        if (mCamera != null) {
            try {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);
                mCamera.release();
            } catch (Exception e) {
                Log.e(TAG, "releaseCamera failed", e);
            }
            mCamera = null;
        }
        mBitmapExecutor.shutdownNow();
        mBitmapExecutor = Executors.newFixedThreadPool(BitmapCreateThread.MAX_INSTANCES);
        if (mCameraPreviewBitmapBuffer != null && !mCameraPreviewBitmapBuffer.isRecycled()) {
            mCameraPreviewBitmapBuffer.recycle();
        }
        mCameraPreviewBitmapBuffer = null;
        mState = STATE_CLOSED;
        if (mSound != null) {
            mSound.release();
            mSound = null;
        }
        Log.d(TAG, "camera released.");
    }

    private MediaActionSound getMediaActionSound() {
        if (mSound == null) {
            mSound = new MediaActionSound();
            mSound.load(MediaActionSound.FOCUS_COMPLETE);
            mSound.load(MediaActionSound.SHUTTER_CLICK);
        }
        return mSound;
    }

    private void playActionSound(String prefKey, int soundId) {
        if (mSharedPreferences.getBoolean(prefKey, false)) {
            MediaActionSound player = getMediaActionSound();
            if (player != null) player.play(soundId);
        }
    }

    public void playActionSoundShutter() {
        playActionSound(getResources().getString(R.string.key_preference_shutter_sound),
                MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Restarts continuous autofocus by cancelling and re-engaging.
     */
    public void restartAutoFocus() {
        if (mState != STATE_PREVIEW) return;
        try {
            mCamera.cancelAutoFocus();
        } catch (RuntimeException ex) {
            Log.w(TAG, "autofocus restart failed");
        }
    }

    /**
     * Resumes the camera preview (un-pause).
     */
    public void resumeCameraPreview() {
        if (mState == STATE_PREVIEW) return; // already running (e.g. after rotation)
        if (mCamera == null) {
            mState = STATE_CLOSED;
            enableCamera();
            return;
        }
        try {
            mCamera.setPreviewDisplay(mHolder);
        } catch (IOException e) {
            Log.e(TAG, "setPreviewDisplay failed in resumeCameraPreview", e);
            releaseCamera();
            enableCamera();
            return;
        }
        mState = STATE_PREVIEW;
        mCamera.setPreviewCallback(mCameraPreviewCallbackHandler);
        mCamera.startPreview();
    }

    /**
     * Triggers autofocus, waits for it to lock, lets a few focused frames
     * fill the buffer, then freezes the preview and calls {@code onPaused}.
     * Falls back to an immediate pause if autofocus is unavailable or fails.
     */
    public void pauseWithFocus(final Runnable onPaused) {
        if (mCamera == null || mState != STATE_PREVIEW) return;

        final Runnable freezeAndNotify = new Runnable() {
            private boolean mDone;
            @Override
            public void run() {
                if (mDone) return;
                mDone = true;
                if (mState == STATE_PREVIEW) {
                    freezePreview();
                }
                if (onPaused != null) onPaused.run();
            }
        };

        try {
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    if (mState != STATE_PREVIEW) {
                        if (onPaused != null) post(onPaused);
                        return;
                    }
                    // Clear stale (pre-focus) frames, then let fresh ones accumulate
                    synchronized (mFrameLock) {
                        mFrameBufferCount = 0;
                        mFrameBufferIndex = 0;
                    }
                    postDelayed(freezeAndNotify, 250); // ~8 frames at 30 fps
                }
            });
            // Safety timeout: if autoFocus callback never fires, freeze anyway
            postDelayed(freezeAndNotify, 2000);
        } catch (RuntimeException e) {
            // autofocus not supported or camera in bad state — freeze immediately
            freezePreview();
            if (onPaused != null) onPaused.run();
        }
    }

    /** Stops the preview and runs multi-frame stabilization off the UI thread. */
    private void freezePreview() {
        if (mCamera == null || mState != STATE_PREVIEW) return;
        mState = STATE_OPENED;
        mCamera.setPreviewCallback(null);
        mCamera.stopPreview();

        // Capture frame data under lock, then stabilize on a background thread
        // to avoid blocking the UI (stabilization is O(frames × pixels × search²)).
        final byte[][] frames;
        synchronized (mFrameLock) {
            if (mFrameBufferCount > 0) {
                frames = new byte[mFrameBufferCount][];
                for (int i = 0; i < mFrameBufferCount; i++)
                    frames[i] = getBufferedFrame(i).clone();
            } else {
                frames = null;
            }
        }
        final int w = mCameraPreviewWidth;
        final int h = mCameraPreviewHeight;
        try {
            mBitmapExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (frames != null) {
                        byte[] stabilized = ImageStabilizer.stabilize(frames, w, h);
                        synchronized (mFrameLock) {
                            mCameraPreviewBufferData = stabilized;
                        }
                    }
                    post(new Runnable() {
                        @Override
                        public void run() {
                            runBitmapCreateThread(true);
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private byte[] getBufferedFrame(int index) {
        return mFrameBuffer[(mFrameBufferIndex - mFrameBufferCount + index + FRAME_BUFFER_SIZE) % FRAME_BUFFER_SIZE];
    }

    /** Returns the effective scale factor used to display the preview bitmap. */
    public float getPreviewScale() {
        return scaleX;
    }

    /**
     * Toggles flashlight on and off.
     */
    public void nextFlashlightMode() {
        if (mState != STATE_PREVIEW) return;

        mCameraFlashMode = !mCameraFlashMode;
        if (mCameraFlashMode) {
            turnFlashlightOn();
        } else {
            turnFlashlightOff();
        }
    }

    private void turnFlashlightOff() {
        if (mState != STATE_PREVIEW || !supportsFlashlight()) return;
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        mCamera.setParameters(parameters);
    }

    private void turnFlashlightOn() {
        if (mState != STATE_PREVIEW || !supportsFlashlight()) return;
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        mCamera.setParameters(parameters);
    }


    /**
     * Checks if the current device has a flash that supports torch mode.
     *
     * @return true if flash is supported
     */
    private boolean supportsFlashlight() {
        if (mCamera == null) return false;
        boolean hasFlash = getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);

        if (!hasFlash) {
            return false;
        }

        Camera.Parameters parameters = mCamera.getParameters();
        List<String> supportedFlashModes = parameters.getSupportedFlashModes();
        return supportedFlashModes != null && supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH);

    }

    public void setZoomLevelPercent(int zoomLevelPercent) {
        if (mState != STATE_PREVIEW) {
            mPendingZoomPercent = zoomLevelPercent;
            return;
        }
        mPendingZoomPercent = -1;
        mCameraCurrentZoomLevel = (int) ((double) zoomLevelPercent * mCameraMaxZoomLevel / 100);

        if (mCameraCurrentZoomLevel > mCameraMaxZoomLevel) {
            mCameraCurrentZoomLevel = mCameraMaxZoomLevel;
        }

        setCameraZoomLevel(mCameraCurrentZoomLevel);
    }

    /**
     * Returns the actual zoom factor (e.g. 1.0, 2.5, 4.0) for the current zoom level,
     * or -1 if zoom ratios are unavailable.
     */
    public float getActualZoomFactor() {
        if (mCameraZoomRatios == null || mCameraCurrentZoomLevel >= mCameraZoomRatios.size()) return -1;
        return mCameraZoomRatios.get(mCameraCurrentZoomLevel) / 100f;
    }

    /** Sets the list of color filters to cycle through. */
    public void setCameraColorFilters(List<CameraColorFilter> colorFilters) {
        this.mCameraColorFilterList = colorFilters;
    }

    /**
     * change color modes if the camera preview if supported.
     */
    public void toggleColorMode() {
        if (mState == STATE_CLOSED) return;
        if (mCameraColorFilterList == null) return;

        setColorMode(mCurrentColorFilterIndex + 1);
    }

    public void setColorMode(int index) {
        mCurrentColorFilterIndex = index;
        if (mCurrentColorFilterIndex >= mCameraColorFilterList.size()) {
            mCurrentColorFilterIndex = 0;
        }

        CameraColorFilter currentFilter = mCameraColorFilterList.get(mCurrentColorFilterIndex);
        ColorMatrix colorMatrix = new ColorMatrix();
        currentFilter.filter(colorMatrix);

        ColorMatrixColorFilter colorFilter = new ColorMatrixColorFilter(colorMatrix);
        mColorFilterPaint.setColorFilter(colorFilter);

        if (mState == STATE_OPENED) {
            invalidate();
        }

        updatePhotoViewBitmap();
    }

    private void updatePhotoViewBitmap() {
        if (!(getContext() instanceof MagnifierGlassActivity)) return;
        PhotoView photoView = ((MagnifierGlassActivity) getContext()).getPhotoView();
        if (photoView == null || photoView.getVisibility() != View.VISIBLE) return;
        Bitmap bmp = getBitmap();
        if (bmp != null) {
            Drawable prev = photoView.getDrawable();
            photoView.setImageBitmap(bmp);
            if (prev instanceof BitmapDrawable) {
                final Bitmap old = ((BitmapDrawable) prev).getBitmap();
                if (old != null && old != bmp && !old.isRecycled()) {
                    photoView.post(new Runnable() {
                        @Override
                        public void run() { if (!old.isRecycled()) old.recycle(); }
                    });
                }
            }
        }
    }

    /**
     * Runs a bitmap create thread with the current preview buffer data.
     * The buffer reference is read under {@link #mFrameLock} to avoid racing
     * with the preview callback. Uses a fixed thread pool instead of creating
     * a new Thread per frame.
     */
    private void runBitmapCreateThread(boolean rgb) {
        final byte[] data;
        synchronized (mFrameLock) {
            data = mCameraPreviewBufferData;
        }
        final BitmapCreateThread bitmapCreateThread = BitmapCreateThread.getInstance(
                data,
                MagnifierGlassSurface.this,
                mCameraPreviewWidth,
                mCameraPreviewHeight,
                rgb,
                mCameraIsFrontFacing
        );
        if (bitmapCreateThread == null) return;
        try {
            mBitmapExecutor.execute(bitmapCreateThread);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** Sets the rendered bitmap and triggers a UI redraw. */
    public void renderBitmap(Bitmap bitmap) {
        if (bitmap != null) {
            mCameraPreviewBitmapBuffer = bitmap;
        }

        post(new Runnable() {
            @Override
            public void run() {
                invalidate();

                if(mState != STATE_PREVIEW) {
                    updatePhotoViewBitmap();
                }
            }
        });
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (mState == STATE_CLOSED) return;
        Bitmap buffer = mCameraPreviewBitmapBuffer;
        if (buffer == null || buffer.isRecycled()) return;

        if (mState == STATE_OPENED || (mState == STATE_PREVIEW && hasActiveFilterEnabled())) {
            canvas.setMatrix(mFilterDrawMatrix != null ? mFilterDrawMatrix : scaleMatrix);
            canvas.drawBitmap(buffer, 0, 0, mColorFilterPaint);
        }
    }

    /**
     * determines if a filter is active. A filter is active if it is not "NO_FILTER".
     * Used to save performance while have normal (without color effects) camera preview enabled.
     *
     * @return true if the current color mode is not NO_FILTER
     */
    private boolean hasActiveFilterEnabled() {
        return mCameraColorFilterList != null
                && mCameraColorFilterList.get(mCurrentColorFilterIndex) != NO_FILTER;
    }

    /**
     * sets the camera level to the specified {zoomLevel}.
     * It dependes on a valid {mCamera} object to receive
     * the parameters and set it as well.
     *
     * @param zoomLevel the integer of the new zoomLevel you want to set. All integers above the maximum possible value will be set to maximum.
     */
    private void setCameraZoomLevel(int zoomLevel) {
        Camera.Parameters parameters = mCamera.getParameters();

        if (!parameters.isZoomSupported()) {
            Log.w(TAG, "Zoom is not supported on this device.");
            return;
        }

        if (zoomLevel > mCameraMaxZoomLevel) {
            zoomLevel = mCameraMaxZoomLevel;
        }
        mCameraCurrentZoomLevel = zoomLevel;

        Log.d(TAG, "Current zoom level is " + zoomLevel);

        parameters.setZoom(mCameraCurrentZoomLevel);
        mCamera.setParameters(parameters);
    }

    private View getZoomPanel() {
        return zoomPanel;
    }

    private View getFlashButtonView() {
        return flashButtonView;
    }

    public void setZoomPanel(View zoomPanel) {
        this.zoomPanel = zoomPanel;
    }

    public void setFlashButton(View flashButton) {
        this.flashButtonView = flashButton;
    }

    /**
     * Creates a display-ready bitmap from the current preview buffer.
     *
     * <p>The camera preview callback delivers frames in the sensor's native orientation
     * (typically landscape). {@code Camera.setDisplayOrientation()} corrects this for the
     * live preview at the hardware level, but the raw callback data remains unrotated.
     * This method applies the same rotation ({@link #mDisplayOrientation}) so the frozen
     * bitmap matches what the user saw in the live preview. For front-facing cameras,
     * an additional horizontal mirror is applied. The active color filter is also
     * composited via {@link #mColorFilterPaint}.</p>
     *
     * <p>Bilinear filtering is disabled ({@code filter=false}) because the rotation is
     * always an exact multiple of 90°, so nearest-neighbor preserves pixel sharpness.</p>
     *
     * @return A new Bitmap with color filter, rotation, and mirror applied.
     */
    public Bitmap getBitmap() {
        Bitmap buffer = mCameraPreviewBitmapBuffer;
        if (buffer == null || buffer.isRecycled()) return null;
        Bitmap filtered = Bitmap.createBitmap(buffer.getWidth(), buffer.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(filtered);
        canvas.drawBitmap(buffer, 0, 0, mColorFilterPaint);

        if (mDisplayOrientation != 0 || mCameraIsFrontFacing) {
            Matrix m = new Matrix();
            if (mDisplayOrientation != 0) m.postRotate(mDisplayOrientation);
            if (mCameraIsFrontFacing) m.postScale(-1, 1, filtered.getWidth() / 2f, filtered.getHeight() / 2f);
            Bitmap rotated = Bitmap.createBitmap(filtered, 0, 0, filtered.getWidth(), filtered.getHeight(), m, false);
            filtered.recycle();
            filtered = rotated;
        }

        return filtered;
    }

    public int getCameraPreviewWidth() {
        return mCameraPreviewWidth;
    }

    public CharSequence[] getAvailablePreviewWidths() {
        return availablePreviewWidths;
    }
}
