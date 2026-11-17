"""EySnatch for Windows, macOS and Linux: control the computer with your eyes through the webcam.

Tkinter UI (ships with Python, so the packaged app stays small). Screens mirror the Android app:
home (start/stop, status, last gestures), Live view, Calibrate, and Gestures & settings.
"""
from __future__ import annotations

import base64
import logging
import queue
import sys
import time
import tkinter as tk
from tkinter import messagebox, ttk
from typing import List, Optional

from . import __version__
from .actions import IS_WIN, ActionRunner, PCAction
from .core import CalStep, Calibrator, Frame, Gesture, GestureEngine
from .settings import Settings
from .tracker import Tracker

log = logging.getLogger("eysnatch")

POLL_MS = 33
READY_MS = 1800
CAPTURE_MS = 2600


def photo_from_bgr(img) -> Optional[tk.PhotoImage]:
    import cv2

    ok, png = cv2.imencode(".png", img)
    if not ok:
        return None
    return tk.PhotoImage(data=base64.b64encode(png.tobytes()))


class Hud:
    """The small label that confirms each action. It must never take the keyboard focus, otherwise the
    next shortcut would go to EySnatch instead of the app the user is looking at."""

    def __init__(self, root: tk.Tk):
        self.root = root
        self.win: Optional[tk.Toplevel] = None
        self.label: Optional[tk.Label] = None
        self._hide_job = None

    def _create(self):
        w = tk.Toplevel(self.root)
        w.withdraw()
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        try:
            w.attributes("-alpha", 0.9)
        except tk.TclError:
            pass
        self.label = tk.Label(w, bg="#101820", fg="white", font=("Segoe UI", 15), padx=18, pady=10)
        self.label.pack()
        if IS_WIN:
            try:
                import ctypes

                w.update_idletasks()
                hwnd = ctypes.windll.user32.GetParent(w.winfo_id()) or w.winfo_id()
                GWL_EXSTYLE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TRANSPARENT = -20, 0x08000000, 0x80, 0x20
                style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
                ctypes.windll.user32.SetWindowLongW(hwnd, GWL_EXSTYLE, style | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TRANSPARENT)
            except Exception as e:  # pragma: no cover
                log.warning("hud style: %s", e)
        self.win = w

    def show(self, text: str):
        if self.win is None:
            self._create()
        self.label.configure(text=text)
        self.win.update_idletasks()
        sw, sh = self.win.winfo_screenwidth(), self.win.winfo_screenheight()
        ww, wh = self.win.winfo_reqwidth(), self.win.winfo_reqheight()
        self.win.geometry(f"+{(sw - ww) // 2}+{sh - wh - 120}")
        self.win.deiconify()
        self.win.lift()
        if self._hide_job:
            self.root.after_cancel(self._hide_job)
        self._hide_job = self.root.after(900, self.win.withdraw)


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.settings = Settings()
        self.tracker = Tracker(self.settings)
        self.runner = ActionRunner()
        self.hud = Hud(root)
        self.paused = False
        self.calibrating = False
        self.live_win: Optional["LiveWindow"] = None
        self.cal_win: Optional["CalibrateWindow"] = None
        self.settings_win: Optional["SettingsWindow"] = None
        self.recent: List[str] = []

        root.title("EySnatch")
        root.minsize(440, 520)
        root.protocol("WM_DELETE_WINDOW", self.quit)
        self._build()
        self.root.after(POLL_MS, self._poll)

    # ------------------------------------------------------------------ layout

    def _build(self):
        f = ttk.Frame(self.root, padding=16)
        f.pack(fill="both", expand=True)
        ttk.Label(f, text="EySnatch", font=("Segoe UI", 20, "bold")).pack(anchor="w")
        ttk.Label(f, text="Control your computer hands-free with your eyes.", foreground="#555").pack(anchor="w", pady=(0, 10))

        self.status_var = tk.StringVar(value="Stopped")
        self.detail_var = tk.StringVar(value="")
        box = ttk.LabelFrame(f, text="Status", padding=10)
        box.pack(fill="x")
        ttk.Label(box, textvariable=self.status_var, font=("Segoe UI", 12, "bold"), wraplength=380).pack(anchor="w")
        ttk.Label(box, textvariable=self.detail_var, foreground="#555", wraplength=380).pack(anchor="w")

        self.cal_hint = ttk.Label(f, text="", foreground="#b25b00", wraplength=400)
        self.cal_hint.pack(anchor="w", pady=(8, 0))

        btns = ttk.Frame(f)
        btns.pack(fill="x", pady=10)
        self.start_btn = ttk.Button(btns, text="Start eye control", command=self.toggle_running)
        self.start_btn.pack(fill="x", ipady=6)
        self.pause_btn = ttk.Button(btns, text="Pause", command=self.toggle_pause, state="disabled")
        self.pause_btn.pack(fill="x", pady=(6, 0))
        row = ttk.Frame(btns)
        row.pack(fill="x", pady=(6, 0))
        ttk.Button(row, text="Calibrate", command=self.open_calibrate).pack(side="left", expand=True, fill="x")
        ttk.Button(row, text="Live view", command=self.open_live).pack(side="left", expand=True, fill="x", padx=6)
        ttk.Button(row, text="Gestures & settings", command=self.open_settings).pack(side="left", expand=True, fill="x")

        ttk.Label(f, text="Recent gestures", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(6, 2))
        self.recent_list = tk.Listbox(f, height=8, activestyle="none", highlightthickness=0)
        self.recent_list.pack(fill="both", expand=True)
        ttk.Label(f, text=f"v{__version__}  ·  Camera images stay on this computer.", foreground="#888").pack(anchor="w", pady=(8, 0))
        self._refresh_cal_hint()

    def _refresh_cal_hint(self):
        self.cal_hint.configure(
            text="" if self.settings.calibration.calibrated
            else "Tip: run Calibrate once (about 45 s) so EySnatch learns how your eyes look to your camera.")

    # ------------------------------------------------------------------ actions

    def toggle_running(self):
        if self.tracker.running:
            self.tracker.stop()
            self.paused = False
        else:
            self.tracker.start()

    def toggle_pause(self):
        self.paused = not self.paused
        self.note("EySnatch paused" if self.paused else "EySnatch resumed")
        if self.settings.hud:
            self.hud.show("EySnatch paused" if self.paused else "EySnatch resumed")

    def note(self, text: str):
        stamp = time.strftime("%H:%M:%S")
        self.recent.insert(0, f"{stamp}  {text}")
        del self.recent[12:]
        self.recent_list.delete(0, "end")
        for r in self.recent:
            self.recent_list.insert("end", r)

    @property
    def test_only(self) -> bool:
        """While Live view or Calibrate is open gestures are only shown, like on the phone."""
        return self.calibrating or self.live_win is not None

    def route(self, e):
        action = self.settings.action(e.gesture)
        if action is PCAction.NONE or (e.repeat and not action.repeatable):
            return
        if self.paused and action is not PCAction.TOGGLE_PAUSE:
            return
        test = self.test_only
        if not e.repeat:
            self.note(f"{e.gesture.label}  ->  {action.label}" + ("  (test: not run)" if test else ""))
        if test:
            return
        if action is PCAction.TOGGLE_PAUSE:
            self.toggle_pause()
            return
        if self.settings.hud and not e.repeat:
            self.hud.show(f"{e.gesture.label}  ->  {action.label}")
        self.runner.perform(action)
        if self.runner.error:
            self.note(self.runner.error)
            self.runner.error = None

    # ------------------------------------------------------------------ loop

    def _poll(self):
        try:
            while True:
                self.route(self.tracker.events.get_nowait())
        except queue.Empty:
            pass
        t = self.tracker
        running = t.running
        self.start_btn.configure(text="Stop eye control" if running else "Start eye control")
        self.pause_btn.configure(state="normal" if running else "disabled", text="Resume" if self.paused else "Pause")
        if running and self.paused:
            self.status_var.set("Paused - keep your eyes shut for 2 s to resume")
        else:
            self.status_var.set(t.status)
        self.detail_var.set(f"{t.fps:.0f} frames per second" if running and t.fps else "")
        if self.live_win:
            self.live_win.refresh()
        if self.cal_win:
            self.cal_win.tick()
        self.root.after(POLL_MS, self._poll)

    # ------------------------------------------------------------------ windows

    def open_live(self):
        if self.live_win:
            self.live_win.win.lift()
            return
        self.live_win = LiveWindow(self)
        if not self.tracker.running:
            self.tracker.start()

    def open_calibrate(self):
        if self.cal_win:
            self.cal_win.win.lift()
            return
        self.cal_win = CalibrateWindow(self)

    def open_settings(self):
        if self.settings_win:
            self.settings_win.win.lift()
            return
        self.settings_win = SettingsWindow(self)

    def quit(self):
        self.tracker.stop()
        self.root.destroy()


