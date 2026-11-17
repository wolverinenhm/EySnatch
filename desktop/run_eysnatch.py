"""Entry point for PyInstaller and for `python run_eysnatch.py` from a source checkout."""
import sys

if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--selftest":
        from eysnatch_desktop.selftest import selftest

        sys.exit(selftest(sys.argv[2]))
    from eysnatch_desktop.app import main

    main()
