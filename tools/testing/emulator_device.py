"""Locate the Android SDK portably; UI and stress helpers always target emulator-5554."""
import os
import shutil
from pathlib import Path


def emulator_adb():
    executable = shutil.which('adb')
    if executable:
        return [executable, '-s', 'emulator-5554']
    roots = [os.environ.get(key) for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT')]
    properties = Path(__file__).resolve().parents[2] / 'local.properties'
    if properties.is_file():
        for line in properties.read_text(encoding='utf-8').splitlines():
            if line.strip().startswith('sdk.dir='):
                roots.append(line.split('=', 1)[1].strip().replace('\\:', ':').replace('\\\\', '\\'))
    for root in roots:
        if not root:
            continue
        candidate = Path(root) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
        if candidate.is_file():
            return [str(candidate), '-s', 'emulator-5554']
    raise RuntimeError('ADB not found. Add platform-tools to PATH, set ANDROID_HOME/ANDROID_SDK_ROOT, or configure sdk.dir in local.properties.')
