"""Gesture recognition core: a line-by-line port of app/src/main/java/com/eysnatch/core/*.kt.

Pure Python with no camera, UI or OS dependencies, so it is unit-tested on its own
(desktop/tests/test_core.py mirrors the Android unit tests). Keep the two in sync: the
Kotlin files are the reference implementation.
"""
from __future__ import annotations

import enum
import math
from dataclasses import dataclass, field, replace, asdict
from typing import Callable, Dict, List, Optional, Sequence, Tuple


# --------------------------------------------------------------------------------------------
# Model


class Gesture(enum.Enum):
    LOOK_LEFT = ("Look left", "Keep your head still, glance hard to your left and hold ~half a second", "Eyes")
    LOOK_RIGHT = ("Look right", "Keep your head still, glance hard to your right and hold ~half a second", "Eyes")
    LOOK_UP = ("Look up", "Keep your head still, look up and hold ~half a second", "Eyes")
    LOOK_DOWN = ("Look down", "Keep your head still, look down and hold ~half a second", "Eyes")
    DOUBLE_BLINK = ("Double blink", "Two firm blinks in a row", "Eyes")
    LONG_BLINK = ("Long blink", "Close both eyes for about a second", "Eyes")
    EYES_SHUT = ("Eyes shut 2 s+", "Keep both eyes closed for two seconds or more", "Eyes")
    WINK_LEFT = ("Wink left eye", "Close only your left eye for about half a second", "Eyes")
    WINK_RIGHT = ("Wink right eye", "Close only your right eye for about half a second", "Eyes")
    RAISE_BROWS = ("Raise eyebrows", "Lift both eyebrows and hold ~half a second", "Face")
    TURN_LEFT = ("Turn head left", "Turn your head to your left", "Head")
    TURN_RIGHT = ("Turn head right", "Turn your head to your right", "Head")
    TILT_UP = ("Tilt head up", "Tilt your head up", "Head")
    TILT_DOWN = ("Tilt head down", "Tilt your head down", "Head")

    def __init__(self, label: str, how: str, group: str):
        self.label = label
        self.how = how
        self.group = group

    @property
    def directional(self) -> bool:
        return self in _DIRECTIONAL


_DIRECTIONAL = {
    Gesture.LOOK_LEFT, Gesture.LOOK_RIGHT, Gesture.LOOK_UP, Gesture.LOOK_DOWN,
    Gesture.TURN_LEFT, Gesture.TURN_RIGHT, Gesture.TILT_UP, Gesture.TILT_DOWN,
}


@dataclass(frozen=True)
class GestureEvent:
    gesture: Gesture
    time_ms: int
    repeat: bool = False


@dataclass(frozen=True)
class Frame:
    """One raw measurement of the face. Left/right are the user's own left/right
    (MediaPipe names them from the subject's point of view and we feed it un-mirrored frames)."""
    time_ms: int
    face: bool
    blink_l: float = 0.0
    blink_r: float = 0.0
    gaze_x: float = 0.0  # + = eyes look to the user's right
    gaze_y: float = 0.0  # + = eyes look up
    yaw: float = 0.0  # + = head turned to the user's right
    pitch: float = 0.0  # + = head tilted up
    brow: float = 0.0


@dataclass(frozen=True)
class Axis:
    """A raw signal range: base is neutral, pos / neg are the raw values at the two extremes."""
    base: float
    pos: float
    neg: float

    @property
    def pos_span(self) -> float:
        return self.pos - self.base

    @property
    def neg_span(self) -> float:
        return self.base - self.neg

    def recentered(self, base: float) -> "Axis":
        return Axis(base, base + self.pos_span, base - self.neg_span)


