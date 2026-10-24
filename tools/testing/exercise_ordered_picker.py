"""ADB helpers for the app-owned ordered picker; never operates a physical phone by default."""
import importlib.util
import json
import re
import subprocess
import sys
import time
from pathlib import Path

args = sys.argv[1:]
sys.argv = ['emulator_txt_ui.py', 'noop']
spec = importlib.util.spec_from_file_location('ui', Path(__file__).with_name('emulator_txt_ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
ui.ROOT = Path(__file__).resolve().parents[2] / 'app/build/audit/ordered-picker-2026-10-24'
ui.ROOT.mkdir(parents=True, exist_ok=True)


def wait_for(value, timeout=30):
    end = time.monotonic() + timeout
    while True:
        root, parents = ui.dump()
        if any(value in n.get('text', '') or value == n.get('content-desc') for n in root.iter('node')):
            return root, parents
        assert time.monotonic() < end, 'Missing visible status: ' + value


def shot(name):
    (ui.ROOT / name).write_bytes(ui.adb('exec-out', 'screencap', '-p'))


def visible():
    root, _ = ui.dump()
    for node in root.iter('node'):
        if node.get('text') or node.get('content-desc'):
            print(node.get('text'), '|', node.get('content-desc'), '|', node.get('bounds'), '|', node.get('clickable'))


if args[0] == 'seed':
    root = '/sdcard/Download/INK_PICKER_UI'
    ui.adb('shell', 'mkdir', '-p', root + '/inside', root + '/empty')
    fixtures = [('zeta.txt', 'ROOT_ZETA'), ('alpha.txt', 'ROOT_ALPHA'),
                ('inside/middle.txt', 'INSIDE_MIDDLE'), ('inside/alpha.txt', 'INSIDE_ALPHA')]
    for relative, marker in fixtures:
        local = ui.ROOT / relative
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_text((marker + ' A plain story without chapter headings. ') * 35, encoding='utf-8')
        subprocess.run(ui.ADB + ['push', local.name, root + '/' + relative], cwd=local.parent,
                       check=True, capture_output=True, timeout=45)
    (ui.ROOT / 'ignored.png').write_text('Unsupported document fixture', encoding='utf-8')
    subprocess.run(ui.ADB + ['push', 'ignored.png', root + '/ignored.png'], cwd=ui.ROOT,
                   check=True, capture_output=True, timeout=45)
    print('Seeded isolated root/subfolder/duplicate-name/empty/unsupported fixtures')
elif args[0] == 'tap':
    for label in args[1:]:
        ui.click(label)
elif args[0] == 'long':
    ui.click(args[1], long=True)
elif args[0] == 'dump':
    visible()
elif args[0] == 'shot':
    shot(args[1])