# ---------------------------------------------------------------------------------------- live view


BARS = [
    ("Left eye closed", lambda n: n.close_l, GestureEngine.CLOSE_ON),
    ("Right eye closed", lambda n: n.close_r, GestureEngine.CLOSE_ON),
    ("Look left", lambda n: -n.gx, None),
    ("Look right", lambda n: n.gx, None),
    ("Look up", lambda n: n.gy, None),
    ("Look down", lambda n: -n.gy, None),
    ("Eyebrows", lambda n: n.brow, None),
    ("Head left", lambda n: -n.hx, None),
    ("Head right", lambda n: n.hx, None),
    ("Head up", lambda n: n.hy, None),
    ("Head down", lambda n: -n.hy, None),
]


class LiveWindow:
    def __init__(self, app: App):
        self.app = app
        self.win = tk.Toplevel(app.root)
        self.win.title("EySnatch - Live view")
        self.win.protocol("WM_DELETE_WINDOW", self.close)
        app.tracker.want_preview = True
        f = ttk.Frame(self.win, padding=10)
        f.pack(fill="both", expand=True)
        ttk.Label(f, text="Gestures are only shown here, not performed. Close this window to use them.",
                  foreground="#555", wraplength=360).pack(anchor="w")
        self.image = ttk.Label(f)
        self.image.pack(pady=6)
        self.canvas = tk.Canvas(f, width=360, height=len(BARS) * 22 + 6, highlightthickness=0, bg="#fafafa")
        self.canvas.pack()
        self._photo = None

    def refresh(self):
        t = self.app.tracker
        if t.preview is not None:
            self._photo = photo_from_bgr(t.preview)
            if self._photo:
                self.image.configure(image=self._photo)
        n = t.norm
        trig = min(0.95, max(0.3, GestureEngine.TRIGGER / self.app.settings.sensitivity))
        c = self.canvas
        c.delete("all")
        x0, width = 120, 230
        for i, (name, fn, line) in enumerate(BARS):
            y = 4 + i * 22
            v = max(0.0, min(1.5, fn(n))) if n.face else 0.0
            lim = line if line is not None else trig
            c.create_text(4, y + 9, text=name, anchor="w", font=("Segoe UI", 9))
            c.create_rectangle(x0, y + 2, x0 + width, y + 16, outline="#ccc", fill="#eee")
            c.create_rectangle(x0, y + 2, x0 + int(width * min(v, 1.5) / 1.5), y + 16, outline="",
                               fill="#2e9d5b" if v >= lim else "#5a8dee")
            lx = x0 + int(width * lim / 1.5)
            c.create_line(lx, y, lx, y + 18, fill="#d33", width=2)

    def close(self):
        self.app.tracker.want_preview = False
        self.app.live_win = None
        self.win.destroy()


