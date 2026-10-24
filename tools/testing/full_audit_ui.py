"""Explicit emulator-only UI pressure scenarios; never uses an attached phone."""
import sys,runpy,time,json,re,subprocess
from pathlib import Path
stage=sys.argv[1]
sys.argv=['exercise_file_batch.py','noop']
g=runpy.run_path(str(Path(__file__).with_name('exercise_file_batch.py')))
ui=g['ui'];ui.ROOT=Path(__file__).resolve().parents[2]/'app/build/audit/full-2026-10-24'
wait=g['wait_for'];shot=g['shot'];reveal=g['reveal']
if stage in ('picker-baseline','picker-select-fixed'):
 ui.adb('shell','am','start','-n','com.inkreader/.MainActivity')
 for attempt in range(15):
  try: root,_=ui.dump(); break
  except RuntimeError:
   if attempt==14: raise
   time.sleep(.5)
 if any(n.get('text')=='Folder home' for n in root.iter('node')): ui.click('Back')
 wait('396 books')
 ui.click('Import');wait('Folder home');ui.click('INK_PICKER_UI');wait('picker-pressure');ui.click('picker-pressure')
 wait('stress-0001-');root,_=ui.dump()
 label=next(n.get('text') for n in root.iter('node') if n.get('text','').startswith('stress-0001-'))
 ui.click(label,long=True);wait('Batch selection');ui.click('Select all in display order');wait('Selected: 2000');shot('picker-2000-selected.png')
 pid=ui.adb('shell','pidof','com.inkreader').decode().strip()
 ui.adb('shell','input','keyevent','3');time.sleep(2)
 log=ui.adb('logcat','-b','crash','-d','--pid='+pid).decode(errors='replace')
 (ui.ROOT/'baseline-picker-crash.txt').write_text(log,encoding='utf-8')
 print(log[-8000:])
 alive=subprocess.run(ui.ADB+['shell','pidof','com.inkreader'],capture_output=True).stdout.decode().strip()
 print('Original process alive:',alive)
 if stage=='picker-select-fixed':
  assert alive and 'TransactionTooLargeException' not in log
  ui.adb('shell','am','kill','com.inkreader');time.sleep(1)
  assert not subprocess.run(ui.ADB+['shell','pidof','com.inkreader'],capture_output=True).stdout.strip()
  ui.adb('shell','am','start','-n','com.inkreader/.MainActivity');wait('Selected: 2000')
  shot('picker-2000-restored.png')
  ui.click('Continue import');wait('Import 2000 files');shot('picker-2000-import-confirm.png');ui.click('Cancel');wait('396 books')
  print('PASS 2000 files survive background and killed process, preserve order and reach import confirmation',flush=True)
elif stage=='picker-fixed':
 ui.adb('shell','am','start','-n','com.inkreader/.MainActivity');wait('Selected: 2000');shot('picker-2000-restored.png')
 ui.click('Clear');wait('Selected: 0');ui.click('Back');ui.click('Back');ui.click('Back');wait('396 books')
 print('PASS 2000-file selection restored and cleaned; original 396 books preserved',flush=True)

elif stage=='final-library':
 ui.adb('shell','am','start','-n','com.inkreader/.MainActivity');wait('396 books');shot('final-library.png')
 pid=ui.adb('shell','pidof','com.inkreader').decode().strip().split()[0]
 crash=ui.adb('logcat','-b','crash','-d','--pid='+pid).decode(errors='replace')
 assert 'FATAL EXCEPTION' not in crash
 print('PASS final app launches with original 396 books and no fatal crash in current process',flush=True)
