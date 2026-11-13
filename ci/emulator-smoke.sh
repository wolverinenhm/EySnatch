#!/usr/bin/env bash
# Runs on a booted emulator (started by reactivecircus/android-emulator-runner).
# Installs the build, runs the instrumented tests, starts eye control against the emulator camera
# and keeps logs + screenshots in ci-out/ so a human (or an agent) can look at them.
set -u
PKG=com.eysnatch
OUT=ci-out
mkdir -p "$OUT"
FAIL=0
fail() { echo "::error::$*"; FAIL=1; }

echo "=== install"
adb install -r dist/EySnatch-debug.apk || fail "app install failed"
adb install -r dist/EySnatch-androidTest.apk || fail "test apk install failed"
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

echo "=== switch the accessibility service on"
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.EySnatchAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo "=== instrumented tests"
adb logcat -c
adb shell am instrument -w $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrumentation.txt"
adb logcat -d > "$OUT/instrumentation-logcat.txt"
grep -E "EySnatch|AndroidRuntime" "$OUT/instrumentation-logcat.txt" > "$OUT/instrumentation-app-log.txt"
grep -q "^OK (" "$OUT/instrumentation.txt" || fail "instrumented tests failed"
grep -q "ACTIONS TEST RAN" "$OUT/instrumentation-app-log.txt" || fail "the accessibility Home test did not run (service not connected?)"
grep -q "SWIPES TEST RAN" "$OUT/instrumentation-app-log.txt" || fail "the swipe test did not run"
grep -q "swipe was not accepted" "$OUT/instrumentation-app-log.txt" && fail "Android rejected a swipe gesture"

echo "=== start eye control on the emulator camera"
adb shell input keyevent KEYCODE_HOME
sleep 2
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez autostart true
sleep 25
adb exec-out screencap -p > "$OUT/01-main-running.png"
adb logcat -d > "$OUT/logcat-full.txt"
grep -E "EySnatch|AndroidRuntime" "$OUT/logcat-full.txt" > "$OUT/app-log.txt"
adb shell dumpsys activity services $PKG > "$OUT/services.txt"
grep -q "camera bound" "$OUT/app-log.txt" || fail "the camera was not opened"
grep -Eq "STATS frames=[1-9]" "$OUT/app-log.txt" || fail "no camera frames were processed"
grep -q "isForeground=true" "$OUT/services.txt" || fail "the tracking service is not in the foreground"
grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt" && fail "the app crashed"

echo "=== screenshots of every screen"
n=2
for a in LiveActivity SettingsActivity CalibrateActivity; do
  adb shell am start -n $PKG/.$a
  sleep 4
  adb exec-out screencap -p > "$OUT/0$n-$a.png"
  n=$((n + 1))
done
adb logcat -d | grep -q "FATAL EXCEPTION" && fail "a screen crashed"

echo "=== stop"
adb shell am force-stop $PKG
adb shell dumpsys package $PKG | grep -E "versionName|versionCode" | head -2 | tee "$OUT/version.txt"

if [ "$FAIL" -ne 0 ]; then echo "SMOKE TEST FAILED"; exit 1; fi
echo "SMOKE TEST PASSED"