@dataclass(frozen=True)
class Calibration:
    calibrated: bool = False
    swap_eyes: bool = False  # True when the raw "left" blink channel belongs to the user's right eye
    open_l: float = 0.2
    closed_l: float = 0.75
    open_r: float = 0.2
    closed_r: float = 0.75
    gaze_x: Axis = Axis(0.0, 0.6, -0.6)
    gaze_y: Axis = Axis(0.0, 0.35, -0.5)
    yaw: Axis = Axis(0.0, 0.15, -0.15)
    pitch: Axis = Axis(0.0, 0.12, -0.12)
    brow: Axis = Axis(0.0, 0.45, -0.45)

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(d: Optional[dict]) -> "Calibration":
        base = Calibration()
        if not d:
            return base
        kw = {}
        for k, v in base.__dict__.items():
            if k not in d:
                continue
            try:
                if isinstance(v, Axis):
                    a = d[k]
                    kw[k] = Axis(float(a["base"]), float(a["pos"]), float(a["neg"]))
                elif isinstance(v, bool):
                    kw[k] = bool(d[k])
                else:
                    kw[k] = float(d[k])
            except (KeyError, TypeError, ValueError):
                pass
        return replace(base, **kw)


@dataclass(frozen=True)
class Norm:
    """Calibrated frame. Eye closure is 0 (open) .. 1 (closed). Everything else is 0 at the user's
    neutral pose and +-1 at the extreme demonstrated during calibration."""
    face: bool
    close_l: float
    close_r: float
    gx: float
    gy: float
    hx: float
    hy: float
    brow: float


NORM_NONE = Norm(False, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)


# --------------------------------------------------------------------------------------------
# Signals


def _clamp(v: float, lo: float, hi: float) -> float:
    return lo if v < lo else hi if v > hi else v


class _Tracked:
    def __init__(self, axis: Axis, min_span: float, slow_follow: bool = False):
        self.base = axis.base
        self._pos = self._safe(axis.pos_span, min_span)
        self._neg = self._safe(axis.neg_span, min_span)
        self._slow_follow = slow_follow

    @staticmethod
    def _safe(span: float, mn: float) -> float:
        if abs(span) >= mn:
            return span
        return -mn if span < 0 else mn

    def norm(self, x: float) -> float:
        d = x - self.base
        return d / self._pos if d * self._pos >= 0 else d / self._neg

    def follow(self, x: float, n: float, fast: float, slow: float) -> None:
        a = abs(n)
        if a < 0.3:
            self.base += (x - self.base) * fast
        elif self._slow_follow and a < 1.5:
            self.base += (x - self.base) * slow


class Normalizer:
    """Converts raw frames to Norm and slowly follows the user's resting pose."""
    FOLLOW_MS = 10_000.0
    SLOW_FOLLOW_MS = 25_000.0

    def __init__(self, cal: Calibration):
        self.cal = cal
        self.gx = _Tracked(cal.gaze_x, 0.08)
        self.gy = _Tracked(cal.gaze_y, 0.08)
        self.yaw = _Tracked(cal.yaw, 0.03, slow_follow=True)
        self.pitch = _Tracked(cal.pitch, 0.03, slow_follow=True)
        self.brow = _Tracked(cal.brow, 0.08)
        self._started = False
        self._last_t = -1

    def apply(self, f: Frame, follow: bool) -> Norm:
        if not self._started:
            self._started = True
            if not self.cal.calibrated:
                self.gx.base, self.gy.base = f.gaze_x, f.gaze_y
                self.yaw.base, self.pitch.base, self.brow.base = f.yaw, f.pitch, f.brow
        dt = 0 if self._last_t < 0 else int(_clamp(f.time_ms - self._last_t, 0, 500))
        self._last_t = f.time_ms

        cal = self.cal
        raw_l = f.blink_r if cal.swap_eyes else f.blink_l
        raw_r = f.blink_l if cal.swap_eyes else f.blink_r
        n = Norm(
            face=True,
            close_l=self._closure(raw_l, cal.open_l, cal.closed_l),
            close_r=self._closure(raw_r, cal.open_r, cal.closed_r),
            gx=self.gx.norm(f.gaze_x), gy=self.gy.norm(f.gaze_y),
            hx=self.yaw.norm(f.yaw), hy=self.pitch.norm(f.pitch),
            brow=self.brow.norm(f.brow),
        )
        if follow and dt > 0:
            fast = 1.0 - math.exp(-dt / self.FOLLOW_MS)
            slow = 1.0 - math.exp(-dt / self.SLOW_FOLLOW_MS)
            self.gx.follow(f.gaze_x, n.gx, fast, slow)
            self.gy.follow(f.gaze_y, n.gy, fast, slow)
            self.yaw.follow(f.yaw, n.hx, fast, slow)
            self.pitch.follow(f.pitch, n.hy, fast, slow)
            self.brow.follow(f.brow, n.brow, fast, slow)
        return n

    @staticmethod
    def _closure(raw: float, open_: float, closed: float) -> float:
        span = 0.15 if abs(closed - open_) < 0.15 else closed - open_
        return _clamp((raw - open_) / span, 0.0, 1.5)


