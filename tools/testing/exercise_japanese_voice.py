"""Real emulator clicks: open synthetic Japanese book, test connection and both previews.

Requires the emulator already paired to the local PC; never reads or prints credentials.
"""
import importlib.util
import sys
import time
import json
from pathlib import Path

sys.argv = ['emulator_txt_ui.py', 'noop']
spec = importlib.util.spec_from_file_location('ui', Path(__file__).with_name('emulator_txt_ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
ui.ROOT = Path(__file__).resolve().parents[2] / 'app/build/audit/japanese-voice-2026-10-23'
ui.ROOT.mkdir(parents=True, exist_ok=True)


def wait_for(value, timeout=25):
    end = time.monotonic() + timeout
    while True:
        root, _ = ui.dump()
        if any(value in n.get('text', '') or value == n.get('content-desc') for n in root.iter('node')):
            return
        assert time.monotonic() < end, 'Missing control/status: ' + value


def shot(name):
    ui.adb('shell', 'screencap', '-p', '/data/local/tmp/japanese-voice.png')
    ui.adb('pull', '/data/local/tmp/japanese-voice.png', str(ui.ROOT / name))


ui.click('Search books')
ui.adb('shell', 'input', 'text', 'AUDIT_C48')
ui.adb('shell', 'input', 'keyevent', '4')
ui.click('3 chapters')
wait_for('Back to Library')
ui.adb('shell', 'input', 'tap', '540', '1200')
wait_for('Read aloud')
ui.click('Read aloud')
wait_for('已配对 ·')
ui.click('测试连接')
wait_for('晓晓和日语音色可用')
shot('connection.png')
for name, button in [('日语', '试听日语'), ('晓晓', '试听晓晓')]:
    ui.click(button)
    wait_for('正在试听' + name)
    shot('preview-' + ('ja' if name == '日语' else 'zh') + '-playing.png')
    wait_for('试听完成 · ' + name)
    shot('preview-' + ('ja' if name == '日语' else 'zh') + '-completed.png')
    print('UI PASS: preview ' + name, flush=True)
(ui.ROOT / 'preview-ui-results.json').write_text(json.dumps(dict(
    connection='PASS: local Japanese capability reported', japanese_preview='PASS: playback completed',
    chinese_preview='PASS: playback completed'), indent=2), encoding='utf-8')
