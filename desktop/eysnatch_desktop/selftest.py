"""`EySnatch --selftest report.txt`: checks a packaged build without a camera (used by CI)."""


def selftest(report_path: str) -> int:
    """Used by CI on the packaged app: the face model loads and runs, and the input backend imports."""
    lines, ok = [], True
    try:
        import numpy as np
        import mediapipe as mp
        from eysnatch_desktop.tracker import create_landmarker

        lm = create_landmarker(video=True)
        lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.zeros((480, 640, 3), np.uint8)), 1)
        lm.close()
        lines.append("model: ok")
    except Exception as e:
        ok = False
        lines.append(f"model: FAILED {e!r}")
    try:
        import pynput.keyboard  # noqa: F401
        import pynput.mouse  # noqa: F401
        lines.append("input: ok")
    except Exception as e:
        ok = False
        lines.append(f"input: FAILED {e!r}")
    try:
        import cv2  # noqa: F401
        lines.append("camera library: ok")
    except Exception as e:
        ok = False
        lines.append(f"camera library: FAILED {e!r}")
    try:
        import tkinter

        root = tkinter.Tk()
        root.withdraw()
        root.update()
        root.destroy()
        lines.append("window toolkit: ok")
    except Exception as e:
        ok = False
        lines.append(f"window toolkit: FAILED {e!r}")
    lines.append("SELFTEST " + ("PASSED" if ok else "FAILED"))
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return 0 if ok else 1
