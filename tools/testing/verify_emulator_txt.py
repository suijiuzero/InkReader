"""Read back emulator-only imported TXT chapters and check authored expectations."""
from pathlib import Path
import subprocess, sqlite3, json, collections, sys
sys.stdout.reconfigure(encoding='utf-8')
REPO = Path(__file__).resolve().parents[2]
OUT = REPO / ('app/build/audit/txt-expanded-2026-10-23' if '--expanded' in sys.argv else 'app/build/audit/txt-corpus-2026-10-23')
OUT.mkdir(parents=True, exist_ok=True)
from emulator_device import emulator_adb
ADB = emulator_adb()
def adb(*args):
    return subprocess.run(ADB + list(args), check=True, capture_output=True, timeout=45).stdout
for name in ('ink_reader.db', 'ink_reader.db-wal', 'ink_reader.db-shm'):
    path = OUT / name
    path.unlink(missing_ok=True)
    try: path.write_bytes(adb('exec-out', 'run-as', 'com.inkreader', 'cat', 'databases/' + name))
    except subprocess.CalledProcessError: pass
db = sqlite3.connect(str(OUT / 'ink_reader.db'))
cases = json.loads((REPO / 'tests/fixtures/txt-corpus/manifest.json').read_text(encoding='utf-8'))
results = []
for case in cases:
    errors = []
    book = db.execute('SELECT MAX(book_id) FROM chapters WHERE source=?', (case['file'],)).fetchone()[0]
    rows = db.execute('SELECT title,file,chars FROM chapters WHERE book_id=? ORDER BY idx', (book,)).fetchall() if book else []
    if case['reject']:
        if rows: errors.append('Empty input unexpectedly imported')
    elif not rows: errors.append('No imported book')
    else:
        titles = [r[0] for r in rows]
        if case['titles'] is not None and titles != case['titles']:
            errors.append(f'Expected titles {case["titles"]}, got {titles}')
        texts = []
        for title, file, chars in rows:
            assert Path(file).name == file
            text = adb('exec-out', 'run-as', 'com.inkreader', 'cat', 'files/chapters/' + file).decode('utf-8')
            if len(text.encode('utf-16-le'))//2 != chars: errors.append('Saved character count mismatch')
            if '\ufffd' in text: errors.append('Unexpected replacement character')
            texts.append(text)
        joined = '\n\n'.join(texts)
        for block, count in collections.Counter(case['blocks']).items():
            if joined.count(block) != count: errors.append(f'Body paragraph missing/duplicated: expected {count}, got {joined.count(block)}')
        for title in case.get('desired_titles', []):
            if title not in titles and title not in joined:
                errors.append(f'Unrecognized heading lost from body: {title}')
        if case['plain']:
            compact = lambda s: ''.join(s.split())
            if compact(joined) != compact(case['source_text']): errors.append('Plain fallback lost or changed characters')
    actual_titles = [r[0] for r in rows]
    limitation = bool(case.get('desired_titles') and actual_titles != case['desired_titles'])
    result = dict(id=case['id'], scenario=case['scenario'], book_id=book, chapters=len(rows), titles=actual_titles,
                  desired_titles=case.get('desired_titles'), status='FAIL' if errors else ('LIMITATION' if limitation else 'PASS'), errors=errors)
    results.append(result)
    print(case['id'], result['status'], case['scenario'], '; '.join(errors))
(OUT / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2),encoding='utf-8')
print('TOTAL',len(results),'PASS',sum(r['status']=='PASS' for r in results),'LIMITATION',sum(r['status']=='LIMITATION' for r in results),'FAIL',sum(r['status']=='FAIL' for r in results))
sys.exit(any(r['status']=='FAIL' for r in results))
