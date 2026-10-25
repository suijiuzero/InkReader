"""Run bounded, repeatable suites on emulator-5554 only; never select a physical phone."""
import argparse
import csv
import json
import re
import subprocess
import sys
import time
import threading
from datetime import datetime
from pathlib import Path

from emulator_device import emulator_adb


def read_report(output):
    for match in reversed(list(re.finditer(r'\{\s*"checks"\s*:', output))):
        try:
            return json.JSONDecoder().raw_decode(output[match.start():])[0]
        except json.JSONDecodeError:
            continue
    match = re.search(r'PASS (\d+)', output)
    if match and 'INSTRUMENTATION_FAILED' not in output and 'FAIL' not in output:
        return dict(passed=int(match.group(1)), failed=0)
    return dict(passed=0, failed=1, error='Missing or incomplete instrumentation report')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modes', default='recognition,device,order,full,additional,soak')
    parser.add_argument('--rounds', type=int, choices=range(1, 6), default=1)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    modes = args.modes.split(',')
    if any(mode not in ('recognition', 'device', 'order', 'full', 'additional', 'soak') for mode in modes):
        parser.error('Unknown suite')
    output = args.output or Path('app/build/audit') / ('algorithm-' + datetime.now().strftime('%Y-%m-%d-%H%M%S'))
    output.mkdir(parents=True, exist_ok=True)
    adb = emulator_adb()
    if subprocess.check_output(adb + ['get-state'], timeout=15).strip() != b'device':
        raise RuntimeError('Emulator is not ready')
    summaries = []
    for iteration in range(1, args.rounds + 1):
        for mode in modes:
            extra = [] if mode == 'device' else ['-e', 'importOrder', 'true'] if mode == 'order' else \
                ['-e', 'recognition', 'true'] if mode == 'recognition' else ['-e', 'stress', mode]
            command = adb + ['shell', 'am', 'instrument', '-w'] + extra + ['com.inkreader.test/com.inkreader.AuditInstrumentation']
            start = time.monotonic()
            samples = []
            stop = threading.Event()
            def sample_memory():
                while not stop.is_set():
                    try:
                        result = subprocess.run(adb + ['shell', 'dumpsys', 'meminfo', 'com.inkreader'],
                                                capture_output=True, timeout=8).stdout.decode(errors='replace')
                        match = re.search(r'TOTAL PSS:\s+(\d+)', result)
                        if match:
                            samples.append(dict(seconds=round(time.monotonic() - start, 2), pssKiB=int(match.group(1))))
                    except subprocess.TimeoutExpired:
                        pass
                    stop.wait(2)
            monitor = threading.Thread(target=sample_memory, daemon=True)
            monitor.start()
            print(f'START round={iteration} mode={mode}', flush=True)
            try:
                result = subprocess.run(command, capture_output=True, timeout=1800)
                raw = result.stdout + result.stderr
                exit_code = result.returncode
            except subprocess.TimeoutExpired as error:
                raw = (error.stdout or b'') + (error.stderr or b'')
                exit_code = -1
            finally:
                stop.set()
                monitor.join(timeout=9)
            text = raw.decode('utf-8', errors='replace')
            stem = f'round-{iteration}-{mode}'
            (output / f'{stem}.txt').write_text(text, encoding='utf-8')
            report = read_report(text)
            summary = dict(round=iteration, mode=mode, seconds=round(time.monotonic() - start, 2),
                           passed=report.get('passed', 0), failed=report.get('failed', 1), exitCode=exit_code,
                           memorySamples=len(samples),
                           maxSampledPssMiB=round(max((s['pssKiB'] for s in samples), default=0) / 1024, 2) if samples else None)
            with (output / f'{stem}-memory.csv').open('w', newline='') as file:
                writer = csv.DictWriter(file, fieldnames=['seconds', 'pssKiB'])
                writer.writeheader(); writer.writerows(samples)
            if exit_code != 0:
                summary['failed'] = max(1, summary['failed'])
            (output / f'{stem}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            summaries.append(summary)
            (output / 'summary.json').write_text(json.dumps(summaries, indent=2), encoding='utf-8')
            print(json.dumps(summary), flush=True)
    return int(any(item['failed'] for item in summaries))


if __name__ == '__main__':
    sys.exit(main())
