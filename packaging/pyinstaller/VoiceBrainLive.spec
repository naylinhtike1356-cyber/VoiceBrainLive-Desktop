# PyInstaller wrapper for the already-built Compose Desktop distribution.
# Build on Windows after :desktop:createDistributable has produced the source folder.
from pathlib import Path
from PyInstaller.utils.hooks import collect_submodules

ROOT = Path(SPECPATH).parents[2]
COMPOSE_APP = ROOT / 'desktop' / 'build' / 'compose' / 'binaries' / 'main' / 'app' / 'VoiceBrainLive'

if not COMPOSE_APP.exists():
    raise SystemExit(f'Missing Compose distribution: {COMPOSE_APP}')

hiddenimports = collect_submodules('') if False else []

a = Analysis(
    [str(ROOT / 'packaging' / 'pyinstaller' / 'launcher.py')],
    pathex=[str(ROOT)],
    binaries=[],
    datas=[(str(COMPOSE_APP), 'VoiceBrainLive')],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=True,
)

pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='VoiceBrainLive-PyInstaller',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    icon=str(ROOT / 'desktop' / 'src' / 'main' / 'resources' / 'voicebrain_robot.ico'),
)
