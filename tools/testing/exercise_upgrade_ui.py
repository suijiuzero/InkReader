"""Small, explicit emulator-5554 UI driver for the 1.14.1 acceptance steps.

Examples: python tools/testing/exercise_upgrade_ui.py show
          python tools/testing/exercise_upgrade_ui.py tap 'Whole-book chapter review'
          python tools/testing/exercise_upgrade_ui.py query 65
Only operates the project emulator. Original shelf titles are redacted when a baseline exists.
"""
import importlib.util
import json
import re
import shlex
import sys
import time
from pathlib import Path

stage = sys.argv[1]
args = sys.argv[2:]
saved = sys.argv
sys.argv = ['emulator_txt_ui.py', 'noop']
spec = importlib.util.spec_from_file_location('ui', Path(__file__).with_name('emulator_txt_ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
sys.argv = saved
ui.ROOT = Path(__file__).resolve().parents[2] / 'app/build/audit/emulator-1.14.1'
ui.ROOT.mkdir(parents=True, exist_ok=True)


def tap(label, long=False):
    root, parents = ui.dump()
    nodes = list(root.iter('node'))
    node = next((n for n in nodes if n.get('text') == label or n.get('content-desc') == label), None)
    if node is None:
        node = next(n for n in nodes if label in n.get('text', '') or label in n.get('content-desc', ''))
    original = node
    while node.get('clickable') != 'true' and node in parents and not long:
        node = parents[node]
    if node.get('clickable') != 'true': node = original
    a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds')))
    current = node
    while current in parents:
        current = parents[current]
        if current.get('scrollable') == 'true':
            x, y, z, w = map(int, re.findall(r'\d+', current.get('bounds')))
            a, b, c, d = max(a, x), max(b, y), min(c, z), min(d, w)
    assert c > a and d > b, 'Control is clipped; scroll it into view'
    x, y = str((a + c) // 2), str((b + d) // 2)
    ui.adb('shell', 'input', *(['swipe', x, y, x, y, '800'] if long else ['tap', x, y]))
    print('tap', label, flush=True)


if stage == 'show':
    original = []
    baseline = ui.ROOT / 'before/baseline.json'
    if baseline.is_file(): original = [b['title'] for b in json.loads(baseline.read_text(encoding='utf-8'))['books']]
    root, _ = ui.dump()
    for n in root.iter('node'):
        text = n.get('text', '') or n.get('content-desc', '')
        if not text: continue
        if any(text == title or len(text) > 2 and text in title for title in original): text = '[existing title hidden]'
        print(text, n.get('bounds'), n.get('checked'))
elif stage in ('tap', 'hold'):
    for label in args: tap(label, stage == 'hold'); time.sleep(.4)
elif stage == 'query':
    root, _ = ui.dump()
    field = next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
    a, b, c, d = map(int, re.findall(r'\d+', field.get('bounds')))
    ui.adb('shell', 'input', 'tap', str((a + c) // 2), str((b + d) // 2))
    ui.adb('shell', 'input', 'keycombination', '113', '29')
    ui.adb('shell', 'input', 'keyevent', '67')
    if args: ui.adb('shell', 'input', 'text', shlex.quote(args[0]))
    ui.adb('shell', 'input', 'keyevent', '4')
elif stage == 'scroll':
    for _ in range(int(args[0]) if args else 1):
        root, _ = ui.dump()
        boxes = [tuple(map(int, re.findall(r'\d+', n.get('bounds')))) for n in root.iter('node') if n.get('scrollable') == 'true']
        a, b, c, d = max(boxes, key=lambda box: box[3] - box[1])
        x = str((a + c) // 2)
        ui.adb('shell', 'input', 'swipe', x, str(b + (d - b) * 4 // 5), x, str(b + (d - b) // 5), '400')
        time.sleep(.3)
elif stage == 'shot':
    assert Path(args[0]).name == args[0]
    (ui.ROOT / args[0]).write_bytes(ui.adb('exec-out', 'screencap', '-p'))
elif stage == 'back':
    ui.adb('shell', 'input', 'keyevent', '4')
else:
    raise SystemExit('Unknown stage')
