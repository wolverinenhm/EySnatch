# EySnatch

Control your Android phone hands-free with eye and head movements. The front camera is analysed on-device (ML Kit face detection); actions are executed through an Android Accessibility Service. No images leave the phone.

## Gestures

| Movement | Action |
|---|---|
| Turn head left / right | Swipe left / right |
| Tilt head up / down | Scroll up / down |
| Double blink | Back |
| Long blink (1 s) | Home |
| Wink left eye (hold ~0.35 s) | Screenshot (Android 9+) |
| Wink right eye (hold ~0.35 s) | Recent apps |

Android has no system-wide "forward" action; add one in `EySnatchAccessibilityService` if needed (e.g. a swipe gesture for your browser).

## Build

Open the folder in Android Studio (Koala+) and run on a device with a front camera (minSdk 24). Then in the app: 1) enable the *EySnatch* accessibility service, 2) tap **Start eye control** and grant camera permission.

## Tuning

Thresholds live in `EyeGestureDetector.kt`. If left/right directions or left/right winks feel mirrored on your device, swap the signs/sides there.

## Notes

Not yet built/tested on a physical device. Keep the phone propped up facing you in decent light.
