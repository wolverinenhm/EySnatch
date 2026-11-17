"""Runs the real MediaPipe model on the photos used by the Android instrumented tests (irises moved by
hand), proving model -> features -> engine agree about left/right/up/down on the desktop too."""
from pathlib import Path

import pytest

mp = pytest.importorskip("mediapipe")
cv2 = pytest.importorskip("cv2")

from eysnatch_desktop.core import Gesture, GestureEngine  # noqa: E402
from eysnatch_desktop.tracker import create_landmarker, frame_from_result  # noqa: E402

ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "androidTest" / "assets"


@pytest.fixture(scope="module")
def frames():
    lm = create_landmarker(video=False)
    out = {}
    try:
        for name in ("neutral", "gaze_left", "gaze_right", "gaze_up", "gaze_down"):
            bgr = cv2.imread(str(ASSETS / f"{name}.jpg"))
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            res = lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
            out[name], _ = frame_from_result(res, 0, bgr.shape[1], bgr.shape[0])
    finally:
        lm.close()
    return out


def test_face_is_found(frames):
    f = frames["neutral"]
    assert f.face and abs(f.gaze_x) < 0.3 and f.brow < 0.3


def test_directions(frames):
    n = frames["neutral"]
    assert frames["gaze_right"].gaze_x - n.gaze_x > 0.4
    assert n.gaze_x - frames["gaze_left"].gaze_x > 0.4
    assert frames["gaze_up"].gaze_y - n.gaze_y > 0.1
    assert n.gaze_y - frames["gaze_down"].gaze_y > 0.25


def test_whole_chain_turns_a_glance_into_a_gesture(frames):
    from dataclasses import replace

    engine = GestureEngine()
    events = []
    t = 1000
    for name, ms in [("neutral", 1500), ("gaze_left", 900), ("neutral", 800), ("gaze_right", 900), ("neutral", 800)]:
        for _ in range(ms // 33):
            t += 33
            events += [e.gesture for e in engine.on_frame(replace(frames[name], time_ms=t)) if not e.repeat]
    assert events == [Gesture.LOOK_LEFT, Gesture.LOOK_RIGHT]
