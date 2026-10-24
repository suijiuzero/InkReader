"""ADB helpers for virtual folder-home acceptance on the emulator only."""
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
ui.ROOT = Path(__file__).resolve().parents[2] / 'app/build/audit/folder-home-2026-10-24'
ui.ROOT.mkdir(parents=True, exist_ok=True)


def wait_for(value, timeout=30):
    end = time.monotonic() + timeout
    while True:
        root, parents = ui.dump()
        if any(value in n.get('text', '') or value == n.get('content-desc') for n in root.iter('node')):
            return root, parents
        assert time.monotonic() < end, 'Missing: ' + value


def shot(name):
    (ui.ROOT / name).write_bytes(ui.adb('exec-out', 'screencap', '-p'))


def record(check, **details):
    file = ui.ROOT / 'results.json'
    results = json.loads(file.read_text(encoding='utf-8')) if file.exists() else []
    results.append(dict(check=check, status='PASS', **details))
    file.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS', check, flush=True)


if args[0] == 'seed':
    for folder, name, marker in [('INK_PICKER_SECOND', 'beta.txt', 'SECOND_BETA'),
                                 ('INK_PICKER_THIRD', 'gamma.txt', 'THIRD_GAMMA')]:
        ui.adb('shell', 'mkdir', '-p', '/sdcard/Download/' + folder)
        target = ui.ROOT / name
        target.write_text((marker + ' A plain story without chapter headings. ') * 35, encoding='utf-8')
        subprocess.run(ui.ADB + ['push', name, '/sdcard/Download/' + folder + '/' + name],
                       cwd=ui.ROOT, check=True, capture_output=True, timeout=45)
    print('Seeded two separate folder roots')
elif args[0] == 'tap':
    for label in args[1:]: ui.click(label)
elif args[0] == 'long': ui.click(args[1], long=True)
elif args[0] == 'shot': shot(args[1])
elif args[0] == 'dump':
    root, _ = ui.dump()
    for node in root.iter('node'):
        if node.get('text') or node.get('content-desc'):
            print(node.get('text'), '|', node.get('content-desc'), '|', node.get('bounds'))
