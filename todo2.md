# Accessibility Improvements TODO

## High Impact, Low Effort

1. **Larger touch targets** — All buttons are 36dp, below Android's 48dp minimum. For low-vision users, 56–64dp is appropriate. Reduced vision often correlates with reduced motor control.

2. **Double-tap to freeze/unfreeze** — Currently requires finding the small ⏸ button. Double-tap anywhere on the preview would be much faster. Most-used action.

3. **Volume buttons for zoom** — Physical buttons are findable by touch. Map volume-up/down to zoom in/out during live preview.

4. **Auto-flashlight based on ambient light** — Current `auto_torch` only fires at startup. Use camera ambient light sensor or `SensorManager.SENSOR_TYPE_LIGHT` to auto-toggle flash in dark conditions.

5. **Haptic feedback on all button presses** — Short vibration confirms tap registered. Critical when you can't see button response. One line: `v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)`.

6. **Long-press speak button to repeat** — Currently tapping while speaking stops TTS. Long-press to repeat last OCR result without re-running Tesseract.

## High Impact, Medium Effort

7. **Swipe gestures for color mode cycling** — Swipe left/right to cycle through 5 color modes instead of hunting for the ◐ button.

8. **Auto-freeze on steady hold** — Detect device held still (accelerometer) for ~2 seconds and auto-freeze. Helps users who struggle to hold steady while pressing pause.

9. **High-contrast button themes** — Offer high-contrast mode with large white-on-black or yellow-on-black buttons with text labels ("ZOOM+", "FREEZE", "SPEAK") instead of emoji.

10. **Continuous OCR/TTS mode** — Instead of freeze → tap speak → wait, continuously read detected text as camera moves. Useful for scanning documents.

## Medium Impact, Low Effort

11. **Zoom level announced via TTS** — After zoom change, briefly speak "3x" so user knows level without reading the small label.

12. **Startup sound/vibration** — Confirm app is ready (camera initialized) with sound or vibration.

13. **Prevent accidental exit** — ✕ button is easy to hit accidentally. Require long-press to exit.

14. **Larger zoom step option** — Current 10% step is too small for button taps. Offer 25% "large step" in settings.

## Medium Impact, Higher Effort

15. **Edge-to-edge button strip** — Full-width bottom bar with large labeled buttons instead of small floating ones.

16. **Voice commands** — "Zoom in", "Freeze", "Read", "Flash on". Hands-free operation.

17. **Magnification window mode** — Magnified circular region following finger drag instead of full-screen. Useful for reading along a line.

## Additional Ideas from Research (competitor apps, Apple Magnifier, WCAG 2.2)

### From Apple iOS Magnifier (best-in-class reference)

18. **Point and Speak mode** — Live OCR that reads text as you point the camera at it, without freezing. Combines camera + OCR + TTS in real-time. Apple uses LiDAR but camera-only is feasible with Tesseract on a per-frame basis (throttled to every ~2 seconds).

19. **Reader Mode** — After freezing, reflow recognized text into a clean, resizable, recolorable text view (like an e-reader). User controls font size, font color, and background color. Much easier to read than a magnified photo of text.

20. **Detection Mode with haptic/audio feedback** — Vibrate or beep when text is detected in the camera view, helping blind users aim the camera before freezing.

### From competitor Android magnifier apps

21. **Adjustable flashlight brightness** — Current app is on/off only. A brightness slider (or 3 levels: low/medium/high) prevents glare on glossy surfaces like pill bottles and packaging.

22. **Night/dark mode for the UI** — Dim the button backgrounds and use warm tones to reduce eye strain in dark environments. Different from the color filter modes which affect the camera image.

23. **Reading guide / line focus overlay** — A horizontal strip overlay that masks everything except 2–3 lines of text. Helps users with macular degeneration or nystagmus track their reading position. Draggable up/down.

24. **Continuous zoom slider** — Replace the discrete +/- buttons with a vertical slider along the screen edge. Faster and more precise than repeated button taps.

25. **Auto-brightness for display** — Dynamically adjust screen brightness based on ambient light sensor, independent of the system setting. Bright outdoors, dim indoors.

26. **Macro mode hint** — When zoom is very high and focus fails, suggest the user move the phone further from the object. Many low-vision users hold the phone too close.

### From WCAG 2.2 and Android accessibility guidelines

27. **48dp minimum touch targets** — WCAG 2.5.8 (Target Size minimum) requires 24×24 CSS px; Android guidelines recommend 48dp. Current 36dp buttons fail both.

28. **Focus indicators** — When using keyboard/switch navigation, focused buttons need a visible indicator. Current buttons have no `android:focusable="true"` except the speak button.

29. **TalkBack compatibility audit** — Verify all buttons have meaningful `contentDescription`, correct traversal order, and that state changes (frozen/live, flash on/off) are announced.

30. **Reduced motion option** — The button press animation (`animScale`) may be disorienting for some users. Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (reduce motion preference).

31. **Consistent help location** — WCAG 3.2.6 requires help mechanisms in a consistent location. Add a simple help/tutorial overlay on first launch showing what each button does.

### Quality of life

32. **Remember last color mode** — Currently resets to "normal" on restart. Save the preferred color mode in SharedPreferences.

33. **Remember flash state** — If the user had flash on, restore it after rotation or resume (currently only auto-torch-on-start exists).

34. **Zoom level persistence across sessions** — The default zoom preference exists but the last-used zoom during a session is lost on restart.

35. **Quick-launch from lock screen** — Register as an accessibility shortcut so users can open the app with a triple-press of the power button.

36. **Widget / notification shortcut** — A home screen widget or persistent notification for instant launch without finding the app icon.
