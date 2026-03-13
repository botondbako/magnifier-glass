# Camera2 API Migration Analysis

## Why migrate

The current app freezes preview callback frames (NV21) which are low-resolution and minimally processed by the ISP. Photo apps use hardware capture pipelines that produce much sharper images. Camera2 API enables burst capture of full-resolution, ISP-processed frames — the foundation of HDR+ style multi-frame stacking.

A `camera.takePicture()` approach with the legacy API was attempted but the full-res JPEG decoding causes OOM on the BV8000Pro (~190MB heap, Android 7.0). Camera2 gives more control over resolution and memory via `ImageReader`.

## Scope

`MagnifierGlassSurface.java` is 1139 lines with 116 references to `mCamera`. It's the only file using the legacy Camera API. Everything else (Activity, filters, ImageStabilizer, BitmapCreateThread) is independent.

## API mapping

| Legacy Camera | Camera2 |
|---|---|
| `Camera.open(id)` | `CameraManager.openCamera()` + async `StateCallback` |
| `Camera.Parameters` | `CaptureRequest.Builder` + `CameraCharacteristics` |
| `setPreviewDisplay(holder)` | `createCaptureSession()` with `Surface` targets |
| `PreviewCallback` (NV21 bytes) | `ImageReader` (YUV_420_888) |
| `parameters.setZoom(level)` | `SCALER_CROP_REGION` (crop rect) |
| `parameters.setFlashMode(TORCH)` | `FLASH_MODE_TORCH` on `CaptureRequest` |
| `FOCUS_MODE_CONTINUOUS_PICTURE` | `CONTROL_AF_MODE_CONTINUOUS_PICTURE` |
| `autoFocus(callback)` | `CONTROL_AF_TRIGGER_START` + `CaptureCallback` |
| `stopPreview()` + grab frames | `captureBurst()` → `ImageReader` collects N full-res frames |

## Key challenges

1. **Async everything** — Camera2 open, session creation, and capture are all async with callbacks. The current code is mostly synchronous.

2. **YUV format change** — Camera2 uses `YUV_420_888` (planar), not NV21 (semi-planar). The native `yuv-decoder.c` and `ImageStabilizer` expect NV21. Either convert YUV_420_888→NV21, or update those to handle the new format.

3. **Zoom model** — Legacy uses integer zoom levels with `getZoomRatios()`. Camera2 uses `SCALER_CROP_REGION` (a crop rectangle on the sensor). Need to translate the current zoom percentage UI to crop rects.

4. **Burst capture for freeze** — The payoff. Instead of grabbing preview frames:
   - Create an `ImageReader` for `JPEG` or `YUV_420_888` at full sensor resolution
   - On pause: `session.captureBurst(requests, callback, handler)` fires N full-res captures
   - Collect them, align+merge with `ImageStabilizer`, display result
   - Each frame gets full ISP processing (NR, sharpening, etc.)

5. **Memory** — Full-res YUV frames are large. On the BV8000Pro with ~190MB heap, burst of 3-5 frames at a moderate resolution (not max sensor) would be practical.

## Migration plan

### Phase 1: Camera2 preview

Replace camera open, preview, zoom, flash, autofocus with Camera2 equivalents. Keep using `ImageReader` + `YUV_420_888` for preview frames, convert to NV21 for the existing bitmap pipeline. Everything else stays the same.

Estimated: ~400-500 lines rewritten.

### Phase 2: Burst capture on freeze

Replace `freezePreview()` with `captureBurst()`. Collect full-res frames from `ImageReader`, run `ImageStabilizer`, display result. This is where the quality win happens.

Estimated: ~100-150 lines.

### Phase 3: Optimize

Update `ImageStabilizer` and native decoder to work with YUV_420_888 directly (skip NV21 conversion). Tune burst count and resolution for the device's memory.

## Feature: Text-to-Speech (Talkback)

When the user freezes the image, run OCR on the captured frame and read the detected text aloud.

### Components needed

1. **OCR** — Google ML Kit Text Recognition (`com.google.mlkit:text-recognition`) runs on-device, supports Latin, Chinese, Japanese, Korean, Devanagari scripts. Returns detected text + language hints per block.

2. **Text-to-Speech** — Android's built-in `android.speech.tts.TextToSpeech`. No extra dependencies. Supports most languages out of the box.

3. **Language selection** — Two options:
   - **Auto-detect**: ML Kit returns language tags per text block → pass to TTS `setLanguage()`
   - **Menu language**: Use the app's current locale preference (from `LocaleHelper`) → always read in that language regardless of detected script
   - Could offer both as a setting: "Read in detected language" vs "Read in app language"

### Integration points

