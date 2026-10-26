"""Extract explicitly annotated chapter excerpts from cached, attributed public source pages.

No parser import/output is used. Existing frozen fixtures are never overwritten.
Download source HTML separately and pass its directory with --cache.
"""
import argparse
import base64
import hashlib
import json
import re
from html.parser import HTMLParser
from pathlib import Path

ROMANS = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII']
BOOKS = [
    ('R01', 'development', 'en', 'alice', 'Alice’s Adventures in Wonderland', 'Lewis Carroll',
     'https://www.gutenberg.org/cache/epub/11/pg11-images.html', ['CHAPTER ' + n + '.' for n in ROMANS]),
    ('R02', 'development', 'de', 'kafka', 'Die Verwandlung', 'Franz Kafka',
     'https://www.gutenberg.org/cache/epub/22367/pg22367-images.html', ['I.', 'II.', 'III.']),
    ('R03', 'holdout', 'zh', 'aq', '阿Q正传', '鲁迅',
     'https://zh.wikisource.org/zh-hans/%E9%98%BFQ%E6%AD%A3%E4%BC%A0',
     ['第一章 序', '第二章 优胜记略', '第三章 续优胜记略', '第四章 恋爱的悲剧', '第五章 生计问题',
      '第六章 从中兴到末路', '第七章 革命', '第八章 不准革命', '第九章 大团圆']),
]


class Excerpts(HTMLParser):
    def __init__(self, headings):
        super().__init__(); self.expected = headings; self.current = None; self.block = None; self.text = []; self.rows = []

    def handle_starttag(self, tag, attrs):
        if tag == 'h2':
            self.block = tag; self.text = []; self.current = None
        elif tag == 'p' and self.current is not None:
            self.block = tag; self.text = []

    def handle_data(self, text):
        if self.block: self.text.append(text)

    def handle_endtag(self, tag):
        if tag != self.block: return
        text = ''.join(self.text).strip(); self.block = None
        if tag == 'h2':
            lines = [re.sub(r'\s+', ' ', line).strip() for line in text.splitlines() if line.strip()]
            if not lines: return
            label = lines[0]
            if label in self.expected:
                self.current = dict(title=label, subtitle=' '.join(lines[1:]), paragraphs=[]); self.rows.append(self.current)
        elif self.current is not None and text and len(self.current['paragraphs']) < 4:
            self.current['paragraphs'].append(' '.join(text.split()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2] / 'tests/fixtures/real-source'
    if root.exists(): raise SystemExit('Refusing to overwrite frozen source fixtures')
    assembled = []; provenance = []
    for ident, partition, language, stem, title, author, url, headings in BOOKS:
        raw = (args.cache / (stem + '.html')).read_bytes()
        parsed = Excerpts(headings); parsed.feed(raw.decode('utf-8-sig'))
        if [r['title'] for r in parsed.rows] != headings: raise ValueError(f'{ident}: source headings differ from authored expectations')
        if any(len(r['paragraphs']) != 4 for r in parsed.rows): raise ValueError(f'{ident}: fewer than four complete paragraphs')
        text = title + '\n\n'; gold = []
        for row in parsed.rows:
            gold += ['T\t' + row['title'], 'L\t' + str(text.count('\n') + 1)]
            text += row['title'] + '\n'
            if row['subtitle']: text += row['subtitle'] + '\n'
            text += '\n' + '\n\n'.join(row['paragraphs']) + '\n\n'
            gold += ['P64\t' + base64.b64encode(p.encode()).decode() for p in row['paragraphs']]
        assembled.append((ident, partition, language, stem, text.strip() + '\n', '\n'.join(gold) + '\n'))
        provenance.append(dict(id=ident, work=title, author=author, url=url, sourceSha256=hashlib.sha256(raw).hexdigest(),
                               extraction='Original chapter labels, multiline subtitles and first four complete p elements per chapter; navigation and licence boilerplate excluded; HTML whitespace collapsed.'))
    root.mkdir()
    for ident, _, _, _, text, gold in assembled:
        (root / (ident + '.txt')).write_bytes(text.encode())
        (root / (ident + '.gold')).write_bytes(gold.encode())
    manifest = 'id\tpartition\tlanguage\tgroup\n' + ''.join('\t'.join(row[:4]) + '\n' for row in assembled)
    (root / 'manifest.tsv').write_bytes(manifest.encode())
    (root / 'sources.json').write_bytes((json.dumps(provenance, ensure_ascii=False, indent=2) + '\n').encode())
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in root.iterdir() if p.is_file()}
    (root / 'frozen-sha256.json').write_bytes((json.dumps(hashes, sort_keys=True, indent=2) + '\n').encode())
    print('Frozen 3 source works, 24 chapter headings and 96 complete source paragraphs')


if __name__ == '__main__': main()
