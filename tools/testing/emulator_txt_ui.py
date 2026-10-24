import subprocess, sys, pathlib, xml.etree.ElementTree as ET, re, time
sys.stdout.reconfigure(encoding='utf-8')
ROOT = pathlib.Path(__file__).resolve().parents[2] / "app/build/audit/txt-corpus-2026-10-23"
from emulator_device import emulator_adb
ADB = emulator_adb()
def adb(*args):
    return subprocess.run(ADB + list(args), check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45).stdout
def dump():
    # uiautomator can exit zero while failing to dump a moving screen; never reuse stale XML.
    for attempt in range(3):
        adb('shell', 'rm', '-f', '/data/local/tmp/inkreader-gap-ui.xml')
        outcome = adb('shell', 'uiautomator', 'dump', '/data/local/tmp/inkreader-gap-ui.xml')
        if b'UI hierchary dumped to:' in outcome: break
        if attempt == 2: raise RuntimeError(outcome.decode(errors='replace'))
        time.sleep(.5)
    xml = adb('shell', 'cat', '/data/local/tmp/inkreader-gap-ui.xml')
    (ROOT / 'last-ui.xml').write_bytes(xml)
    root = ET.fromstring(xml)
    return root, {child: parent for parent in root.iter() for child in parent}
def click(text, long=False):
    root, parents = dump()
    nodes = list(root.iter('node'))
    node = next((n for n in nodes if n.get('text') == text or n.get('content-desc') == text), None)
    if node is None:
        node = next((n for n in nodes if text in n.get('text', '') or text in n.get('content-desc', '')), None)
    assert node is not None, f'Missing visible control: {text}'
    original = node
    while node.get('clickable') != 'true' and node in parents and not long:
        node = parents[node]
    if node.get('clickable') != 'true':
        node = original
    coords = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(coords) == 4, f'Missing bounds: {text}'
    x, y = (coords[0] + coords[2]) // 2, (coords[1] + coords[3]) // 2
    if long:
        adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '800')
    else:
        adb('shell', 'input', 'tap', str(x), str(y))
    print(f'{"long press" if long else "tap"}: {text} at {x},{y}')
ROOT.mkdir(parents=True, exist_ok=True)
action = sys.argv[1]
if action == 'dump':
    root, _ = dump()
    for n in root.iter('node'):
        if n.get('text') or n.get('content-desc'):
            print(n.get('text'), '|', n.get('content-desc'), '|', n.get('bounds'), '| clickable=' + str(n.get('clickable')))
elif action in ('tap', 'long'):
    click(sys.argv[2], action == 'long')
elif action == 'shot':
    adb('shell', 'screencap', '-p', '/data/local/tmp/inkreader-gap-shot.png')
    adb('pull', '/data/local/tmp/inkreader-gap-shot.png', str(ROOT / sys.argv[2]))
    print(str(ROOT / sys.argv[2]))
elif action == 'db':
    for name in ('ink_reader.db', 'ink_reader.db-wal', 'ink_reader.db-shm'):
        try:
            (ROOT / name).write_bytes(adb('exec-out', 'run-as', 'com.inkreader', 'cat', 'databases/' + name))
        except subprocess.CalledProcessError:
            pass
    import sqlite3
    db = sqlite3.connect(str(ROOT / 'ink_reader.db'))
    for table in ('books', 'chapters'):
        print(table, db.execute(f'SELECT * FROM {table}').description)
        for row in db.execute(f'SELECT * FROM {table}'):
            print(row)
