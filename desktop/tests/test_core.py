"""Port of app/src/test/java/com/eysnatch/core/*Test.kt: the desktop core must behave exactly like the
Android one, so the same scenarios run against it."""
import math

import pytest

from eysnatch_desktop.core import (
    Axis, CalStep, Calibration, Calibrator, EngineConfig, FaceLandmarks, FeatureExtractor, Frame,
    Gesture, GestureEngine, Normalizer,
)

G = Gesture


class Sim:
    """Drives the engine with synthetic frames at a fixed frame rate."""

    def __init__(self, engine=None, fps=30):
        self.engine = engine or GestureEngine()
        self.fps = fps
        self.t = 1000
        self.events = []
        self.blink_l = self.blink_r = 0.05
        self.gx = self.gy = self.yaw = self.pitch = self.brow = 0.0
        self.face = True

    def run(self, ms):
        for _ in range(ms * self.fps // 1000):
            self.t += 1000 // self.fps
            self.events += self.engine.on_frame(Frame(self.t, self.face, self.blink_l, self.blink_r,
                                                      self.gx, self.gy, self.yaw, self.pitch, self.brow))

    def open(self):
        self.blink_l = self.blink_r = 0.05

    def close_both(self):
        self.blink_l = self.blink_r = 0.85

    def first(self):
        return [e.gesture for e in self.events if not e.repeat]


# ------------------------------------------------------------------------------------ engine


def test_double_blink_fires_once():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(200); s.open(); s.run(250)
    s.close_both(); s.run(200); s.open(); s.run(1000)
    assert s.first() == [G.DOUBLE_BLINK]


def test_natural_quick_blinks_are_ignored():
    s = Sim(); s.run(1000)
    for _ in range(5):
        s.close_both(); s.run(100); s.open(); s.run(300)
    assert s.events == []


def test_single_firm_blink_does_nothing():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(250); s.open(); s.run(1500)
    assert s.events == []


def test_long_blink_is_recognised_when_eyes_reopen():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(1000)
    assert s.events == []
    s.open(); s.run(500)
    assert s.first() == [G.LONG_BLINK]


def test_eyes_shut_for_two_seconds():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(2500); s.open(); s.run(500)
    assert s.first() == [G.EYES_SHUT]


def test_too_long_closure_is_ignored():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(9000); s.open(); s.run(500)
    assert s.events == []


def test_wink_left_and_right():
    s = Sim(); s.run(1000)
    s.blink_l = 0.85; s.run(700); s.open(); s.run(800)
    s.blink_r = 0.85; s.run(700); s.open(); s.run(800)
    assert s.first() == [G.WINK_LEFT, G.WINK_RIGHT]


def test_wink_fires_once_per_wink():
    s = Sim(); s.run(1000)
    s.blink_r = 0.85; s.run(3000); s.open(); s.run(500)
    assert s.first() == [G.WINK_RIGHT]


def test_blink_with_eyes_closing_slightly_apart_is_not_a_wink():
    s = Sim(); s.run(1000)
    s.blink_l = 0.85; s.run(66)
    s.close_both(); s.run(200)
    s.blink_r = 0.05; s.run(66)
    s.open(); s.run(1000)
    assert s.events == []


def test_squinting_one_eye_a_smidge_is_not_a_wink():
    s = Sim(); s.run(1000)
    s.blink_l = 0.45; s.run(2000)
    assert s.events == []


@pytest.mark.parametrize("g,x,y", [(G.LOOK_LEFT, -0.7, 0), (G.LOOK_RIGHT, 0.7, 0), (G.LOOK_UP, 0, 0.5), (G.LOOK_DOWN, 0, -0.6)])
def test_look_each_direction(g, x, y):
    s = Sim(); s.run(1000)
    s.gx, s.gy = x, y; s.run(700)
    s.gx, s.gy = 0, 0; s.run(700)
    assert s.first() == [g]


def test_glance_that_is_too_short_does_nothing():
    s = Sim(); s.run(1000)
    s.gx = -0.7; s.run(250); s.gx = 0; s.run(1000)
    assert s.events == []


def test_reading_eye_movements_are_not_gestures():
    s = Sim(); s.run(1000)
    for i in range(30):
        s.gx = -0.15 if i % 2 == 0 else 0.15
        s.gy = -0.1
        s.run(200)
    assert s.events == []


def test_holding_repeats_after_a_delay():
    s = Sim(); s.run(1000)
    s.gy = -0.6; s.run(3000)
    assert sum(1 for e in s.events if not e.repeat) == 1
    assert 2 <= sum(1 for e in s.events if e.repeat) <= 3
    assert all(e.gesture == G.LOOK_DOWN for e in s.events)


def test_jitter_around_the_threshold_does_not_fire_twice():
    s = Sim(); s.run(1000)
    for i in range(40):
        s.gx = -(0.42 + (0.05 if i % 2 == 0 else -0.04))
        s.run(100)
    assert sum(1 for e in s.events if not e.repeat) <= 1


def test_gaze_is_ignored_while_the_eyes_are_closed():
    s = Sim(); s.run(1000)
    s.close_both(); s.gx = -0.9; s.run(300)
    s.open(); s.gx = 0; s.run(1000)
    assert s.events == []


def test_diagonal_glance_picks_the_strongest_direction():
    s = Sim(); s.run(1000)
    s.gx, s.gy = -0.4, 0.5; s.run(800)
    s.gx, s.gy = 0, 0; s.run(500)
    assert s.first() == [G.LOOK_UP]


def test_a_non_zero_resting_pose_is_learned():
    s = Sim()
    s.gx, s.gy, s.pitch, s.yaw, s.brow = 0.3, -0.4, -0.45, 0.05, 0.2
    s.blink_l = s.blink_r = 0.1
    s.run(20_000)
    assert s.events == []


def test_slow_drift_of_the_resting_pose_is_followed():
    s = Sim(); s.run(1000)
    for i in range(200):
        s.gy = -0.002 * i
        s.run(500)
    assert s.events == []


def test_losing_the_face_mid_blink_resets_the_blink():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(300)
    s.face = False; s.run(1500)
    s.face = True; s.open(); s.run(1000)
    assert s.events == []


def test_sensitivity_changes_how_big_a_movement_must_be():
    normal = Sim(); normal.run(1000); normal.gx = -0.45; normal.run(800)
    assert normal.first() == [G.LOOK_LEFT]
    careful = Sim(GestureEngine(EngineConfig(sensitivity=0.5)))
    careful.run(1000); careful.gx = -0.45; careful.run(800)
    assert careful.events == []


def test_hold_scale_lengthens_the_hold():
    p = Sim(GestureEngine(EngineConfig(hold_scale=2.0)))
    p.run(1000); p.gx = -0.7; p.run(700)
    assert p.events == []
    p.run(600)
    assert p.first() == [G.LOOK_LEFT]


def test_only_enabled_gestures_are_reported():
    s = Sim(GestureEngine(EngineConfig(enabled=frozenset({G.LOOK_LEFT}))))
    s.run(1000)
    s.close_both(); s.run(1000); s.open(); s.run(500)
    s.gx = 0.8; s.run(800)
    assert s.events == []
    s.gx = 0; s.run(500)
    s.gx = -0.8; s.run(800)
    assert s.first() == [G.LOOK_LEFT]


def test_head_turns_and_tilts_are_reported_and_suppress_eye_gestures():
    s = Sim(); s.run(1000)
    s.yaw, s.gx = 0.2, -0.7; s.run(800)
    assert s.first() == [G.TURN_RIGHT]
    s.yaw, s.gx = 0, 0; s.run(500)
    s.pitch = 0.2; s.run(800)
    s.pitch = 0; s.run(500)
    s.pitch = -0.2; s.run(800)
    assert s.first() == [G.TURN_RIGHT, G.TILT_UP, G.TILT_DOWN]


def test_eyebrow_raise():
    s = Sim(); s.run(1000)
    s.brow = 0.5; s.run(800); s.brow = 0; s.run(500)
    assert s.first() == [G.RAISE_BROWS]


def test_looking_up_does_not_also_count_as_eyebrow_raise():
    s = Sim(); s.run(1000)
    s.gy, s.brow = 0.5, 0.5; s.run(800)
    assert s.first() == [G.LOOK_UP]


def test_works_at_low_frame_rate():
    s = Sim(fps=12); s.run(1000)
    s.gx = -0.7; s.run(900); s.gx = 0; s.run(600)
    s.blink_l = 0.85; s.run(900); s.open(); s.run(600)
    assert s.first() == [G.LOOK_LEFT, G.WINK_LEFT]


def test_swapped_eye_channels_are_handled_by_calibration():
    s = Sim(GestureEngine(EngineConfig(), Calibration(calibrated=True, swap_eyes=True)))
    s.run(1000)
    s.blink_r = 0.85; s.run(700); s.open(); s.run(800)
    assert s.first() == [G.WINK_LEFT]


def test_a_stalled_camera_does_not_invent_a_gesture():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(100)
    s.t += 3000
    s.open(); s.run(1000)
    assert s.events == []


def test_a_face_that_returns_just_after_the_reset_window_does_not_inherit_the_closure():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(400)
    s.face = False; s.run(340)
    s.face = True; s.open(); s.run(1000)
    assert s.events == []


def test_a_long_blink_followed_by_one_eye_staying_closed_is_not_also_a_wink():
    s = Sim(); s.run(1000)
    s.close_both(); s.run(1000)
    s.blink_r = 0.05; s.run(900)
    s.open(); s.run(800)
    assert s.first() == [G.LONG_BLINK]


def test_a_blink_during_a_held_look_does_not_fire_it_again():
    s = Sim(); s.run(1000)
    s.gx = -0.7; s.run(700)
    s.close_both(); s.gx = 0; s.run(200)
    s.open(); s.gx = -0.7; s.run(1200)
    assert s.first() == [G.LOOK_LEFT]


def test_a_momentary_diagonal_does_not_rearm_a_held_look():
    s = Sim(); s.run(1000)
    s.gx = -0.9; s.run(700)
    s.gy = 0.6; s.run(200)
    s.gy = 0; s.run(1000)
    assert s.first() == [G.LOOK_LEFT]


def test_a_gesture_ready_during_another_ones_cooldown_still_fires():
    s = Sim(); s.run(1000)
    s.gx, s.brow = 0.7, 0.5; s.run(2000)
    assert s.first() == [G.LOOK_RIGHT, G.RAISE_BROWS]


def test_a_changed_resting_head_pose_is_eventually_followed():
    s = Sim(GestureEngine(EngineConfig(enabled=frozenset({G.LOOK_LEFT})), Calibration(calibrated=True)))
    s.run(1000)
    s.yaw = 0.12
    s.gx = -0.7; s.run(1000)
    assert s.events == []
    s.gx = 0; s.run(100_000)
    s.gx = -0.7; s.run(900)
    assert s.first() == [G.LOOK_LEFT]


# ------------------------------------------------------------------------------------ calibration


def pose(c, step, blink_l=0.1, blink_r=0.1, gx=0.0, gy=0.0, yaw=0.0, pitch=0.0, brow=0.05):
    c.start_step()
    for i in range(40):
        c.add(Frame(i * 33, True, blink_l, blink_r, gx, gy, yaw, pitch, brow))
    assert c.finish_step(step)


def test_needs_enough_frames():
    c = Calibrator()
    c.start_step()
    for i in range(3):
        c.add(Frame(i * 33, True))
    assert not c.finish_step(CalStep.NEUTRAL)
    c.start_step()
    for i in range(40):
        c.add(Frame(i * 33, False))
    assert not c.finish_step(CalStep.NEUTRAL)


def test_without_resting_pose_nothing_changes():
    prev = Calibration(calibrated=True, swap_eyes=True)
    r = Calibrator(prev).build()
    assert r.calibration == prev and r.warnings


def test_learns_which_channel_is_which_eye():
    c = Calibrator()
    pose(c, CalStep.NEUTRAL)
    pose(c, CalStep.BOTH_CLOSED, blink_l=0.8, blink_r=0.85)
    pose(c, CalStep.WINK_LEFT, blink_l=0.1, blink_r=0.8)
    pose(c, CalStep.WINK_RIGHT, blink_l=0.8, blink_r=0.1)
    r = c.build()
    assert r.calibration.swap_eyes
    assert r.calibration.closed_l == pytest.approx(0.85)
    assert r.calibration.closed_r == pytest.approx(0.8)
    assert r.warnings == []
    e = GestureEngine(EngineConfig(), r.calibration)
    out, t = [], 0
    for ms, l, rr in [(1000, 0.1, 0.1), (700, 0.1, 0.85), (800, 0.1, 0.1)]:
        for _ in range(ms // 33):
            t += 33
            out += e.on_frame(Frame(t, True, l, rr))
    assert [x.gesture for x in out] == [G.WINK_LEFT]


def test_unclear_winks_produce_a_warning():
    c = Calibrator()
    pose(c, CalStep.NEUTRAL)
    pose(c, CalStep.WINK_LEFT, blink_l=0.7, blink_r=0.65)
    pose(c, CalStep.WINK_RIGHT, blink_l=0.7, blink_r=0.65)
    assert sum(1 for w in c.build().warnings if "wink" in w) == 2


def test_learns_flipped_gaze_directions():
    c = Calibrator()
    pose(c, CalStep.NEUTRAL, gx=0.02)
    pose(c, CalStep.LOOK_LEFT, gx=0.5)
    pose(c, CalStep.LOOK_RIGHT, gx=-0.45)
    pose(c, CalStep.LOOK_UP, gy=0.3)
    pose(c, CalStep.LOOK_DOWN, gy=-0.5)
    r = c.build()
    assert r.calibration.gaze_x.base == pytest.approx(0.02)
    e = GestureEngine(EngineConfig(), r.calibration)
    out, t = [], 0
    for ms, gx in [(1000, 0.02), (800, 0.5), (600, 0.02), (800, -0.45), (600, 0.02)]:
        for _ in range(ms // 33):
            t += 33
            out += e.on_frame(Frame(t, True, 0.05, 0.05, gx, 0.0))
    assert [x.gesture for x in out if not x.repeat] == [G.LOOK_LEFT, G.LOOK_RIGHT]


def test_weak_movement_falls_back_to_a_typical_range():
    c = Calibrator()
    pose(c, CalStep.NEUTRAL)
    pose(c, CalStep.LOOK_LEFT, gx=-0.02)
    pose(c, CalStep.LOOK_RIGHT, gx=0.5)
    r = c.build()
    assert r.warnings and r.calibration.gaze_x.neg == pytest.approx(-0.6)


def test_eye_only_run_keeps_the_head_ranges_usable():
    c = Calibrator()
    pose(c, CalStep.NEUTRAL, yaw=0.1, pitch=-0.55)
    pose(c, CalStep.BOTH_CLOSED, blink_l=0.8, blink_r=0.8)
    cal = c.build().calibration
    assert cal.pitch.base == pytest.approx(-0.55)
    assert cal.pitch.pos_span > 0 and cal.pitch.neg_span > 0
    n = Normalizer(cal)
    assert n.apply(Frame(0, True, pitch=-0.55 + cal.pitch.pos_span), False).hy == pytest.approx(1, abs=0.01)
    assert n.apply(Frame(33, True, pitch=-0.55 - cal.pitch.neg_span), False).hy == pytest.approx(-1, abs=0.01)


def test_calibration_survives_storage():
    c = Calibration(calibrated=True, swap_eyes=True, open_l=0.11, closed_l=0.81,
                    gaze_x=Axis(0.02, -0.5, 0.45), brow=Axis(0.1, 0.6, -0.4))
    assert Calibration.from_dict(c.to_dict()) == c
    assert Calibration.from_dict({}) == Calibration()
    assert Calibration.from_dict({"open_l": "garbage"}) == Calibration()


# ------------------------------------------------------------------------------------ features


def face(nose_x=0.5, nose_y=0.5, size=640):
    x, y = [0.0] * 478, [0.0] * 478
    x[33], y[33] = 0.40, 0.40
    x[263], y[263] = 0.60, 0.40
    x[1], y[1] = nose_x, nose_y
    x[13], y[13] = 0.50, 0.60
    x[14], y[14] = 0.50, 0.61
    return FaceLandmarks(x, y, size, size)


def test_head_pose_directions():
    centre = FeatureExtractor.head_pose(face())
    assert centre[0] == pytest.approx(0, abs=0.001)
    assert FeatureExtractor.head_pose(face(nose_x=0.45))[0] > 0.2
    assert FeatureExtractor.head_pose(face(nose_x=0.55))[0] < -0.2
    assert FeatureExtractor.head_pose(face(nose_y=0.55))[1] < centre[1]
    assert FeatureExtractor.head_pose(face(nose_y=0.45))[1] > centre[1]


def test_head_pose_ignores_roll():
    a = math.radians(20)
    base = face()
    x, y = [0.0] * 478, [0.0] * 478
    for i in (1, 13, 14, 33, 263):
        dx, dy = base.x[i] - 0.5, base.y[i] - 0.5
        x[i] = 0.5 + dx * math.cos(a) - dy * math.sin(a)
        y[i] = 0.5 + dx * math.sin(a) + dy * math.cos(a)
    p = FeatureExtractor.head_pose(FaceLandmarks(x, y, 640, 640))
    s = FeatureExtractor.head_pose(base)
    assert p[0] == pytest.approx(s[0], abs=0.01) and p[1] == pytest.approx(s[1], abs=0.01)


def test_tiny_faces_are_rejected():
    assert FeatureExtractor.head_pose(face(size=50)) is None
    assert not FeatureExtractor.frame(5, lambda k: 0.0, face(size=50)).face
    assert not FeatureExtractor.frame(5, lambda k: 0.0, None).face


def test_blendshapes_become_signed_signals():
    scores = {"eyeBlinkLeft": 0.8, "eyeBlinkRight": 0.1, "eyeLookOutRight": 0.6, "eyeLookInLeft": 0.6,
              "eyeLookUpLeft": 0.1, "eyeLookUpRight": 0.1, "eyeLookDownLeft": 0.5, "eyeLookDownRight": 0.3,
              "browInnerUp": 0.3, "browOuterUpLeft": 0.6, "browOuterUpRight": 0.0}
    f = FeatureExtractor.frame(7, lambda k: scores.get(k, 0.0), face())
    assert f.face
    assert (f.blink_l, f.blink_r) == (0.8, 0.1)
    assert f.gaze_x == pytest.approx(0.6) and f.gaze_y == pytest.approx(-0.3) and f.brow == pytest.approx(0.3)
