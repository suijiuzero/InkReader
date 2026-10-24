"""Run isolated Android suites on emulator-5554 and sample process memory every two seconds."""
import csv,json,re,subprocess,sys,threading,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]/'app/build/audit/full-2026-10-24';ROOT.mkdir(parents=True,exist_ok=True)
from emulator_device import emulator_adb
ADB = emulator_adb()
mode=sys.argv[1];stop=threading.Event();samples=[]
def sample():
 while not stop.is_set():
  try:
   result=subprocess.run(ADB+['shell','dumpsys','meminfo','com.inkreader'],capture_output=True,timeout=8).stdout.decode(errors='replace')
   match=re.search(r'TOTAL PSS:\s+(\d+)',result)
   if match:samples.append(dict(seconds=round(time.monotonic()-begin,2),pssKiB=int(match.group(1))))
  except subprocess.TimeoutExpired:pass
  stop.wait(2)
begin=time.monotonic();thread=threading.Thread(target=sample,daemon=True);thread.start()
extra=[] if mode=='device' else ['-e','importOrder','true'] if mode=='order' else ['-e','stress',mode]
command=ADB+['shell','am','instrument','-w']+extra+['com.inkreader.test/com.inkreader.AuditInstrumentation']
lines=[]
try:
 with subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace') as process:
  for line in process.stdout:
   lines.append(line)
   if line.startswith('{"name"') or 'PASS ' in line or 'FAILED' in line:print(line.rstrip(),flush=True)
  process.wait(timeout=1800)
finally:stop.set();thread.join(timeout=9)
output=''.join(lines);(ROOT/(mode+'.txt')).write_text(output,encoding='utf-8')
report=None
for match in re.finditer(r'\{\s*"checks"\s*:',output):
 try:report=json.JSONDecoder().raw_decode(output[match.start():])[0]
 except json.JSONDecodeError:pass
summary=dict(mode=mode,seconds=round(time.monotonic()-begin,2),memorySamples=len(samples),maxSampledPssMiB=round(max((s['pssKiB'] for s in samples),default=0)/1024,2))
if report is not None:
 (ROOT/(mode+'-results.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 summary.update(passed=report.get('passed'),failed=report.get('failed'))
else:
 summary['failed']=int('FAILED' in output or not re.search(r'PASS \d+',output))
 match=re.search(r'PASS (\d+)',output)
 if match:summary['passed']=int(match.group(1))
( ROOT/(mode+'-summary.json')).write_text(json.dumps(summary,indent=2))
with (ROOT/(mode+'-memory.csv')).open('w',newline='') as file:
 writer=csv.DictWriter(file,fieldnames=['seconds','pssKiB']);writer.writeheader();writer.writerows(samples)
print(json.dumps(summary),flush=True)
sys.exit(int(bool(summary.get('failed'))))