- Trigger: after `freezePreview()` produces the frozen bitmap
- Feed the bitmap to ML Kit `TextRecognizer.process(InputImage)`
- Concatenate recognized text blocks (top-to-bottom, left-to-right)
- Pass to `TextToSpeech.speak()`
- UI: a speaker button (visible when paused, next to the screenshot button) to trigger/stop reading
- Stop speech on `unfreezePreview()` or app pause

### Dependencies

```groovy
implementation 'com.google.mlkit:text-recognition:16.0.1'
// Optional: additional script libraries
implementation 'com.google.mlkit:text-recognition-chinese:16.0.1'
implementation 'com.google.mlkit:text-recognition-devanagari:16.0.1'
```

No dependency needed for TTS — it's part of the Android framework.

### Considerations

- ML Kit models are downloaded on first use (~20MB per script). The Latin model is bundled by default if using the bundled variant.
- OCR accuracy depends heavily on image quality — this feature benefits directly from the Camera2 burst capture (sharper frozen image = better OCR).
- TTS engine availability varies by device/language. Need graceful fallback if a language isn't supported.
- Accessibility: this feature makes the app usable for blind/low-vision users who can't read even magnified text.

### Effort estimate

All tools are free, on-device, no API keys or usage limits.

| Task | Effort |
|---|---|
| OCR integration (ML Kit dependency, feed bitmap, collect results) | 2-3 hours |
| TTS integration (init, set language, speak/stop) | 1-2 hours |
| UI (speaker button in paused state, progress indicator) | 1-2 hours |
| Language logic (auto-detect vs app language setting) | 1 hour |
| Edge cases & polish (TTS unavailable, no text, long text, stop/restart) | 1-2 hours |
| **Total** | **1-2 days** |

Cost: $0. ML Kit on-device and Android TTS are completely free. Latin script model adds ~3MB to APK (or ~20MB downloaded on first use with thin variant). No cloud calls, no quotas.

This feature is independent of the Camera2 migration — it only needs the frozen bitmap which already exists. Camera2 would improve OCR accuracy later via sharper captures.

## Other improvement proposals

### High impact, low effort

**Gesture shortcuts** — Double-tap to freeze/unfreeze, long-press to toggle torch, swipe left/right to cycle color modes. Important for users who can't easily see small buttons. Effort: ~half day. Uses existing `GestureDetector` / `OnTouchListener`.

**Auto-brightness + torch** — Detect low-light from preview frame luminance average. Automatically boost screen brightness and enable torch. Currently torch is manual only. Effort: ~2-3 hours. Luminance check is a few lines in `onPreviewFrame`.

**Save/recall presets** — Users with specific conditions always use the same zoom + color mode. Save a preset to SharedPreferences that loads on startup. Effort: ~half day. Mostly UI work (settings screen entry).

### Medium impact, medium effort

**Edge enhancement on live preview** — Real-time unsharp mask or edge-boost filter applied to the preview. Makes text edges crisper before freezing. Could be an additional color mode. Effort: ~1 day. Needs a new `CameraColorFilter` implementation using `ColorMatrix` convolution or a custom shader.

**Quick Settings tile** — Android Quick Settings tile to launch the magnifier instantly. Finding and opening the app is itself a challenge for low-vision users. Effort: ~half day. Subclass `TileService` (API 24+).

**Picture-in-picture mode** — Android PiP so the magnifier stays visible as a floating window while using other apps. Effort: ~1 day. Requires `Activity.enterPictureInPictureMode()` (API 26+), layout adjustments for small window.

### Lower priority

**Distance / ruler overlay** — Overlay a grid or ruler when zoomed in. Useful for reading small measurements, pill markings. Effort: ~1 day. Draw overlay in `onDraw()` scaled to real-world units using camera FOV + zoom level.

**Wear OS companion** — Control the phone magnifier from a smartwatch (freeze, zoom, torch) or use watch camera as secondary magnifier. Effort: ~3-5 days. Requires Wear OS module + Data Layer API.

## Implementation plan

Tests are written before or alongside each feature (TDD where practical). Estimated total: ~4-5 weeks.

### Step 1: Gesture shortcuts (~2 days)

1. Write unit tests for gesture mapping logic (double-tap → freeze, long-press → torch, swipe → color mode cycle). Test edge cases: gesture during frozen state, gesture while camera is closed.
2. Extract gesture handling into a testable `GestureHandler` class.
3. Implement `GestureDetector` / `OnTouchListener` in `MagnifierGlassSurface`, delegate to `GestureHandler`.
4. Verify no regression with existing pinch-to-zoom touch handling.

### Step 2: Auto-brightness + torch (~1.5 days)

1. Write unit tests for luminance calculation from NV21 Y-plane (average brightness, threshold logic, hysteresis to avoid flickering).
2. Implement `LuminanceAnalyzer` utility class with tested threshold logic.
3. Hook into `onPreviewFrame` — sample every Nth frame, trigger torch + screen brightness when below threshold.
4. Add setting to enable/disable auto-brightness.

