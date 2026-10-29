"""Freeze a larger, Chinese-focused corpus from attributed cached public-domain HTML.

No InkReader imports, no parser-derived labels. Requires beautifulsoup4 only to regenerate.
"""
import argparse, base64, hashlib, json, re
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from bs4 import BeautifulSoup, Tag
from generate_source_v2 import clean

# Entire works remain in one partition. Natural, unnumbered headings are declared
# capability probes before running the recognizer; their paragraphs remain mandatory.
WORKS = [
 ('紅樓夢',24264,'曹雪芹','development','zh'), ('水滸傳',23863,'施耐庵','holdout','zh'),
 ('封神演義',23910,'許仲琳','development','zh'), ('儒林外史',24032,'吳敬梓','holdout','zh'),
 ('鏡花緣',23818,'李汝珍','development','zh'), ('老殘遊記',25124,'劉鶚','holdout','zh'),
 ('官場現形記',24138,'李伯元','development','zh'), ('孽海花',25128,'曾樸','holdout','zh'),
 ('三俠五義',25376,'石玉昆','development','zh'), ('隋唐演義',23835,'褚人獲','holdout','zh'),
 ('東周列國志',25349,'馮夢龍','development','zh'), ('喻世明言',27582,'馮夢龍','development','zh'),
 ('警世通言',24141,'馮夢龍','development','zh'), ('醒世恆言',24239,'馮夢龍','development','zh'),
 ('二刻拍案驚奇',26729,'凌濛初','holdout','zh'), ('初刻拍案驚奇',57248,'凌濛初','holdout','zh'),
 ('聊齋志異',51828,'蒲松齡','development','zh'), ('吶喊',27166,'魯迅','holdout','zh'),
 ('朝花夕拾',25271,'魯迅','holdout','zh'), ('野草',25242,'魯迅','holdout','zh'),
 ('浮生六記',25192,'沈復','development','zh'), ('圍爐夜話',25421,'王永彬','holdout','zh'),
 ('牡丹亭',23849,'湯顯祖','development','zh'), ('桃花扇',24234,'孔尚任','holdout','zh'),
 ('Frankenstein',84,'Mary Shelley','development','en'), ('Jane Eyre',1260,'Charlotte Brontë','holdout','en'),
 ('Moby Dick',2701,'Herman Melville','development','en'), ('The Adventures of Sherlock Holmes',1661,'Arthur Conan Doyle','holdout','en'),
 ('The Wonderful Wizard of Oz',55,'L. Frank Baum','development','en'), ('The Adventures of Tom Sawyer',74,'Mark Twain','holdout','en'),
 ('Treasure Island',120,'Robert Louis Stevenson','development','en'), ('Great Expectations',1400,'Charles Dickens','holdout','en'),
]

LITERARY = {
 27166: ['一件小事','狂人日記','孔乙己','藥','明天','頭髮的故事','風波','故鄉','阿Q正傳','端午節','白光','兔和貓','鴨的喜劇','社戲'],
 25271: ['狗·貓·鼠','阿長與山海經','《二十四孝圖》','五猖會','無常','從百草園到三味書屋','父親的病','瑣記','藤野先生','范愛農'],
 25242: ['秋夜','影的告別','求乞者','我的失戀','復仇','復仇 (其二)','希望','雪','風箏','好的故事','過客','死火','狗的駁詰','失掉的好地獄','墓碣文','頹敗線的顫動','立論','死後','這樣的戰士','聰明人和傻子和奴才','臘葉','淡淡的血痕中','一覺'],
}

