from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def bundle_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS"))
    return Path(__file__).resolve().parents[2]


def main() -> int:
    app_dir = bundle_root() / "VoiceBrainLive"
    executable = app_dir / "VoiceBrainLive.exe"
    if not executable.exists():
        raise FileNotFoundError(f"Compose executable was not found: {executable}")

    env = os.environ.copy()
    env.setdefault("VOICEBRAIN_WRAPPER", "pyinstaller")
    completed = subprocess.run([str(executable), *sys.argv[1:]], cwd=app_dir, env=env, check=False)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
