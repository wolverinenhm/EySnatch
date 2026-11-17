"""Webcam -> MediaPipe Face Landmarker -> FeatureExtractor -> GestureEngine, on a background thread.

Mirrors EyeTrackingService.kt. Results reach the UI through `events` (a queue) and a few attributes
the UI polls; nothing here touches Tk.
"""
from __future__ import annotations

import logging
import os
import queue
import sys
import threading
import time
from pathlib import Path
from typing import Callable, Optional

from .core import NORM_NONE, FaceLandmarks, FeatureExtractor, Frame, GestureEngine, GestureEvent, Norm

log = logging.getLogger("eysnatch")

MODEL_NAME = "face_landmarker.task"
FRAME_INTERVAL_S = 0.030
SAVER_INTERVAL_S = 0.080
IDLE_INTERVAL_S = 0.200
IDLE_AFTER_S = 10.0
STALL_S = 4.0
PREVIEW_WIDTH = 360


def find_model() -> Path:
    """The model ships inside the PyInstaller bundle; from a source checkout it is the Android asset."""
    here = Path(__file__).resolve().parent
    candidates = []
    if getattr(sys, "_MEIPASS", None):
        candidates.append(Path(sys._MEIPASS) / MODEL_NAME)
    env = os.environ.get("EYSNATCH_MODEL")
    if env:
        candidates.insert(0, Path(env))
    candidates += [here / MODEL_NAME, here.parent.parent / "app" / "src" / "main" / "assets" / MODEL_NAME]
    for c in candidates:
        if c.is_file():
            return c
    raise FileNotFoundError(f"{MODEL_NAME} not found (looked in: {', '.join(str(c) for c in candidates)})")


def create_landmarker(video: bool = True):
    from mediapipe.tasks.python import BaseOptions, vision

    options = vision.FaceLandmarkerOptions(
        # A buffer instead of a path: MediaPipe cannot open paths with non-ASCII characters on Windows.
        base_options=BaseOptions(model_asset_buffer=find_model().read_bytes()),
        running_mode=vision.RunningMode.VIDEO if video else vision.RunningMode.IMAGE,
        num_faces=1,
        min_face_detection_confidence=0.5,
        min_face_presence_confidence=0.5,
        min_tracking_confidence=0.5,
        output_face_blendshapes=True,
    )
    return vision.FaceLandmarker.create_from_options(options)


def frame_from_result(result, t_ms: int, width: int, height: int):
    """MediaPipe result -> (Frame, landmarks or None)."""
    if not result.face_landmarks or not result.face_blendshapes:
        return Frame(t_ms, False), None
    points = result.face_landmarks[0]
    scores = {c.category_name: c.score for c in result.face_blendshapes[0]}
    xs = [p.x for p in points]
    ys = [p.y for p in points]
    lm = FaceLandmarks(xs, ys, width, height)
    return FeatureExtractor.frame(t_ms, lambda k: scores.get(k, 0.0), lm), lm


def open_camera(index: int):
    import cv2

    backends = [cv2.CAP_DSHOW, cv2.CAP_MSMF] if sys.platform.startswith("win") else [cv2.CAP_ANY]
    for b in backends:
        cap = cv2.VideoCapture(index, b)
        if cap is not None and cap.isOpened():
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            return cap
        if cap is not None:
            cap.release()
    return None


