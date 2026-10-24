"""ADB click acceptance for voice model cards; emulator must already be paired."""
import importlib.util
import json
import re
import sys
import time
import subprocess
from pathlib import Path

sys.argv = ['emulator_txt_ui.py', 'noop']
spec = importlib.util.spec_from_file_location('ui', Path(__file__).with_name('emulator_txt_ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
ui.ROOT = Path(__file__).resolve().parents[2] / 'app/build/audit/voice-models-2026-10-23'
ui.ROOT.mkdir(parents=True, exist_ok=True)


def wait_any(*values):
    end = time.monotonic() + 25
    while True:
        root, _ = ui.dump()
        labels = [n.get('text', '') for n in root.iter('node')]
        for value in values:
            if any(value in t for t in labels):
                return value
        assert time.monotonic() < end, ('Missing status', values, labels)


def shot(name):
    ui.adb('shell', 'screencap', '-p', '/data/local/tmp/voice-models.png')
    # ADB's Windows path handling is unreliable with absolute CJK paths; keep the target ASCII-relative.
    subprocess.run(ui.ADB + ['pull', '/data/local/tmp/voice-models.png', name], cwd=ui.ROOT,
                   check=True, capture_output=True, timeout=45)


def open_panel():
    wait_any('Ink Reader')
    ui.click('Search books')
    ui.adb('shell', 'input', 'text', 'AUDIT_C48')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.click('3 chapters')
    end = time.monotonic() + 20
    while True:
        root, _ = ui.dump()
        if any(n.get('content-desc') == 'Back to Library' for n in root.iter('node')):
            break
        assert time.monotonic() < end
    ui.adb('shell', 'input', 'tap', '540', '1200')
    wait_any('Read aloud')
    ui.click('Read aloud')
    wait_any('电脑语音')


def choose(name):
    ui.click('选择朗读模型')
    wait_any('使用提示')
    ui.click(name)


open_panel()
root, _ = ui.dump()
nodes = list(root.iter('node'))
def bounds(label):
    return list(map(int, re.findall(r'\d+', next(n for n in nodes if n.get('text') == label).get('bounds'))))
cancel, select, preview, connect = [bounds(t) for t in ['取消配对', '选择朗读模型', '试听语音', '测试连接']]
assert cancel[1] == select[1] and cancel[3] == select[3]
assert select[0] == preview[0] and select[2] == preview[2] and cancel[0] == connect[0]
assert preview[3] <= select[1]
assert not any(n.get('text') in ['电脑晓晓', '试听晓晓', '试听日语'] for n in nodes)
shot('panel.png')

# Draft changes must not overwrite the preference when cancelled.
wait_any('当前模型：自动')
choose('七海')
wait_any('固定使用七海')
shot('model-nanami.png')
ui.click('取消')
wait_any('当前模型：自动')
results = [dict(check='two-row layout, renamed controls, cancel keeps preference', status='PASS')]

for name in ['七海', '晓晓', '自动']:
    choose(name)
    shot('model-' + {'七海': 'nanami', '晓晓': 'xiaoxiao', '自动': 'auto'}[name] + '.png')
    ui.click('保存选择')
    wait_any('当前模型：' + name)
    ui.click('试听语音')
    wait_any('正在试听' + name, '试听完成 · ' + name)
    wait_any('试听完成 · ' + name)
    shot('preview-' + {'七海': 'nanami', '晓晓': 'xiaoxiao', '自动': 'auto'}[name] + '.png')
    results.append(dict(model=name, check='save and actual preview completion', status='PASS'))
    print('UI PASS model', name, flush=True)

choose('七海')
ui.click('保存选择')
wait_any('当前模型：七海')
ui.adb('shell', 'am', 'force-stop', 'com.inkreader')
ui.adb('shell', 'am', 'start', '-n', 'com.inkreader/.MainActivity')
open_panel()
wait_any('当前模型：七海')
shot('model-persisted.png')
results.append(dict(check='model persists after app restart', status='PASS'))
choose('自动')
ui.click('保存选择')
wait_any('当前模型：自动')
(ui.ROOT / 'model-ui-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print('UI PASS persistence and automatic model restored', flush=True)
