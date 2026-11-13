# EySnatch

Control your Android phone **hands-free with your eyes**: swipe left/right, scroll, go back, go home, take a screenshot, open recent apps, move forward... without touching the screen.

The front camera is analysed **on the phone** (Google MediaPipe Face Landmarker). Nothing is recorded, stored or sent anywhere. An Android accessibility service then performs the actions.

## Install (no Android Studio needed)

1. On your phone open the latest release: **<https://github.com/wolverinenhm/EySnatch/releases/latest>**
2. Download `EySnatch-v….apk` and open it (allow "install unknown apps" for your browser/files app when asked). If Play Protect says the app is unknown, that is because it is a personal build that is not on the Play Store: choose **More details → Install anyway**.
3. Open **EySnatch** and follow the checklist on the home screen:
   - **Allow camera** (and notifications).
   - **Turn on the accessibility service** – this is what lets EySnatch press Back/Home and swipe for you.
     *On Android 13+ the switch may be greyed out for apps installed from a file.* Tap **Open App info**, press the **⋮** menu (top right) → **Allow restricted settings**, then turn the service on.
   - Optional: allow background use so battery savers don't stop it.
4. Tap **Calibrate** once (about 45 s). It learns how *your* eyes look to *your* camera, so left/right are never mixed up and gestures are reliable.
5. Tap **Start eye control**, leave the app, and use your eyes. A small label and a short vibration confirm each action.

Updates install over the old version (every build is signed with the same key).

## Gestures (all configurable)

| Movement | Default action |
|---|---|
| Look left (hold ~½ s, head still) | Swipe left |
| Look right | Swipe right |
| Look up (hold = keeps scrolling) | Scroll up |
| Look down (hold = keeps scrolling) | Scroll down |
| Double blink (two firm blinks) | Back |
| Long blink (~1 s) | Home |
| Eyes shut 2 s or more | Pause / resume EySnatch |
| Wink left eye | Screenshot |
| Wink right eye | Recent apps |
| Raise eyebrows | Forward / next page |
| Turn / tilt head | off (optional) |

Other actions you can assign in **Gestures and settings**: open notifications, quick settings, lock screen, play/pause media, volume up/down, backward / previous page, or *do nothing* (switches a gesture off).

*Forward / backward:* Android has no system-wide "forward" button, so EySnatch scrolls the main scrollable thing on screen by one page (next photo, next page, next screen of a list) and falls back to a swipe.

**Pause:** hold your eyes shut for two seconds to pause EySnatch (handy when you talk to someone or look around), and again to resume. While paused only that gesture works. You can also pause/stop from the notification.

## Tips

- Hold the phone about an arm's length away with your face in the middle of the front camera, with light on your face (not a bright window behind you).
- **Live view** shows exactly what the app sees: the camera image, the face points and a bar for every signal with the trigger line. While an EySnatch screen is open, gestures are only *shown*, not performed. It is the best place to tune **Sensitivity** and **Hold time**.
- Gestures firing by accident? Lower the sensitivity, raise the hold time, or switch off the gestures you don't need. Missing gestures? Re-run **Calibrate** and make the movements bigger.
- Looking up also lifts your eyebrows a little; Calibrate warns you if *Look up* and *Raise eyebrows* collide on your face. Map only one of them then.
- Not everyone can wink one eye cleanly. Calibrate tells you if winks are not recognised; use blinks and eye movements instead.
- Eye tracking uses the CPU. **Battery saver** checks your eyes less often (double blink may be missed).
- Some phone makers (Xiaomi, Huawei, OnePlus, Samsung "sleeping apps"...) stop background apps aggressively. Allow EySnatch to run in the background in your phone's battery settings.

## How it works

```
front camera ─ CameraX ─► MediaPipe Face Landmarker (blendshapes + 478 landmarks, on-device)
      ─► FeatureExtractor  (blink L/R, gaze x/y, head yaw/pitch, eyebrows)
      ─► Normalizer        (your calibrated neutral pose and ranges; follows slow posture drift)
      ─► GestureEngine     (blink/wink/long-blink timing, hold + hysteresis + repeat, cooldown)
      ─► Accessibility service ─► Back / Home / swipe / screenshot / ...
```

- `app/src/main/java/com/eysnatch/core/` is plain Kotlin with no Android dependencies and a full unit-test suite (blinks, winks, holds, jitter, drift, face loss, low frame rates, calibration of swapped/flipped signals).
- `EyeTrackingService` is the foreground camera service; `EySnatchAccessibilityService` performs actions and shows the on-screen label.
- Left/right and up/down are **learned during calibration** instead of assumed, so a different camera, model or orientation cannot make "look left" swipe the wrong way.

## Privacy

Camera frames are processed in memory on the phone and discarded. The app has no internet permission. The accessibility service is used only to perform the actions above; it does not read or store screen content (it asks for window access only to find the scrollable area for *Forward/Backward*).

## Build it yourself

Requirements: JDK 17 and the Android SDK (Android Studio Koala or newer works out of the box).

```
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # engine + calibration unit tests (no device needed)
./gradlew connectedDebugAndroidTest  # on a device/emulator: real MediaPipe model on test photos, screens, accessibility actions
```

`.github/workflows/android.yml` builds on every push, runs the unit tests, then boots an Android emulator, runs the instrumented tests, starts eye control against the emulator camera and stores logs and screenshots of every screen as a build artifact. Pushing a tag like `v1.1.0` publishes the APK as a GitHub release.

MediaPipe ships no x86_64 library, so the **debug** build also contains the 32-bit x86 one (that is what the CI emulator runs); the **release** build is ARM only (arm64-v8a, armeabi-v7a).

## Limitations

- It tracks head-relative **eye rotation** with the front camera, not exactly which pixel you look at; large, deliberate glances are the gestures.
- Android does not allow accessibility services on some secure screens (banking apps, some system dialogs); gestures cannot act there.
- It needs a front camera and Android 8.0 or newer (screenshots and lock screen need Android 9+).

## Credits

- Face model and runtime: [MediaPipe Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker) by Google (Apache-2.0).
- Test photos used by the instrumented tests are derived from the official White House portrait of Barack Obama by Pete Souza (public domain, U.S. government work), with the irises moved by hand.