def extract_chapters(soup, ident, language):
    if language == 'en':
        pattern = r'(?i)^chapter\s' if ident not in (120,1661) else r'^[IVXLCDM]+[.\s]'
        heads=[h for h in soup.find_all('h2') if re.match(pattern,clean(h))][:8]
        result=[]
        for head in heads:
            paragraphs=[]
            for node in head.next_elements:
                if isinstance(node,Tag) and node.name=='h2' and head not in node.parents: break
                if isinstance(node,Tag) and node.name=='p' and head not in node.parents:
                    text=clean(node)
                    if len(text)>=80: paragraphs.append(text)
                    if len(paragraphs)>=4: break
            if paragraphs: result.append((clean(head),paragraphs))
        return result
    # Retain original paragraph boundaries and hard-wrapped source lines. Some PG
    # Chinese editions put a heading and its whole chapter in one p/div; those are
    # explicitly source-block excerpts rather than claimed complete HTML paragraphs.
    for node in soup.find_all('br'): node.replace_with('\n')
    for node in soup.find_all(['p','h2','h3','h4']):
        node.insert_before('\x1e'); node.insert_after('\x1e')
    raw=soup.body.get_text('',strip=False).replace('\r','')
    numeric=re.compile(r'^第[0-9０-９零〇一二三四五六七八九十百千兩两]{1,10}[回卷章集齣出折]')
    known=set(LITERARY.get(ident,[]))
    rows=[]; title=None; body=[]
    def finish():
        nonlocal body
        text=' '.join(' '.join(body).split())
        if title is not None and len(text)>=80: rows.append((title,[text]))
        body=[]
    # Source blank lines delimit body blocks. Headings are authored from the source
    # labels; prose is never classified by InkReader to produce its own answers.
    paragraphs=[]
    for block in raw.split('\x1e'):
        text=' '.join(block.split())
        if len(text)>=80: paragraphs.append(text)
    if ident==25421: return [('',paragraphs[2:26])]
    source_lines=[]
    for block in raw.split('\x1e'):
        source_lines.extend(line for line in block.splitlines() if line.strip())
        source_lines.append('')
    for line in source_lines:
        text=' '.join(line.split())
        heading=None; remainder=''
        if ident in LITERARY:
            if text in known: heading=text
            elif any(text.startswith(t+' ') for t in known):
                heading=next(t for t in known if text.startswith(t+' ')); remainder=text[len(heading):].strip()
        elif ident==51828:
            if re.fullmatch(r'〈[^〈〉]{1,30}〉',text): heading=text
        elif numeric.match(text):
            cut=re.search(r'\s+(?:話說|话说|卻說|却说|自古|詩曰|诗曰|詞曰|词曰|贊曰|赞曰)',text)
            if cut: heading=text[:cut.start()]; remainder=text[cut.start():].strip()
            elif len(text)<=100: heading=text
        if heading:
            finish(); title=heading
            if remainder: body.append(remainder)
        elif title is not None:
            # Preserve a few complete source body blocks, capped before the next
            # actual source heading. No characters within a retained block are cut.
            if not text:
                block=' '.join(body).strip()
                if len(block)>=80:
                    if rows and rows[-1][0]==title and len(rows[-1][1])<4: rows[-1][1].append(block)
                    elif not rows or rows[-1][0]!=title: rows.append((title,[block]))
                body=[]
            else: body.append(text)
    finish()
    # A single source heading can own several p elements; avoid duplicating its
    # label while keeping the first four substantial blocks in their source order.
    merged=[]
    for heading,blocks in rows:
        if merged and merged[-1][0]==heading:
            merged[-1][1].extend(blocks[:max(0,4-len(merged[-1][1]))])
        else: merged.append((heading,blocks[:4]))
    return merged[:8]