class Tracker:
    def __init__(self, settings):
        self.settings = settings
        self.events: "queue.Queue[GestureEvent]" = queue.Queue()
        self.engine = GestureEngine()
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

        # Read by the UI.
        self.running = False
        self.status = "Stopped"
        self.error: Optional[str] = None
        self.fps = 0.0
        self.face_ok = False
        self.frame: Optional[Frame] = None
        self.norm: Norm = NORM_NONE
        self.preview = None  # small mirrored BGR image with the face points, when want_preview
        self.want_preview = False
        self.frame_listener: Optional[Callable[[Frame], None]] = None

    # ------------------------------------------------------------------ control (UI thread)

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        self.error = None
        self.running = True
        self.status = "Starting the camera..."
        self._thread = threading.Thread(target=self._run, name="eysnatch-tracker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        t = self._thread
        if t is not None and t is not threading.current_thread():
            t.join(timeout=3)
        self._thread = None

    # ------------------------------------------------------------------ worker thread

    def _fail(self, msg: str) -> None:
        log.error(msg)
        self.error = msg
        self.status = msg

    def _run(self) -> None:
        cap = None
        landmarker = None
        try:
            try:
                import cv2
                import mediapipe as mp
                landmarker = create_landmarker(video=True)
            except Exception as e:
                self._fail(f"Could not load the face model: {e}")
                return
            cap = open_camera(self.settings.camera_index)
            if cap is None:
                self._fail(f"Could not open camera {self.settings.camera_index}. Is it plugged in, or used by another app?")
                return
            self.status = "Looking for your face..."
            self._loop(cap, landmarker, cv2, mp)
        except Exception as e:  # pragma: no cover - last-resort guard so the UI shows something
            log.exception("tracker crashed")
            self._fail(f"Eye tracking stopped: {e}")
        finally:
            if cap is not None:
                cap.release()
            if landmarker is not None:
                try:
                    landmarker.close()
                except Exception:
                    pass
            self.running = False
            self.face_ok = False
            self.fps = 0.0
            self.norm = NORM_NONE
            if not self.error:
                self.status = "Stopped"

    def _loop(self, cap, landmarker, cv2, mp) -> None:
        seen_version = -1
        started = time.monotonic()
        last_submit = 0.0
        last_ts = -1
        last_good_read = started
        last_face = 0.0
        stat_start, stat_frames = started, 0
        preview_counter = 0
        t0 = started

        while not self._stop.is_set():
            ok, bgr = cap.read()
            now = time.monotonic()
            if not ok or bgr is None:
                if now - last_good_read > STALL_S:
                    self.status = "No pictures from the camera. Is another app using it?"
                    self.face_ok = False
                time.sleep(0.05)
                continue
            last_good_read = now

            idle = now - max(last_face, started) > IDLE_AFTER_S and self.frame_listener is None and not self.want_preview
            interval = IDLE_INTERVAL_S if idle else (SAVER_INTERVAL_S if self.settings.battery_saver else FRAME_INTERVAL_S)
            if now - last_submit < interval:
                continue
            last_submit = now
            ts = int((now - t0) * 1000)
            if ts <= last_ts:
                ts = last_ts + 1
            last_ts = ts

            h, w = bgr.shape[:2]
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            result = landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), ts)
            frame, lm = frame_from_result(result, ts, w, h)

            if self.settings.version != seen_version:
                seen_version = self.settings.version
                self.engine.configure(self.settings.engine_config(), self.settings.calibration)

            self.frame = frame
            listener = self.frame_listener
            if listener is not None:
                listener(frame)
            for e in self.engine.on_frame(frame):
                self.events.put(e)
            self.norm = self.engine.norm

            if frame.face:
                last_face = now
            self.face_ok = last_face > 0 and now - last_face < 2.5
            stat_frames += 1
            if now - stat_start >= 1.0:
                self.fps = stat_frames / (now - stat_start)
                stat_start, stat_frames = now, 0
            self.status = "Watching your eyes" if self.face_ok else "No face in view - sit in front of the camera"

            if self.want_preview:
                preview_counter += 1
                if preview_counter % 2 == 0:
                    self.preview = self._make_preview(cv2, bgr, lm)
            else:
                self.preview = None

    @staticmethod
    def _make_preview(cv2, bgr, lm):
        h, w = bgr.shape[:2]
        pw = PREVIEW_WIDTH
        ph = max(1, int(h * pw / w))
        small = cv2.flip(cv2.resize(bgr, (pw, ph), interpolation=cv2.INTER_AREA), 1)  # mirror: feels natural
        if lm is not None:
            for i in range(0, len(lm.x), 3):
                x = int((1.0 - lm.x[i]) * pw)
                y = int(lm.y[i] * ph)
                if 0 <= x < pw and 0 <= y < ph:
                    small[y, x] = (80, 230, 120)
            for i in (33, 263, 468, 473) if len(lm.x) > 473 else (33, 263):
                cv2.circle(small, (int((1.0 - lm.x[i]) * pw), int(lm.y[i] * ph)), 2, (0, 200, 255), -1)
        return small