# ---------------------------------------------------------------------------------------- calibration


class CalibrateWindow:
    """Same wizard as CalibrateActivity: wait for the face, then for each pose 'get ready' + capture."""

    def __init__(self, app: App):
        self.app = app
        self.win = tk.Toplevel(app.root)
        self.win.title("EySnatch - Calibrate")
        self.win.minsize(460, 300)
        self.win.protocol("WM_DELETE_WINDOW", self.abort)
        self.phase = "INTRO"
        self.steps: List[CalStep] = []
        self.index = 0
        self.retries = 0
        self.phase_start = 0.0
        self.waiting_since = 0.0
        self.started_tracker = False
        self.calibrator: Optional[Calibrator] = None
        self.result = None

        f = ttk.Frame(self.win, padding=16)
        f.pack(fill="both", expand=True)
        self.title = ttk.Label(f, text="Calibrate", font=("Segoe UI", 16, "bold"), wraplength=440)
        self.title.pack(anchor="w")
        self.prompt = ttk.Label(f, text="", font=("Segoe UI", 12), wraplength=440, justify="left")
        self.prompt.pack(anchor="w", pady=8)
        self.detail = ttk.Label(f, text="", foreground="#555", wraplength=440)
        self.detail.pack(anchor="w")
        self.progress = ttk.Progressbar(f, maximum=100)
        self.progress.pack(fill="x", pady=10)
        self.buttons = ttk.Frame(f)
        self.buttons.pack(fill="x", side="bottom")
        self._intro()

    def _set_buttons(self, *spec):
        for w in self.buttons.winfo_children():
            w.destroy()
        for text, cmd in spec:
            ttk.Button(self.buttons, text=text, command=cmd).pack(side="left", expand=True, fill="x", padx=2)

    def _intro(self):
        self.title.configure(text="Calibrate")
        self.prompt.configure(text=(
            "Sit in front of the camera as you normally do. EySnatch will ask you for a few eye movements and "
            "learn how your eyes look to your camera, so gestures are reliable and left/right are never mixed up.\n\n"
            "A short beep marks the start and end of each pose, so you do not need to read the screen while "
            "you look away."))
        self._set_buttons(("Start: eyes (about 45 s)", lambda: self.begin(False)),
                          ("Eyes and head turns (about 70 s)", lambda: self.begin(True)),
                          ("Close", self.abort))

    def begin(self, with_head: bool):
        self.steps = [CalStep.NEUTRAL, CalStep.BOTH_CLOSED, CalStep.WINK_LEFT, CalStep.WINK_RIGHT,
                      CalStep.LOOK_LEFT, CalStep.LOOK_RIGHT, CalStep.LOOK_UP, CalStep.LOOK_DOWN, CalStep.BROWS]
        if with_head:
            self.steps += [CalStep.TURN_LEFT, CalStep.TURN_RIGHT, CalStep.TILT_UP, CalStep.TILT_DOWN]
        self.calibrator = Calibrator(self.app.settings.calibration)
        self.index = 0
        self.retries = 0
        self.app.calibrating = True
        self.app.tracker.frame_listener = self._on_frame
        if not self.app.tracker.running:
            self.started_tracker = True
            self.app.tracker.start()
        self.phase = "WAIT_FACE"
        self.waiting_since = time.monotonic()
        self.title.configure(text="Getting ready")
        self.prompt.configure(text="Look at the camera so I can find your face.")
        self._set_buttons(("Cancel", self.abort))

    def _on_frame(self, f: Frame):
        # Tracker thread. The calibrator is only appended to here while capturing; list.append is atomic.
        if self.phase == "CAPTURE" and self.calibrator is not None:
            self.calibrator.add(f)

    def _ready(self):
        step = self.steps[self.index]
        self.phase = "READY"
        self.phase_start = time.monotonic()
        self.title.configure(text=f"{self.index + 1} of {len(self.steps)}:  {step.title}")
        self.prompt.configure(text=step.prompt)
        self.progress.configure(value=0)
        spec = [("Cancel", self.abort)]
        if step is not CalStep.NEUTRAL:
            spec.insert(0, ("Skip this pose", self._skip))
        self._set_buttons(*spec)

    def _skip(self):
        self.retries = 0
        self.index += 1
        self._next()

    def tick(self):
        now = time.monotonic()
        t = self.app.tracker
        if self.phase == "WAIT_FACE":
            if t.running and t.frame is not None and t.frame.face:
                self._ready()
            elif not t.running and now - self.waiting_since > 1:
                self.detail.configure(text=f"The camera did not start: {t.error or 'no reason given'}")
            else:
                self.detail.configure(text="Looking for your face..." if t.running else "Starting the camera...")
        elif self.phase == "READY":
            left = READY_MS - (now - self.phase_start) * 1000
            if left <= 0:
                self.calibrator.start_step()
                self.phase = "CAPTURE"
                self.phase_start = now
                self.detail.configure(text="Hold it!")
                self.win.bell()
            else:
                self.detail.configure(text=f"Get ready...  {int(left / 1000) + 1}")
        elif self.phase == "CAPTURE":
            elapsed = (now - self.phase_start) * 1000
            self.progress.configure(value=min(100, 100 * elapsed / CAPTURE_MS))
            if elapsed >= CAPTURE_MS:
                self.phase = "BUSY"
                self.win.bell()
                self._finish_step()

    def _finish_step(self):
        step = self.steps[self.index]
        if self.calibrator.finish_step(step):
            self.index += 1
            self.retries = 0
            self._next()
            return
        self.retries += 1
        if step is CalStep.NEUTRAL and self.retries >= 3:
            messagebox.showwarning("EySnatch", "I could not see your face. Check the light and try again.", parent=self.win)
            self.abort()
        elif self.retries >= 3:
            self._skip()
        else:
            self._ready()
            self.detail.configure(text="I lost your face for a moment, so let's repeat that pose.")

    def _next(self):
        if self.index >= len(self.steps):
            self._review()
        else:
            self._ready()

    def _review(self):
        self.result = self.calibrator.build()
        self.phase = "REVIEW"
        self._end_capture()
        lines = [f"OK:  {r}" for r in self.result.report] + [f"Note:  {w}" for w in self.result.warnings]
        self.title.configure(text="Calibration finished")
        self.prompt.configure(text="\n".join(lines) or "Nothing to report.", font=("Segoe UI", 10))
        self.detail.configure(text="Save it, then open Live view to check that every gesture responds.")
        self.progress.configure(value=100)
        self._set_buttons(("Save", self._save), ("Discard", self.abort))

    def _save(self):
        self.app.settings.calibration = self.result.calibration
        self.app.settings.save()
        self.app._refresh_cal_hint()
        self.app.note("Calibration saved")
        self.close()

    def _end_capture(self):
        self.app.tracker.frame_listener = None
        self.app.calibrating = False
        if self.started_tracker:
            self.started_tracker = False
            self.app.tracker.stop()

    def abort(self):
        self._end_capture()
        self.close()

    def close(self):
        self.app.cal_win = None
        self.win.destroy()