@dataclass
class FaceLandmarks:
    x: Sequence[float]
    y: Sequence[float]
    width: int
    height: int


class FeatureExtractor:
    """Turns MediaPipe blendshape scores + landmarks into a Frame."""
    NOSE = 1
    RIGHT_EYE_OUTER = 33  # the subject's right eye (left side of an un-mirrored image)
    LEFT_EYE_OUTER = 263
    LIP_TOP = 13
    LIP_BOTTOM = 14
    MIN_EYE_DISTANCE_PX = 20.0

    @classmethod
    def frame(cls, time_ms: int, score: Callable[[str], float], lm: Optional[FaceLandmarks]) -> Frame:
        if lm is None or len(lm.x) <= cls.LEFT_EYE_OUTER:
            return Frame(time_ms, False)
        pose = cls.head_pose(lm)
        if pose is None:
            return Frame(time_ms, False)
        gaze_right = (score("eyeLookOutRight") + score("eyeLookInLeft")) / 2
        gaze_left = (score("eyeLookOutLeft") + score("eyeLookInRight")) / 2
        up = (score("eyeLookUpLeft") + score("eyeLookUpRight")) / 2
        down = (score("eyeLookDownLeft") + score("eyeLookDownRight")) / 2
        brow = (score("browInnerUp") + score("browOuterUpLeft") + score("browOuterUpRight")) / 3
        return Frame(
            time_ms=time_ms, face=True,
            blink_l=score("eyeBlinkLeft"), blink_r=score("eyeBlinkRight"),
            gaze_x=gaze_right - gaze_left, gaze_y=up - down,
            yaw=pose[0], pitch=pose[1], brow=brow,
        )

    @classmethod
    def head_pose(cls, lm: FaceLandmarks) -> Optional[Tuple[float, float]]:
        w, h = float(lm.width), float(lm.height)

        def px(i: int) -> float:
            return lm.x[i] * w

        def py(i: int) -> float:
            return lm.y[i] * h

        ex = px(cls.LEFT_EYE_OUTER) - px(cls.RIGHT_EYE_OUTER)
        ey = py(cls.LEFT_EYE_OUTER) - py(cls.RIGHT_EYE_OUTER)
        eye_dist = math.hypot(ex, ey)
        if eye_dist < cls.MIN_EYE_DISTANCE_PX:
            return None
        ux, uy = ex / eye_dist, ey / eye_dist
        mx = (px(cls.LEFT_EYE_OUTER) + px(cls.RIGHT_EYE_OUTER)) / 2
        my = (py(cls.LEFT_EYE_OUTER) + py(cls.RIGHT_EYE_OUTER)) / 2
        nx, ny = px(cls.NOSE) - mx, py(cls.NOSE) - my
        # Nose sliding towards the image-right (= the user's left) means the head turned left.
        yaw_right = -(nx * ux + ny * uy) / eye_dist
        # Nose position along the eyes -> mouth line (about 0.55). It shrinks when the head tilts up.
        ax = (px(cls.LIP_TOP) + px(cls.LIP_BOTTOM)) / 2 - mx
        ay = (py(cls.LIP_TOP) + py(cls.LIP_BOTTOM)) / 2 - my
        len2 = ax * ax + ay * ay
        if len2 < 1.0:
            return None
        ratio = (nx * ax + ny * ay) / len2
        return yaw_right, -ratio


