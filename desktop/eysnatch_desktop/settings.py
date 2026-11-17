"""User settings, stored as JSON in the usual per-user config folder."""
from __future__ import annotations

import json
import logging
import os
import sys
import threading
from pathlib import Path
from typing import Dict

from .actions import DEFAULT_MAPPING, PCAction
from .core import Calibration, EngineConfig, Gesture

log = logging.getLogger("eysnatch")


def config_dir() -> Path:
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
        return base / "EySnatch"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "EySnatch"
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "eysnatch"


class Settings:
    """Every change bumps `version`, so the running tracker reloads what it needs."""

    def __init__(self, path: Path = None):
        self.path = Path(path) if path else config_dir() / "settings.json"
        self._lock = threading.Lock()
        self.version = 0
        self.mapping: Dict[Gesture, PCAction] = dict(DEFAULT_MAPPING)
        self.sensitivity = 1.0
        self.hold_scale = 1.0
        self.camera_index = 0
        self.battery_saver = False
        self.hud = True
        self.calibration = Calibration()
        self.load()

    # ------------------------------------------------------------------ persistence

    def load(self) -> None:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except (OSError, ValueError) as e:
            log.warning("settings unreadable (%s); using defaults", e)
            return
        m = data.get("mapping", {}) or {}
        self.mapping = {g: PCAction.by_name(m.get(g.name), DEFAULT_MAPPING[g]) for g in Gesture}
        self.sensitivity = _num(data.get("sensitivity"), 1.0, 0.5, 2.0)
        self.hold_scale = _num(data.get("hold_scale"), 1.0, 0.5, 2.5)
        self.camera_index = int(_num(data.get("camera_index"), 0, 0, 16))
        self.battery_saver = bool(data.get("battery_saver", False))
        self.hud = bool(data.get("hud", True))
        self.calibration = Calibration.from_dict(data.get("calibration"))

    def save(self) -> None:
        data = {
            "mapping": {g.name: a.name for g, a in self.mapping.items()},
            "sensitivity": self.sensitivity,
            "hold_scale": self.hold_scale,
            "camera_index": self.camera_index,
            "battery_saver": self.battery_saver,
            "hud": self.hud,
            "calibration": self.calibration.to_dict(),
        }
        with self._lock:
            self.version += 1
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                tmp = self.path.with_suffix(".tmp")
                tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
                os.replace(tmp, self.path)
            except OSError as e:
                log.error("could not save settings: %s", e)

    # ------------------------------------------------------------------ derived

    def action(self, g: Gesture) -> PCAction:
        return self.mapping.get(g, DEFAULT_MAPPING[g])

    def engine_config(self) -> EngineConfig:
        return EngineConfig(
            sensitivity=self.sensitivity,
            hold_scale=self.hold_scale,
            enabled=frozenset(g for g in Gesture if self.action(g) is not PCAction.NONE),
        )


def _num(v, default, lo, hi):
    try:
        x = float(v)
    except (TypeError, ValueError):
        return default
    return min(hi, max(lo, x))