# ---------------------------------------------------------------------------------------- settings


class SettingsWindow:
    def __init__(self, app: App):
        self.app = app
        s = app.settings
        self.win = tk.Toplevel(app.root)
        self.win.title("EySnatch - Gestures & settings")
        self.win.protocol("WM_DELETE_WINDOW", self.close)
        f = ttk.Frame(self.win, padding=14)
        f.pack(fill="both", expand=True)

        labels = [a.label for a in PCAction]
        self.choices = {}
        grid = ttk.LabelFrame(f, text="What each gesture does", padding=8)
        grid.pack(fill="x")
        for r, g in enumerate(Gesture):
            ttk.Label(grid, text=g.label).grid(row=r, column=0, sticky="w", padx=(0, 10), pady=1)
            var = tk.StringVar(value=s.action(g).label)
            cb = ttk.Combobox(grid, textvariable=var, values=labels, state="readonly", width=28)
            cb.grid(row=r, column=1, sticky="ew", pady=1)
            cb.bind("<<ComboboxSelected>>", lambda _e: self._apply())
            self.choices[g] = var

        opts = ttk.LabelFrame(f, text="Tuning", padding=8)
        opts.pack(fill="x", pady=8)
        self.sens = tk.DoubleVar(value=s.sensitivity)
        self.hold = tk.DoubleVar(value=s.hold_scale)
        self._slider(opts, 0, "Sensitivity", self.sens, 0.5, 2.0, "smaller movements trigger")
        self._slider(opts, 1, "Hold time", self.hold, 0.5, 2.5, "longer holds needed")
        ttk.Label(opts, text="Camera number").grid(row=2, column=0, sticky="w")
        self.cam = tk.IntVar(value=s.camera_index)
        ttk.Spinbox(opts, from_=0, to=9, textvariable=self.cam, width=5, command=self._apply).grid(row=2, column=1, sticky="w")
        self.saver = tk.BooleanVar(value=s.battery_saver)
        self.hud = tk.BooleanVar(value=s.hud)
        ttk.Checkbutton(opts, text="Battery saver (checks your eyes less often)", variable=self.saver, command=self._apply).grid(row=3, column=0, columnspan=3, sticky="w")
        ttk.Checkbutton(opts, text="Show a label for each action", variable=self.hud, command=self._apply).grid(row=4, column=0, columnspan=3, sticky="w")

        row = ttk.Frame(f)
        row.pack(fill="x")
        ttk.Button(row, text="Reset gestures", command=self._reset_mapping).pack(side="left")
        ttk.Button(row, text="Forget calibration", command=self._reset_cal).pack(side="left", padx=6)
        ttk.Button(row, text="Close", command=self.close).pack(side="right")

    def _slider(self, parent, row, name, var, lo, hi, hint):
        ttk.Label(parent, text=name).grid(row=row, column=0, sticky="w")
        val = ttk.Label(parent, width=5)
        val.grid(row=row, column=2, sticky="w")

        def changed(_v=None):
            val.configure(text=f"{var.get():.2f}")

        scale = ttk.Scale(parent, from_=lo, to=hi, variable=var, command=changed, length=200)
        scale.grid(row=row, column=1, sticky="ew")
        scale.bind("<ButtonRelease-1>", lambda _e: self._apply())
        scale.bind("<KeyRelease>", lambda _e: self._apply())
        changed()

    def _apply(self):
        if not self.win.winfo_exists():
            return
        s = self.app.settings
        by_label = {a.label: a for a in PCAction}
        s.mapping = {g: by_label.get(v.get(), s.action(g)) for g, v in self.choices.items()}
        s.sensitivity = round(float(self.sens.get()), 2)
        s.hold_scale = round(float(self.hold.get()), 2)
        cam_changed = int(self.cam.get()) != s.camera_index
        s.camera_index = int(self.cam.get())
        s.battery_saver = bool(self.saver.get())
        s.hud = bool(self.hud.get())
        s.save()
        if cam_changed and self.app.tracker.running:
            self.app.tracker.stop()
            self.app.tracker.start()

    def _reset_mapping(self):
        from .actions import DEFAULT_MAPPING

        for g, v in self.choices.items():
            v.set(DEFAULT_MAPPING[g].label)
        self._apply()

    def _reset_cal(self):
        from .core import Calibration

        if messagebox.askyesno("EySnatch", "Forget the calibration and use typical values?", parent=self.win):
            self.app.settings.calibration = Calibration()
            self.app.settings.save()
            self.app._refresh_cal_hint()

    def close(self):
        self.app.settings_win = None
        self.win.destroy()


def _set_icon(root: tk.Tk) -> None:
    from pathlib import Path

    here = Path(__file__).resolve().parent
    for p in (Path(getattr(sys, "_MEIPASS", here)) / "eysnatch.png", here.parent / "assets" / "eysnatch.png"):
        if p.is_file():
            try:
                root._icon = tk.PhotoImage(file=str(p))
                root.iconphoto(True, root._icon)
            except tk.TclError:
                pass
            return


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if IS_WIN:
        try:  # crisp text on high-DPI screens
            import ctypes

            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    root = tk.Tk()
    _set_icon(root)
    try:
        ttk.Style(root).theme_use("vista" if IS_WIN else ("aqua" if sys.platform == "darwin" else "clam"))
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
