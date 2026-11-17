"""What a gesture does on a computer: keyboard shortcuts, mouse wheel and media keys.

The Android app uses the accessibility service for Back/Home/swipes; on a PC the equivalent is sending
the usual shortcuts of the operating system to whatever window is in front.
"""
from __future__ import annotations

import enum
import logging
import subprocess
import sys
import threading
from typing import Dict, Optional

from .core import Gesture

log = logging.getLogger("eysnatch")

IS_WIN = sys.platform.startswith("win")
IS_MAC = sys.platform == "darwin"


class PCAction(enum.Enum):
    NONE = ("Do nothing", False)
    NEXT = ("Next (Right arrow)", False)
    PREVIOUS = ("Previous (Left arrow)", False)
    SCROLL_DOWN = ("Scroll down", True)
    SCROLL_UP = ("Scroll up", True)
    PAGE_DOWN = ("Page down", False)
    PAGE_UP = ("Page up", False)
    BACK = ("Back (browser / folder)", False)
    FORWARD = ("Forward (browser / folder)", False)
    SHOW_DESKTOP = ("Show desktop", False)
    TASK_VIEW = ("All windows (Task view)", False)
    SWITCH_APP = ("Switch to previous app", False)
    SCREENSHOT = ("Screenshot", False)
    LEFT_CLICK = ("Mouse click", False)
    PLAY_PAUSE = ("Play / pause media", False)
    VOLUME_UP = ("Volume up", True)
    VOLUME_DOWN = ("Volume down", True)
    MUTE = ("Mute / unmute", False)
    LOCK_SCREEN = ("Lock the computer", False)
    TOGGLE_PAUSE = ("Pause / resume EySnatch", False)

    def __init__(self, label: str, repeatable: bool):
        self.label = label
        self.repeatable = repeatable

    @staticmethod
    def by_name(name: Optional[str], default: "PCAction") -> "PCAction":
        try:
            return PCAction[name] if name else default
        except KeyError:
            return default


DEFAULT_MAPPING: Dict[Gesture, PCAction] = {
    Gesture.LOOK_LEFT: PCAction.PREVIOUS,
    Gesture.LOOK_RIGHT: PCAction.NEXT,
    Gesture.LOOK_UP: PCAction.SCROLL_UP,
    Gesture.LOOK_DOWN: PCAction.SCROLL_DOWN,
    Gesture.DOUBLE_BLINK: PCAction.BACK,
    Gesture.LONG_BLINK: PCAction.SHOW_DESKTOP,
    Gesture.EYES_SHUT: PCAction.TOGGLE_PAUSE,
    Gesture.WINK_LEFT: PCAction.SCREENSHOT,
    Gesture.WINK_RIGHT: PCAction.SWITCH_APP,
    Gesture.RAISE_BROWS: PCAction.PAGE_DOWN,
    Gesture.TURN_LEFT: PCAction.NONE,
    Gesture.TURN_RIGHT: PCAction.NONE,
    Gesture.TILT_UP: PCAction.NONE,
    Gesture.TILT_DOWN: PCAction.NONE,
}


class ActionRunner:
    """Sends the input events. pynput is imported lazily so the rest of the app (and the tests) work on
    machines without a display."""

    SCROLL_NOTCHES = 3

    def __init__(self):
        self._kb = None
        self._mouse = None
        self._keys = None
        self._buttons = None
        self._lock = threading.Lock()
        self.error: Optional[str] = None

    def _ensure(self) -> bool:
        if self._kb is not None:
            return True
        try:
            from pynput import keyboard, mouse  # noqa: WPS433 (lazy on purpose)
            self._kb = keyboard.Controller()
            self._mouse = mouse.Controller()
            self._keys = keyboard.Key
            self._buttons = mouse.Button
            return True
        except Exception as e:  # pragma: no cover - depends on the desktop session
            self.error = f"Cannot send keys or mouse events: {e}"
            log.error(self.error)
            return False

    def _combo(self, *keys) -> None:
        kb = self._kb
        for k in keys:
            kb.press(k)
        for k in reversed(keys):
            kb.release(k)

    def perform(self, action: PCAction) -> None:
        if action in (PCAction.NONE, PCAction.TOGGLE_PAUSE):
            return
        with self._lock:
            if not self._ensure():
                return
            try:
                self._perform(action)
            except Exception as e:  # pragma: no cover
                log.warning("action %s failed: %s", action.name, e)

    def _perform(self, a: PCAction) -> None:  # noqa: C901 - a flat table is clearest here
        K = self._keys
        cmd = K.cmd  # Windows key / Command key / Super key
        alt = K.alt
        if a is PCAction.NEXT:
            self._combo(K.right)
        elif a is PCAction.PREVIOUS:
            self._combo(K.left)
        elif a is PCAction.SCROLL_DOWN:
            self._mouse.scroll(0, -self.SCROLL_NOTCHES)
        elif a is PCAction.SCROLL_UP:
            self._mouse.scroll(0, self.SCROLL_NOTCHES)
        elif a is PCAction.PAGE_DOWN:
            self._combo(K.page_down)
        elif a is PCAction.PAGE_UP:
            self._combo(K.page_up)
        elif a is PCAction.BACK:
            self._combo(cmd, "[") if IS_MAC else self._combo(alt, K.left)
        elif a is PCAction.FORWARD:
            self._combo(cmd, "]") if IS_MAC else self._combo(alt, K.right)
        elif a is PCAction.SHOW_DESKTOP:
            self._combo(cmd, K.f3) if IS_MAC else self._combo(cmd, "d")
        elif a is PCAction.TASK_VIEW:
            self._combo(K.ctrl, K.up) if IS_MAC else (self._combo(cmd, K.tab) if IS_WIN else self._combo(cmd))
        elif a is PCAction.SWITCH_APP:
            self._combo(cmd, K.tab) if IS_MAC else self._combo(alt, K.tab)
        elif a is PCAction.SCREENSHOT:
            # Windows: Win+PrintScreen saves straight to Pictures\Screenshots without any window.
            if IS_MAC:
                self._combo(cmd, K.shift, "3")
            elif IS_WIN:
                self._combo(cmd, K.print_screen)
            else:
                self._combo(K.print_screen)
        elif a is PCAction.LEFT_CLICK:
            self._mouse.click(self._buttons.left)
        elif a is PCAction.PLAY_PAUSE:
            self._combo(K.media_play_pause)
        elif a is PCAction.VOLUME_UP:
            self._combo(K.media_volume_up)
        elif a is PCAction.VOLUME_DOWN:
            self._combo(K.media_volume_down)
        elif a is PCAction.MUTE:
            self._combo(K.media_volume_mute)
        elif a is PCAction.LOCK_SCREEN:
            self._lock_screen()

    def _lock_screen(self) -> None:
        if IS_WIN:
            # Win+L cannot be synthesised; this is the documented API for it.
            import ctypes
            ctypes.windll.user32.LockWorkStation()
        elif IS_MAC:
            self._combo(self._keys.ctrl, self._keys.cmd, "q")
        else:
            for cmd in (["loginctl", "lock-session"], ["xdg-screensaver", "lock"]):
                try:
                    if subprocess.run(cmd, timeout=5).returncode == 0:
                        return
                except (OSError, subprocess.SubprocessError):
                    continue
