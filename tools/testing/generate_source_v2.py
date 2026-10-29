"""Freeze attributed public-domain excerpts without importing or calling InkReader.

Requires beautifulsoup4 for extraction only; runtime checks use frozen files.
Download the declared source pages into --cache; existing output is never replaced.
"""
import argparse
import base64
import copy
import hashlib
import json
import re
from pathlib import Path
from bs4 import BeautifulSoup, Tag

# Partition by entire work, including all its derived layouts and negative controls.
WORKS = [
 ('S01', 'development', 'en', 'pride', 'Pride and Prejudice', 'Jane Austen', 'https://www.gutenberg.org/cache/epub/1342/pg1342-images.html'),
 ('S02', 'development', 'fr', 'candide', 'Candide', 'Voltaire', 'https://www.gutenberg.org/files/4650/4650-h/4650-h.htm'),
 ('S03', 'development', 'es', 'quijote', 'Don Quijote', 'Miguel de Cervantes Saavedra', 'https://www.gutenberg.org/cache/epub/2000/pg2000-images.html'),
 ('S04', 'development', 'zh', 'xiyou', '西遊記', '吳承恩', 'https://www.gutenberg.org/cache/epub/23962/pg23962-images.html'),
 ('S05', 'holdout', 'en', 'dracula', 'Dracula', 'Bram Stoker', 'https://www.gutenberg.org/cache/epub/345/pg345-images.html'),
 ('S06', 'holdout', 'ja', 'kokoro', 'こころ', '夏目漱石', 'https://www.aozora.gr.jp/cards/000148/files/773_14560.html'),
 ('S07', 'holdout', 'it', 'pinocchio', 'Le avventure di Pinocchio', 'Carlo Collodi', 'https://www.gutenberg.org/cache/epub/52484/pg52484-images.html'),
 ('S08', 'holdout', 'de', 'heidi', 'Heidis Lehr- und Wanderjahre', 'Johanna Spyri', 'https://www.gutenberg.org/files/7500/7500-h/7500-h.htm'),
]

def clean(node):
    node = copy.copy(node)
    for removed in node.select('rt, rp, .caption, .pagenum, figure, img, .figcenter, .figleft, .figright'):
        removed.decompose()
    return ' '.join(node.get_text('', strip=False).split())

def extract(raw, stem):
    soup = BeautifulSoup(raw, 'html.parser')
    if stem == 'xiyou':
        heads = [p for p in soup.find_all('p') if re.match(r'^第[一二三四五六七八九十]+回\s', clean(p))][:6]
    elif stem == 'kokoro':
        heads = soup.find_all('h4')[:9]
    elif stem == 'heidi':
        heads = soup.find_all('h3')[:6]
    else:
        tag = 'h3' if stem == 'quijote' else 'h2'
        label = {'pride': r'(?i)^chapter\s', 'candide': r'^CHAPITRE\s', 'quijote': r'^Capítulo\s',
                 'dracula': r'^CHAPTER\s', 'pinocchio': r'^[IVX]+\.'}[stem]
        heads = [h for h in soup.find_all(tag) if re.match(label, clean(h))][:6]
    if len(heads) < 6: raise ValueError(stem + ': source structure differs')
    result = []
    for h in heads:
        paragraphs = []
        if stem == 'kokoro':
            # Aozora uses sibling text/ruby nodes and br, not p. Remove ruby readings
            # (retain rb text) and collect complete newline-delimited paragraphs.
            fragments = []
            for sibling in h.parent.next_siblings:
                if isinstance(sibling, Tag) and sibling.find('h4'): break
                if isinstance(sibling, Tag) and sibling.name == 'br': fragments.append('\n')
                elif isinstance(sibling, Tag): fragments.append(clean(sibling))
                else: fragments.append(str(sibling).replace('\r', ''))
            paragraphs = [' '.join(p.split()) for p in ''.join(fragments).splitlines() if p.strip()][:6]
        else:
            for node in h.next_elements:
                if not isinstance(node, Tag): continue
                if node is not h and node.name in ('h2', 'h3') and h not in node.parents: break
                if node.name == 'p' and h not in node.parents:
                    if stem == 'xiyou' and node in heads: break
                    text = clean(node)
                    if text and not re.fullmatch(r'[\[\]{}\d\s]+', text): paragraphs.append(text)
                    if len(paragraphs) == 6: break
        if not paragraphs: raise ValueError(stem + ': no complete source paragraph')
        result.append((clean(h), paragraphs))
    return result

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache', type=Path, required=True)
    p.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[2] / 'tests/fixtures/real-source-v2')
    args = p.parse_args()
    if args.output.exists(): raise SystemExit('Refusing to overwrite frozen source fixtures')
    files = {}; manifest = ['id\tpartition\tlanguage\tgroup\tkind\tcontract']; sources = []
    for ident, partition, language, stem, title, author, url in WORKS:
        raw = (args.cache / (stem + '.html')).read_bytes()
        chapters = extract(raw, stem)
        sources.append(dict(group=stem, title=title, author=author, url=url,
            sourceSha256=hashlib.sha256(raw).hexdigest(), partition=partition,
            headingCount=len(chapters), paragraphCount=sum(len(ps) for _, ps in chapters),
            extraction='First 6 chapter elements (9 for Japanese), up to first 6 complete paragraphs each; navigation, page numbers, image captions and ruby readings excluded; HTML whitespace collapsed; no parser output used.'))
        for variant in ('original', 'spaced', 'body-only'):
            case = ident + {'original':'A', 'spaced':'B', 'body-only':'N'}[variant]
            text = title + '\n\n'; gold = []
            for heading, paragraphs in chapters:
                if variant != 'body-only':
                    gold += ['T\t' + heading, 'L\t' + str(text.count('\n') + 1)]
                    text += ('\u3000' if variant == 'spaced' else '') + heading + '\n\n'
                text += ('\n\n\n' if variant == 'spaced' else '\n\n').join(paragraphs) + '\n\n'
                gold += ['P64\t' + base64.b64encode(paragraph.encode()).decode() for paragraph in paragraphs]
            # Arbitrary unnumbered German titles are a measured capability limit, not
            # silently relabelled as correct. Body-only controls must still preserve text.
            contract = 'capability' if stem == 'heidi' and variant != 'body-only' else 'required'
            manifest.append('\t'.join((case, partition, language, stem, variant, contract)))
            files[case + '.txt'] = text.strip() + '\n'
            files[case + '.gold'] = '\n'.join(gold) + '\n'
    files['manifest.tsv'] = '\n'.join(manifest) + '\n'
    files['sources.json'] = json.dumps(sources, ensure_ascii=False, indent=2) + '\n'
    args.output.mkdir(parents=True)
    for name, body in files.items(): (args.output / name).write_bytes(body.encode())
    hashes = {name: hashlib.sha256((args.output / name).read_bytes()).hexdigest() for name in files}
    (args.output / 'frozen-sha256.json').write_bytes((json.dumps(hashes, sort_keys=True, indent=2) + '\n').encode())
    print(f'Frozen {len(WORKS)} works / {len(WORKS)*3} cases; source groups do not overlap partitions')

if __name__ == '__main__': main()