# --------------------------------------------------------------------------------------------
# Gesture engine


@dataclass(frozen=True)
class EngineConfig:
    sensitivity: float = 1.0  # > 1 triggers earlier / with smaller movements
    hold_scale: float = 1.0  # > 1 needs longer holds
    enabled: frozenset = field(default_factory=lambda: frozenset(Gesture))


class _Dwell:
    __slots__ = ("since", "last_above", "armed", "next_repeat")

    def __init__(self):
        self.reset()

    def reset(self):
        self.since = -1
        self.last_above = -1
        self.armed = True
        self.next_repeat = math.inf


class GestureEngine:
    """Turns a stream of face frames into gesture events. Driven only by frame timestamps,
    so it behaves identically at 10 or 30 fps. Not thread-safe: feed it from one thread."""

    CLOSE_ON = 0.6
    CLOSE_OFF = 0.4
    WINK_OTHER_MAX = 0.5
    OPEN_MAX = 0.45
    TRIGGER = 0.6
    RELEASE_RATIO = 0.5
    HEAD_STILL = 0.7

    DWELL_GAZE_MS = 450
    DWELL_HEAD_MS = 350
    DWELL_BROW_MS = 450
    WINK_MS = 350
    BLINK_MIN_MS = 130
    BLINK_MAX_MS = 450
    DOUBLE_GAP_MS = 600
    LONG_MIN_MS = 650
    SHUT_MIN_MS = 1800
    SHUT_MAX_MS = 7000
    REPEAT_DELAY_MS = 1000
    REPEAT_INTERVAL_MS = 700
    RETRY_MS = 100
    DROPOUT_MS = 160
    COOLDOWN_MS = 450
    FACE_LOST_RESET_MS = 350
    GAP_RESET_MS = 500

    def __init__(self, config: EngineConfig = EngineConfig(), calibration: Calibration = Calibration()):
        self.norm: Norm = NORM_NONE
        self._cfg = config
        self._cal = calibration
        self._normalizer = Normalizer(calibration)
        self._last_t = -1
        self._last_face_t = -1
        self._last_event_t = -self.COOLDOWN_MS
        self._dwell: Dict[Gesture, _Dwell] = {
            g: _Dwell() for g in Gesture if g.directional or g is Gesture.RAISE_BROWS
        }
        self._reset_transient()

    def configure(self, config: EngineConfig, calibration: Calibration) -> None:
        self._cfg = config
        if calibration != self._cal:
            self._cal = calibration
            self._normalizer = Normalizer(calibration)
        self._reset_transient()

    def on_frame(self, f: Frame) -> List[GestureEvent]:
        t = f.time_ms
        if self._last_t >= 0 and t - self._last_t > self.GAP_RESET_MS:
            self._reset_transient()
        self._last_t = t

        if not f.face:
            self.norm = NORM_NONE
            if self._last_face_t >= 0 and t - self._last_face_t > self.FACE_LOST_RESET_MS:
                self._reset_transient()
            return []
        # A face that comes back after being gone for a while must not inherit half-finished gestures.
        if self._last_face_t >= 0 and t - self._last_face_t > self.FACE_LOST_RESET_MS:
            self._reset_transient()
        self._last_face_t = t

        n = self._normalizer.apply(f, follow=not self._left_closed and not self._right_closed)
        self.norm = n
        out: List[GestureEvent] = []
        self._detect_eyes(t, n, out)
        self._detect_directions(t, n, out)
        return out

    def _reset_transient(self) -> None:
        self._left_closed = False
        self._right_closed = False
        self._both_since = -1
        self._last_blink_end = -1
        self._wink_since = -1
        self._wink_side = 0
        self._wink_fired = False
        self._wink_blocked = False
        for d in self._dwell.values():
            d.reset()

    def _scaled(self, ms: int) -> int:
        return int(ms * self._cfg.hold_scale)

    def _fire(self, g: Gesture, t: int, repeat: bool, out: List[GestureEvent]) -> bool:
        if g not in self._cfg.enabled:
            return False
        if t - self._last_event_t < self.COOLDOWN_MS:
            return False
        self._last_event_t = t
        out.append(GestureEvent(g, t, repeat))
        return True

    def _detect_eyes(self, t: int, n: Norm, out: List[GestureEvent]) -> None:
        self._left_closed = n.close_l > self.CLOSE_OFF if self._left_closed else n.close_l >= self.CLOSE_ON
        self._right_closed = n.close_r > self.CLOSE_OFF if self._right_closed else n.close_r >= self.CLOSE_ON

        if self._left_closed and self._right_closed:
            if self._both_since < 0:
                self._both_since = t
        elif self._both_since >= 0:
            start = self._both_since
            self._both_since = -1
            self._both_reopened(start, t, out)
        if not self._left_closed and not self._right_closed:
            self._wink_blocked = False

        if self._wink_blocked:
            side = 0
        elif self._left_closed and not self._right_closed and n.close_r < self.WINK_OTHER_MAX:
            side = -1
        elif self._right_closed and not self._left_closed and n.close_l < self.WINK_OTHER_MAX:
            side = 1
        else:
            side = 0

        if side == 0:
            self._wink_since = -1
            self._wink_side = 0
            self._wink_fired = False
        else:
            if side != self._wink_side or self._wink_since < 0:
                self._wink_side = side
                self._wink_since = t
                self._wink_fired = False
            if not self._wink_fired and t - self._wink_since >= self._scaled(self.WINK_MS):
                g = Gesture.WINK_LEFT if side < 0 else Gesture.WINK_RIGHT
                if self._fire(g, t, False, out):
                    self._wink_fired = True

    def _both_reopened(self, start: int, end: int, out: List[GestureEvent]) -> None:
        dur = end - start
        if dur >= self.BLINK_MIN_MS:
            self._wink_blocked = True
        if self.BLINK_MIN_MS <= dur <= self.BLINK_MAX_MS:
            if self._last_blink_end >= 0 and start - self._last_blink_end <= self.DOUBLE_GAP_MS:
                self._last_blink_end = -1
                self._fire(Gesture.DOUBLE_BLINK, end, False, out)
            else:
                self._last_blink_end = end
        elif self.LONG_MIN_MS <= dur < self.SHUT_MIN_MS:
            self._last_blink_end = -1
            self._fire(Gesture.LONG_BLINK, end, False, out)
        elif self.SHUT_MIN_MS <= dur <= self.SHUT_MAX_MS:
            self._last_blink_end = -1
            self._fire(Gesture.EYES_SHUT, end, False, out)
        else:
            self._last_blink_end = -1

    def _detect_directions(self, t: int, n: Norm, out: List[GestureEvent]) -> None:
        eyes_open = (not self._left_closed and not self._right_closed
                     and n.close_l < self.OPEN_MAX and n.close_r < self.OPEN_MAX)
        head_still = abs(n.hx) < self.HEAD_STILL and abs(n.hy) < self.HEAD_STILL
        trigger = _clamp(self.TRIGGER / self._cfg.sensitivity, 0.3, 0.95)

        gl, gr, gu, gd = max(0.0, -n.gx), max(0.0, n.gx), max(0.0, n.gy), max(0.0, -n.gy)
        g_max = max(gl, gr, gu, gd)
        gaze_ok = eyes_open and head_still
        g_dwell = self._scaled(self.DWELL_GAZE_MS)
        self._step(Gesture.LOOK_LEFT, gl if gl == g_max else 0.0, gl, gaze_ok, t, trigger, g_dwell, out)
        self._step(Gesture.LOOK_RIGHT, gr if gr == g_max else 0.0, gr, gaze_ok, t, trigger, g_dwell, out)
        self._step(Gesture.LOOK_UP, gu if gu == g_max else 0.0, gu, gaze_ok, t, trigger, g_dwell, out)
        self._step(Gesture.LOOK_DOWN, gd if gd == g_max else 0.0, gd, gaze_ok, t, trigger, g_dwell, out)

        hl, hr, hu, hd = max(0.0, -n.hx), max(0.0, n.hx), max(0.0, n.hy), max(0.0, -n.hy)
        h_max = max(hl, hr, hu, hd)
        h_dwell = self._scaled(self.DWELL_HEAD_MS)
        self._step(Gesture.TURN_LEFT, hl if hl == h_max else 0.0, hl, True, t, trigger, h_dwell, out)
        self._step(Gesture.TURN_RIGHT, hr if hr == h_max else 0.0, hr, True, t, trigger, h_dwell, out)
        self._step(Gesture.TILT_UP, hu if hu == h_max else 0.0, hu, True, t, trigger, h_dwell, out)
        self._step(Gesture.TILT_DOWN, hd if hd == h_max else 0.0, hd, True, t, trigger, h_dwell, out)

        # Looking up also lifts the brows a little, so a strong upward glance wins.
        self._step(Gesture.RAISE_BROWS, n.brow, n.brow, eyes_open and gu < 0.5, t, trigger,
                   self._scaled(self.DWELL_BROW_MS), out)

    def _step(self, g: Gesture, value: float, raw: float, gate: bool, t: int, trigger: float,
              dwell_ms: int, out: List[GestureEvent]) -> None:
        d = self._dwell[g]
        if g not in self._cfg.enabled or not gate:
            d.since = -1
            return
        if raw < trigger * self.RELEASE_RATIO:
            d.armed = True
            d.since = -1
            d.next_repeat = math.inf
            return
        if value >= trigger:
            if d.since < 0:
                d.since = t
            d.last_above = t
            if d.armed:
                if t - d.since >= dwell_ms and self._fire(g, t, False, out):
                    d.armed = False
                    d.next_repeat = t + self.REPEAT_DELAY_MS
            elif g.directional and t >= d.next_repeat:
                d.next_repeat = (t + self.REPEAT_INTERVAL_MS) if self._fire(g, t, True, out) else (t + self.RETRY_MS)
        elif d.since >= 0 and t - d.last_above > self.DROPOUT_MS:
            d.since = -1


