"""Add an explicitly reviewed UTF-8 TXT + exhaustive title-line labels to our local training corpus.

Never reads a shelf, trains automatically, or uploads text. Refuses overwrites.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'tests/fixtures/heading-training-extra'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--id', required=True)
    parser.add_argument('--group', required=True, help='Same work always has the same canonical group, e.g. real-source-v3/23910')
    parser.add_argument('--lines', required=True, help='Exhaustive one-based source title lines, comma-separated; none for a negative document')
    parser.add_argument('--partition', choices=['development', 'holdout'], required=True)
    parser.add_argument('--language', default='zh')
    parser.add_argument('--reviewed', action='store_true', required=True, help='Confirm that ALL boundaries have been reviewed, including negative paragraphs')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.id): parser.error('Invalid case ID')
    if not re.fullmatch(r'[A-Za-z0-9_/-]{1,100}', args.group) or '\t' in args.language or '\n' in args.language: parser.error('Invalid group or language')
    text = args.source.read_text(encoding='utf-8-sig')
    lines = text.splitlines()
    labels = [] if args.lines == 'none' else sorted(set(int(value) for value in args.lines.split(',')))
    titles = []
    for number in labels:
        if not 1 <= number <= len(lines): parser.error('Title line out of bounds')
        title = lines[number-1].strip()
        if not title or len(title) > 200 or sum(line.strip()==title for line in lines)!=1:
            parser.error('Empty, overlong or repeated title: review a smaller unambiguous excerpt')
        titles.append(title)
    raw = text.replace('\r\n', '\n').replace('\r', '\n').encode('utf-8')
    gold = ''.join(f'T\t{title}\nL\t{number}\n' for title, number in zip(titles, labels)).encode('utf-8')
    if not labels: gold = b'# Reviewed body-only negative\n'
    ROOT.mkdir(parents=True, exist_ok=True)
    manifest = ROOT / 'manifest.tsv'
    existing = manifest.read_text('utf-8') if manifest.exists() else 'id\tpartition\tlanguage\tgroup\tkind\tcontract\n'
    if any(row.startswith(args.id+'\t') for row in existing.splitlines()) or any((ROOT/(args.id+suffix)).exists() for suffix in ['.txt','.gold']):
        parser.error('Case already exists; use a new ID')
    row = '\t'.join([args.id, args.partition, args.language, args.group, 'reviewed', 'training'])+'\n'
    ledger = ROOT/'reviewed-sha256.json'
    hashes = json.loads(ledger.read_text('utf-8')) if ledger.exists() else {}
    for suffix, data in [('.txt', raw), ('.gold', gold)]:
        name = args.id+suffix
        with (ROOT/name).open('xb') as target: target.write(data)
        hashes[name] = hashlib.sha256(data).hexdigest()
    manifest.write_text(existing+row, encoding='utf-8')
    ledger.write_text(json.dumps(hashes,sort_keys=True,indent=2)+'\n',encoding='utf-8')
    print(f'Added reviewed case {args.id}: {len(labels)} boundaries; kept {args.partition} partition')


if __name__ == '__main__':
    main()