def fetch(cache, book):
    title, ident, *_ = book
    path = cache / f'{ident}.html'
    if not path.exists():
        url = f'https://www.gutenberg.org/cache/epub/{ident}/pg{ident}-images.html'
        with urlopen(Request(url, headers={'User-Agent':'InkReader attributed regression corpus/1.0'}), timeout=90) as response:
            data = response.read(12 * 1024 * 1024)
        path.write_bytes(data)
    return title, path.stat().st_size

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache',type=Path,required=True)
    p.add_argument('--fetch',action='store_true')
    p.add_argument('--inspect',action='store_true')
    p.add_argument('--output',type=Path,default=Path('tests/fixtures/real-source-v3'))
    args=p.parse_args(); args.cache.mkdir(parents=True,exist_ok=True)
    if args.fetch:
        with ThreadPoolExecutor(max_workers=2) as pool:
            for title,size in pool.map(lambda b:fetch(args.cache,b),WORKS): print(title,size,flush=True)
    if args.inspect:
        for title,ident,*_ in WORKS:
            soup=BeautifulSoup((args.cache/f'{ident}.html').read_bytes(),'html.parser')
            for n in soup.select('header, footer, #pg-header, #pg-footer'): n.decompose()
            heads=[(h.name,clean(h)[:100]) for h in soup.find_all(['h2','h3','h4'])][:11]
            print(ident,title,heads,flush=True)
        return
    if args.output.exists(): raise SystemExit('Refusing to overwrite frozen corpus')
    files={}; manifest=['id\tpartition\tlanguage\tgroup\tkind\tcontract']; provenance=[]
    for sequence,(title,ident,author,partition,language) in enumerate(WORKS,1):
        raw=(args.cache/f'{ident}.html').read_bytes()
        soup=BeautifulSoup(raw,'html.parser')
        for n in soup.select('header,footer,#pg-header,#pg-footer'): n.decompose()
        chapters=extract_chapters(soup,ident,language)
        minimum=2 if ident in LITERARY else 3
        if len(chapters)<minimum and ident!=25421: raise ValueError(f'{ident}: insufficient independently extracted headings: {len(chapters)}')
        capability=ident in LITERARY or ident==51828
        provenance.append(dict(group=str(ident),title=title,author=author,language=language,partition=partition,
            url=f'https://www.gutenberg.org/cache/epub/{ident}/pg{ident}-images.html',sourceSha256=hashlib.sha256(raw).hexdigest(),
            headings=[h for h,_ in chapters if h],blocks=sum(len(ps) for _,ps in chapters),
            extraction='First up to eight source headings; first up to four substantial source body blocks each. HTML whitespace collapsed. Chinese hard-wrapped p/div text uses source-block excerpts, not necessarily whole HTML paragraphs. No InkReader output used.'))
        for variant in ('original','spaced','body-only'):
            name=f'C{sequence:02d}'+{'original':'A','spaced':'B','body-only':'N'}[variant]
            text=title+'\n\n'; gold=[]
            for heading,blocks in chapters:
                if heading and variant!='body-only':
                    gold+=['T\t'+heading,'L\t'+str(text.count('\n')+1)]
                    text+=('　' if variant=='spaced' else '')+heading+'\n\n'
                text+= ('\n\n\n' if variant=='spaced' else '\n\n').join(blocks)+'\n\n'
                gold+=['P64\t'+base64.b64encode(block.encode()).decode() for block in blocks]
            contract='capability' if capability and variant!='body-only' else 'required'
            manifest.append('\t'.join((name,partition,language,str(ident),variant,contract)))
            files[name+'.txt']=text.strip()+'\n'; files[name+'.gold']='\n'.join(gold)+'\n'
    files['manifest.tsv']='\n'.join(manifest)+'\n';files['sources.json']=json.dumps(provenance,ensure_ascii=False,indent=2)+'\n'
    args.output.mkdir(parents=True)
    for name,text in files.items(): (args.output/name).write_bytes(text.encode())
    hashes={name:hashlib.sha256((args.output/name).read_bytes()).hexdigest() for name in files}
    (args.output/'frozen-sha256.json').write_bytes((json.dumps(hashes,sort_keys=True,indent=2)+'\n').encode())
    print(f'Frozen {len(WORKS)} works, {len(WORKS)*3} cases')

if __name__=='__main__': main()
