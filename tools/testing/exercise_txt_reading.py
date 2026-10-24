"""ADB click-level reading spot checks; run after corpus import, from Library."""
import importlib.util, sys, time, json
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
expanded = '--expanded' in sys.argv
sys.argv = ['emulator_txt_ui.py', 'noop']
spec = importlib.util.spec_from_file_location('ui', Path(__file__).with_name('emulator_txt_ui.py'))
ui = importlib.util.module_from_spec(spec); spec.loader.exec_module(ui)
cases = json.loads((Path(__file__).resolve().parents[2] / 'tests/fixtures/txt-corpus/manifest.json').read_text(encoding='utf-8'))
ids = ['C04','C12','C22','C30','C37','C42','C44','C31']
actual = {}
if expanded:
    ui.ROOT = Path(__file__).resolve().parents[2] / 'app/build/audit/txt-expanded-2026-10-23'
    actual = {r['id']: r for r in json.loads((ui.ROOT/'results.json').read_text(encoding='utf-8'))}
    ids = [f'C{i:02d}' for i in range(48, 74, 2)] + ['C88','C108','C112','C121','C151','C153']
def texts():
    root, _ = ui.dump()
    return [n.get('text', '') for n in root.iter('node')]
def await_text(value):
    end = time.monotonic()+20
    while value not in texts():
        assert time.monotonic() < end, f'Timed out: {value}'
        time.sleep(.2)
results = []
for ident in ids:
    case = next(c for c in cases if c['id'] == ident)
    ui.click('Search books')
    ui.adb('shell','input','text','AUDIT_'+ident)
    ui.adb('shell','input','keyevent','4')
    labels = texts()
    expected = actual[ident]['titles'] if expanded else case['titles']
    count = len(expected) if expected else None
    metadata = next(t for t in labels if ('chapters ·' in t or 'chapter ·' in t) and (count is None or t.startswith(str(count)+' chapter')))
    ui.click(metadata)
    # Wait for the reader before injecting the central tap (avoid tapping a moving card grid).
    end = time.monotonic()+20
    while True:
        root, _ = ui.dump()
        if any(n.get('content-desc') == 'Back to Library' for n in root.iter('node')): break
        assert time.monotonic()<end
    ui.adb('shell','input','tap','540','1200')
    await_text('Contents')
    ui.click('Contents')
    labels = texts()
    if expected:
        assert all(title in labels for title in expected), (ident, labels)
        title = expected[-1]
    else:
        title = next(t for t in labels if t.startswith('第 ') and t.endswith(' 节'))
    ui.adb('shell','screencap','-p','/data/local/tmp/txt-corpus-shot.png')
    ui.adb('pull','/data/local/tmp/txt-corpus-shot.png',str(ui.ROOT/(ident+'-contents.png')))
    ui.click(title)
    labels = texts()
    if expanded:
        # UI rendering check is separate from independent title/body recognition checks.
        source_titles = case.get('source_titles') or case.get('desired_titles') or case['titles'] or []
        block_index = source_titles.index(title) if title in source_titles and len(source_titles) == len(case['blocks']) else 0
        marker = case['blocks'][block_index].split(' ')[0]
        end = time.monotonic()+20
        while not any(marker in t for t in labels):
            assert time.monotonic() < end, (ident, 'Body not visible', labels)
            labels = texts()
    ui.adb('shell','screencap','-p','/data/local/tmp/txt-corpus-shot.png')
    ui.adb('pull','/data/local/tmp/txt-corpus-shot.png',str(ui.ROOT/(ident+'-reading.png')))
    ui.click('Back to Library')
    await_text('Ink Reader')
    results.append(dict(id=ident,scenario=case['scenario'],status='PASS'))
    (ui.ROOT/'reading-ui-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print('UI PASS',ident,case['scenario'],flush=True)