# --------------------------------------------------------------------------------------------
# Calibration


class CalStep(enum.Enum):
    NEUTRAL = ("Look at the screen", "Relax and look at the screen the way you normally would.", False)
    BOTH_CLOSED = ("Close both eyes", "Close both eyes gently and keep them shut.", False)
    WINK_LEFT = ("Wink your LEFT eye", "Close only your LEFT eye. Keep the right eye open.", False)
    WINK_RIGHT = ("Wink your RIGHT eye", "Close only your RIGHT eye. Keep the left eye open.", False)
    LOOK_LEFT = ("Look LEFT", "Keep your head still. Look as far to your left as you can, using only your eyes.", False)
    LOOK_RIGHT = ("Look RIGHT", "Keep your head still. Look as far to your right as you can, using only your eyes.", False)
    LOOK_UP = ("Look UP", "Keep your head still. Look as high as you can, using only your eyes.", False)
    LOOK_DOWN = ("Look DOWN", "Keep your head still. Look as low as you can, using only your eyes.", False)
    BROWS = ("Raise your eyebrows", "Lift both eyebrows as high as you can and hold them there.", False)
    TURN_LEFT = ("Turn your head LEFT", "Turn your head about 25 degrees to your left and hold it.", True)
    TURN_RIGHT = ("Turn your head RIGHT", "Turn your head about 25 degrees to your right and hold it.", True)
    TILT_UP = ("Tilt your head UP", "Tilt your head up about 20 degrees and hold it.", True)
    TILT_DOWN = ("Tilt your head DOWN", "Tilt your head down about 20 degrees and hold it.", True)

    def __init__(self, title: str, prompt: str, head: bool):
        self.title = title
        self.prompt = prompt
        self.head = head


