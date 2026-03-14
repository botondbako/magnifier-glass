# MagnifierGlass

A magnifying glass app for Android, designed for people with low vision.

> Fork of [visor-android](https://github.com/kloener/visor-android) by Christian Illies, licensed under Apache 2.0.

## What it does

MagnifierGlass turns your phone's camera into a handheld electronic magnifier. Point it at text or objects and see them enlarged on screen with enhanced contrast.

Features:
- Live camera preview with pinch-to-zoom (live and frozen image)
- 5 color modes: normal, black/white, white/black, blue/yellow, yellow/blue (long-press resets to normal)
- Zoom in/out buttons with on-screen zoom level indicator
- Volume button zoom — use physical volume keys to zoom in/out without looking at the screen
- LED flashlight toggle for low-light conditions
- Ambient light auto-torch — automatically turns flashlight on/off based on scene brightness
- Multi-frame image stabilization for sharper frozen images
- Continuous autofocus (tap to refocus, double-tap to freeze)
- Double-tap frozen image to unfreeze
- Screenshot capture and share
- Left-handed mode (mirrors button layout)
- Startup preset: saves zoom, color mode, and flash state — auto-saved when settings change
- Configurable default zoom, zoom step size, preview resolution, camera selection
- Flash state and zoom level persist across sessions
- Multi-language support (29 languages)
- Full-screen immersive mode
- **OCR text-to-speech**: freeze the image and press the speak button to have recognized text read aloud
  - Powered by Tesseract OCR (best accuracy models) with English bundled
  - Additional OCR language models downloaded automatically when a non-English menu language is selected (28 languages supported)
  - TTS always uses the menu language — no heuristic detection, predictable pronunciation
  - All OCR status (progress, results, errors, downloads) announced via TTS for eyes-free use
  - Garbage filtering rejects noise/artifacts that aren't real text
  - Reads text blocks in proper reading order (multi-column layouts)
  - Grayscale + contrast preprocessing for improved recognition
  - Long-press speak button to repeat last recognized text without re-running OCR
  - OCR text preserved across screen rotations

### Accessibility

- 48dp touch targets on all buttons (meets WCAG 2.5.8 and Android guidelines)
- Haptic feedback on every button press and long-press
- Vibration feedback when camera is ready (configurable)
- Zoom level announced via text-to-speech after each change
- Volume button zoom for eyes-free operation
- Long-press to exit prevents accidental app closure
- All buttons focusable with content descriptions for TalkBack and switch access
- Freeze/unfreeze state changes announced for screen reader users
- Respects system "Remove animations" setting (reduced motion)
- Configurable zoom step size (10% or 25%) for users with limited motor control

## Known limitations

- Handwritten text and stylized fonts (e.g. 7-segment displays) are poorly recognized — Tesseract is trained on printed text. Google ML Kit would be a better engine for handwriting.
- OCR language model download requires an internet connection on first use per language (~12–15 MB each).
- Android 7.0 devices have outdated root CA certificates; the app bundles the ISRG Root X1 certificate for reliable language data downloads from GitHub.

## Requirements

- Android 7.0+ (API 24)
- Device with a camera

## Building from source

### Prerequisites

All platforms need:
- **JDK 21** or newer
- **Android SDK** with:
  - SDK Platform 34
  - Build Tools 34.0.0
  - CMake (for the native YUV decoder)
  - NDK (any recent version)

The easiest way to get the Android SDK is to install [Android Studio](https://developer.android.com/studio), which bundles everything. Alternatively, install the [command-line tools](https://developer.android.com/studio#command-line-tools-only) and use `sdkmanager`:

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0" "cmake;3.22.1" "ndk;27.0.12077973"
```

Set the `ANDROID_HOME` environment variable to your SDK location.

#### Linux

```bash
# Debian/Ubuntu
sudo apt install openjdk-21-jdk

# Fedora
sudo dnf install java-21-openjdk-devel

# Arch
sudo pacman -S jdk21-openjdk

export ANDROID_HOME=~/Android/Sdk
```

#### macOS

```bash
brew install openjdk@21
export ANDROID_HOME=~/Library/Android/sdk
```

#### Windows

Install JDK 21 from [Adoptium](https://adoptium.net/) or via `winget install EclipseAdoptium.Temurin.21.JDK`. Set `ANDROID_HOME` to `%LOCALAPPDATA%\Android\Sdk` (the Android Studio default).

Use `gradlew.bat` instead of `./gradlew` in all commands below.

### Build

```bash
git clone https://github.com/botondbako/magnifier-glass.git
cd magnifier-glass
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### Install on a connected device

1. Enable **Developer Options** on your phone (tap Build Number 7 times in Settings → About Phone).
2. Enable **USB Debugging** in Developer Options.
3. Connect the phone via USB and accept the debugging prompt.
4. Run:

```bash
./gradlew installDebug
```

Or install the APK manually:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Run tests

```bash
./gradlew testDebugUnitTest
```

## Third-party libraries

- [PhotoView](https://github.com/chrisbanes/PhotoView) — pinch-to-zoom ImageView (Apache 2.0)
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) — Tesseract OCR engine for Android (Apache 2.0)
- [tessdata_best](https://github.com/tesseract-ocr/tessdata_best) — trained OCR models (Apache 2.0)

## License

Original code copyright (c) 2015 Christian Illies, licensed under the Apache License, Version 2.0.

This fork has been substantially rewritten by [Botond Bako](https://github.com/botondbako). All changes and new code are copyright (c) 2026 Botond Bako, licensed under the same Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
