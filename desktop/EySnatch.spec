# PyInstaller recipe for the desktop app:  pyinstaller --noconfirm EySnatch.spec
# Produces dist/EySnatch/ (a folder with EySnatch.exe on Windows). A folder build starts much faster
# than a one-file build, because MediaPipe does not have to be unpacked on every launch.
import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs, collect_submodules

here = Path(SPECPATH)
model = here.parent / "app" / "src" / "main" / "assets" / "face_landmarker.task"

datas = [(str(model), "."), (str(here / "assets" / "eysnatch.png"), ".")] + collect_data_files("mediapipe", excludes=["**/*.pyc", "**/testdata/**"])
binaries = collect_dynamic_libs("mediapipe")
# pynput picks its backend at import time, so name it explicitly (collecting it needs a display on Linux).
backend = {"win32": "_win32", "darwin": "_darwin"}.get(sys.platform, "_xorg")
hidden = collect_submodules("mediapipe.tasks.python") + [
    f"pynput.keyboard.{backend}", f"pynput.mouse.{backend}", f"pynput._util.{backend}",
]

a = Analysis(
    [str(here / "run_eysnatch.py")],
    pathex=[str(here)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hidden,
    excludes=["IPython", "pytest", "PIL.ImageQt", "PyQt5", "PySide6"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="EySnatch",
    console=False,
    icon=str(here / "assets" / ("eysnatch.png" if sys.platform == "darwin" else "eysnatch.ico")) if sys.platform != "linux" else None,
)
coll = COLLECT(exe, a.binaries, a.datas, name="EySnatch")
if sys.platform == "darwin":
    app = BUNDLE(
        coll,
        name="EySnatch.app",
        bundle_identifier="com.eysnatch.desktop",
        info_plist={"NSCameraUsageDescription": "EySnatch watches your eyes to turn glances and blinks into actions."},
    )