@dataclass(frozen=True)
class Summary:
    count: int
    blink_l: float
    blink_r: float
    gaze_x: float
    gaze_y: float
    yaw: float
    pitch: float
    brow: float


@dataclass
class CalibrationResult:
    calibration: Calibration
    report: List[str]
    warnings: List[str]


def _median(v: List[float]) -> float:
    if not v:
        return 0.0
    s = sorted(v)
    m = len(s) // 2
    return s[m] if len(s) % 2 == 1 else (s[m - 1] + s[m]) / 2


class Calibrator:
    """Learns the user's personal numbers from a few demonstrated poses."""
    MIN_SAMPLES = 8
    MIN_COVERAGE = 0.6
    WINK_MARGIN = 0.25
    MIN_CLOSE_RISE = 0.25
    MIN_BROW_RISE = 0.10

    def __init__(self, previous: Calibration = Calibration()):
        self.previous = previous
        self._samples: List[Frame] = []
        self._total = 0
        self.summaries: Dict[CalStep, Summary] = {}

    @property
    def sample_count(self) -> int:
        return len(self._samples)

    def start_step(self) -> None:
        self._samples = []
        self._total = 0

    def add(self, f: Frame) -> None:
        """Feed every frame of the capture window, also those without a face."""
        self._total += 1
        if f.face:
            self._samples.append(f)

    def has(self, step: CalStep) -> bool:
        return step in self.summaries

    def finish_step(self, step: CalStep) -> bool:
        n = len(self._samples)
        if not (n >= self.MIN_SAMPLES and n >= self.MIN_COVERAGE * self._total):
            self.start_step()
            return False
        usable = self._samples[n // 4:]
        self.summaries[step] = Summary(
            count=len(usable),
            blink_l=_median([s.blink_l for s in usable]),
            blink_r=_median([s.blink_r for s in usable]),
            gaze_x=_median([s.gaze_x for s in usable]),
            gaze_y=_median([s.gaze_y for s in usable]),
            yaw=_median([s.yaw for s in usable]),
            pitch=_median([s.pitch for s in usable]),
            brow=_median([s.brow for s in usable]),
        )
        self.start_step()
        return True

    def build(self) -> CalibrationResult:
        rep: List[str] = []
        warn: List[str] = []
        S = self.summaries
        prev = self.previous
        neutral = S.get(CalStep.NEUTRAL)
        if neutral is None:
            return CalibrationResult(prev, rep, ["The resting pose is needed first - nothing was changed."])

        cal = replace(prev, calibrated=True)

        # Eyes: both winks must agree before the blink channels are swapped.
        left_wink, right_wink, both_closed = S.get(CalStep.WINK_LEFT), S.get(CalStep.WINK_RIGHT), S.get(CalStep.BOTH_CLOSED)

        def vote(s: Optional[Summary], user_left: bool) -> int:
            if s is None:
                return 0
            a = s.blink_l - neutral.blink_l
            b = s.blink_r - neutral.blink_r
            diff = a - b if user_left else b - a
            return 1 if diff > self.WINK_MARGIN else -1 if diff < -self.WINK_MARGIN else 0

        vl, vr = vote(left_wink, True), vote(right_wink, False)
        swap = (vl < 0) if (vl != 0 and vl == vr) else prev.swap_eyes
        if left_wink is not None:
            if vl != 0 and (vl < 0) == swap:
                rep.append("Left wink: recognised")
            else:
                warn.append("Left wink was not clearly recognised. Blinks and eye movements still work; you can map winks to nothing.")
        if right_wink is not None:
            if vr != 0 and (vr < 0) == swap:
                rep.append("Right wink: recognised")
            else:
                warn.append("Right wink was not clearly recognised. Blinks and eye movements still work; you can map winks to nothing.")

        open_l = neutral.blink_r if swap else neutral.blink_l
        open_r = neutral.blink_l if swap else neutral.blink_r
        if both_closed is not None:
            closed_l = both_closed.blink_r if swap else both_closed.blink_l
            closed_r = both_closed.blink_l if swap else both_closed.blink_r
            if closed_l - open_l < self.MIN_CLOSE_RISE or closed_r - open_r < self.MIN_CLOSE_RISE:
                warn.append("Closed eyes were hard to detect (glare on glasses or dim light?). Try again facing a light.")
                closed_l = max(closed_l, open_l + 0.4)
                closed_r = max(closed_r, open_r + 0.4)
            else:
                rep.append("Blink: recognised")
        else:
            closed_l = max(prev.closed_l, open_l + 0.4)
            closed_r = max(prev.closed_r, open_r + 0.4)
        cal = replace(cal, swap_eyes=swap, open_l=open_l, closed_l=closed_l, open_r=open_r, closed_r=closed_r)

        def axis(name, pos_step, neg_step, pick, min_span, previous_axis: Axis, typical: Axis, neutral_value) -> Axis:
            keep = previous_axis.recentered(neutral_value)
            p = pick(S[pos_step]) if pos_step in S else None
            n = pick(S[neg_step]) if neg_step in S else None
            if p is None and n is None:
                return keep
            if p is None or n is None:
                warn.append(f"{name}: both directions are needed - kept the previous range.")
                return keep
            dp = p - neutral_value
            dn = neutral_value - n
            if dp * dn <= 0:
                warn.append(f"{name}: the two directions looked the same. Redo it with bigger, clearer movements.")
                return keep
            pos, neg = p, n
            if abs(dp) < min_span:
                pos = neutral_value + (-1 if dp < 0 else 1) * abs(typical.pos_span)
                warn.append(f"{name}: one direction was weak - used a typical value. Try a bigger movement.")
            if abs(dn) < min_span:
                neg = neutral_value - (-1 if dn < 0 else 1) * abs(typical.neg_span)
                warn.append(f"{name}: one direction was weak - used a typical value. Try a bigger movement.")
            rep.append(f"{name}: recognised")
            return Axis(neutral_value, pos, neg)

        typ = Calibration()
        cal = replace(
            cal,
            gaze_x=axis("Look left/right", CalStep.LOOK_RIGHT, CalStep.LOOK_LEFT, lambda s: s.gaze_x, 0.10, cal.gaze_x, typ.gaze_x, neutral.gaze_x),
            gaze_y=axis("Look up/down", CalStep.LOOK_UP, CalStep.LOOK_DOWN, lambda s: s.gaze_y, 0.08, cal.gaze_y, typ.gaze_y, neutral.gaze_y),
            yaw=axis("Turn head", CalStep.TURN_RIGHT, CalStep.TURN_LEFT, lambda s: s.yaw, 0.04, cal.yaw, typ.yaw, neutral.yaw),
            pitch=axis("Tilt head", CalStep.TILT_UP, CalStep.TILT_DOWN, lambda s: s.pitch, 0.03, cal.pitch, typ.pitch, neutral.pitch),
        )

        cal = replace(cal, brow=cal.brow.recentered(neutral.brow))
        b = S.get(CalStep.BROWS)
        if b is not None:
            rise = b.brow - neutral.brow
            if rise >= self.MIN_BROW_RISE:
                cal = replace(cal, brow=Axis(neutral.brow, b.brow, neutral.brow - rise))
                rep.append("Eyebrow raise: recognised")
                up = S.get(CalStep.LOOK_UP)
                if up is not None and (up.brow - neutral.brow) > 0.5 * rise:
                    warn.append("Looking up also raises your brows a lot, so 'Raise eyebrows' and 'Look up' may trigger together. Map only one of them.")
            else:
                warn.append("Eyebrow raise was weak. Try lifting them higher, or map that gesture to nothing.")

        return CalibrationResult(cal, rep, warn)