### Step 3: Save/recall presets (~1.5 days)

1. Write unit tests for preset model (serialize/deserialize zoom level, color mode index, torch state to SharedPreferences).
2. Implement `PresetManager` class.
3. Add UI in settings: save current state as default, reset to factory defaults.
4. Load preset on app startup in `enableCamera()`.

### Step 4: Text-to-Speech / OCR (~3 days)

1. Write unit tests for text block ordering logic (sort detected blocks top-to-bottom, left-to-right into readable sequence).
2. Write unit tests for language selection logic (auto-detect vs app locale, fallback when TTS doesn't support detected language).
3. Add ML Kit dependency, implement `TextRecognitionHelper` — feed bitmap, return ordered text + detected language.
4. Implement TTS wrapper — init, speak, stop, language switching. Test with mock TTS engine.
5. Add speaker button to paused-state UI, wire up: freeze → OCR → TTS. Stop on unfreeze.
6. Add setting: language mode (auto-detect / app language).

### Step 5: Camera2 migration — Phase 1: Preview (~5-6 days)

1. Write unit tests for Camera2 helper utilities:
   - Zoom crop rect calculation from percentage (test boundary values, aspect ratio preservation).
   - YUV_420_888 → NV21 conversion (test with known pixel values, compare output to expected NV21 layout).
   - Preview size selection logic (test with various supported size lists).
   - Display orientation calculation (reuse existing `computeDisplayOrientation` tests).
2. Implement `Camera2Helper` class with tested utilities.
3. Replace `getCameraInstance()` with `CameraManager.openCamera()` + `StateCallback`.
4. Replace preview setup: `createCaptureSession()` with `SurfaceHolder` + `ImageReader` targets.
5. Replace `PreviewCallback` with `ImageReader.OnImageAvailableListener`, convert YUV_420_888 → NV21 for existing bitmap pipeline.
6. Replace zoom: `SCALER_CROP_REGION` using tested crop rect calculation.
7. Replace flash: `FLASH_MODE_TORCH` on `CaptureRequest`.
8. Replace autofocus: `CONTROL_AF_TRIGGER` + `CaptureCallback`.
9. Integration test on device — verify preview, zoom, flash, focus, color modes all work.

### Step 6: Camera2 migration — Phase 2: Burst capture (~3-4 days)

1. Write unit tests for burst capture orchestration (frame collection, timeout handling, partial burst fallback).
2. Update `ImageStabilizer` tests for higher-resolution input frames.
3. Implement burst `ImageReader` at capture resolution (moderate, not max — tuned to device memory).
4. Replace `freezePreview()`: trigger AF lock → `captureBurst()` → collect N frames from `ImageReader` → `ImageStabilizer.stabilize()` → display.
5. Test memory usage on BV8000Pro — tune frame count and resolution to stay within heap.

### Step 7: Camera2 migration — Phase 3: Optimize (~2 days)

1. Update native `yuv-decoder.c` to accept YUV_420_888 planes directly (skip NV21 conversion). Write native tests.
2. Update `ImageStabilizer` to work with YUV_420_888 directly. Update existing stabilizer tests.
3. Profile and benchmark: measure freeze-to-display latency, memory peak, preview FPS.

### Step 8: Quick Settings tile (~1 day)

1. Implement `TileService` subclass (API 24+).
2. Test tile state toggling (active/inactive icon).
3. Launch `MagnifierGlassActivity` from tile tap.

### Step 9: Picture-in-picture mode (~1.5 days)

1. Write tests for PiP layout adjustments (button visibility, aspect ratio).
2. Add `supportsPictureInPicture` to manifest, implement `enterPictureInPictureMode()` (API 26+).
3. Handle `onPictureInPictureModeChanged` — hide buttons, adjust layout.
4. Add PiP trigger button or auto-enter on home press.

### Step 10: Edge enhancement filter (~1.5 days)

1. Write unit tests for edge enhancement matrix (apply to known pixel values, verify sharpening effect without clipping).
2. Implement as a new `CameraColorFilter` subclass using `ColorMatrix`.
3. Add to color mode cycle list.

### Summary

| Step | Feature | Effort | Cumulative |
|---|---|---|---|
| 1 | Gesture shortcuts | 2 days | 2 days |
| 2 | Auto-brightness | 1.5 days | 3.5 days |
| 3 | Presets | 1.5 days | 5 days |
| 4 | OCR + TTS | 3 days | 8 days |
| 5 | Camera2 preview | 5-6 days | ~14 days |
| 6 | Camera2 burst capture | 3-4 days | ~18 days |
| 7 | Camera2 optimize | 2 days | ~20 days |
| 8 | Quick Settings tile | 1 day | ~21 days |
| 9 | Picture-in-picture | 1.5 days | ~22.5 days |
| 10 | Edge enhancement | 1.5 days | ~24 days |
