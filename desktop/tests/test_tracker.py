"""The real tracker thread with a fake webcam that shows the test photos: proves the camera loop, the
model in VIDEO mode, settings reload and the event queue work together."""
import time
from pathlib import Path

import pytest

pytest.importorskip("mediapipe")
cv2 = pytest.importorskip("cv2")

from eysnatch_desktop import tracker as tr  # noqa: E402
from eysnatch_desktop.core import Gesture  # noqa: E402
from eysnatch_desktop.settings import Settings  # noqa: E402

ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "androidTest" / "assets"


class FakeCamera:
    def __init__(self, script):
        self.images = {n: cv2.imread(str(ASSETS / f"{n}.jpg")) for n, _ in script}
        self.script = script
        self.start = time.monotonic()

    def read(self):
        time.sleep(0.03)
        t = time.monotonic() - self.start
        for name, secs in self.script:
            if t < secs:
                return True, self.images[name].copy()
            t -= secs
        return True, self.images[self.script[-1][0]].copy()

    def release(self):
        pass


def test_tracker_reports_a_glance(monkeypatch, tmp_path):
    script = [("neutral", 1.5), ("gaze_left", 1.2), ("neutral", 1.0)]
    monkeypatch.setattr(tr, "open_camera", lambda index: FakeCamera(script))
    t = tr.Tracker(Settings(tmp_path / "s.json"))
    t.want_preview = True
    t.start()
    try:
        deadline = time.monotonic() + 20
        got = []
        while time.monotonic() < deadline and Gesture.LOOK_LEFT not in got:
            while not t.events.empty():
                got.append(t.events.get().gesture)
            time.sleep(0.05)
        assert t.error is None, t.error
        assert t.face_ok and t.fps > 3
        assert t.preview is not None and t.preview.shape[1] == tr.PREVIEW_WIDTH
        assert got == [Gesture.LOOK_LEFT]
    finally:
        t.stop()
    assert not t.running and t.status == "Stopped"


def test_missing_camera_is_reported(monkeypatch, tmp_path):
    monkeypatch.setattr(tr, "open_camera", lambda index: None)
    t = tr.Tracker(Settings(tmp_path / "s.json"))
    t.start()
    for _ in range(100):
        if not t.running:
            break
        time.sleep(0.05)
    assert not t.running and "camera" in (t.error or "")


def test_settings_round_trip(tmp_path):
    from eysnatch_desktop.actions import PCAction

    s = Settings(tmp_path / "s.json")
    s.mapping[Gesture.WINK_LEFT] = PCAction.LOCK_SCREEN
    s.sensitivity = 1.4
    s.save()
    again = Settings(tmp_path / "s.json")
    assert again.action(Gesture.WINK_LEFT) is PCAction.LOCK_SCREEN and again.sensitivity == 1.4
    assert Gesture.TURN_LEFT not in again.engine_config().enabled
    (tmp_path / "s.json").write_text("{not json")
    assert Settings(tmp_path / "s.json").sensitivity == 1.0
