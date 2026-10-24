"""Generate authored TXT acceptance fixtures and independent expectations."""
from pathlib import Path
import json, collections

ROOT = Path(__file__).resolve().parents[2] / 'tests' / 'fixtures' / 'txt-corpus'
ROOT.mkdir(parents=True, exist_ok=True)
cases = []

def add(label, titles=None, *, raw=None, encoding='utf-8', newline='\n', reject=False, expected=None, blocks=None, plain=False):
    ident = f'C{len(cases)+1:02d}'
    paragraphs = [] if blocks is None else blocks
    if raw is None:
        paragraphs = [f'TXT_AUDIT_{ident}_S{i:02d} ' + ('测试正文保留人物对白、数字42和普通网址 https://example.org/reference 。' * 5) for i in range(len(titles))]
        raw = '\n\n'.join(t + '\n\n' + p for t, p in zip(titles, paragraphs))
    name = f'{ident}_{label}.txt'
    if not reject:
        raw = f'AUDIT_{ident}\n\n' + raw
    data = raw.replace('\n', newline).encode(encoding)
    (ROOT / name).write_bytes(data)
    cases.append(dict(id=ident, file=name, scenario=label, encoding=encoding, newline=repr(newline),
                      reject=reject, titles=expected if expected is not None else titles, blocks=paragraphs,
                      plain=plain, source_text=raw))

forms = [
 ('arabic', ['第1章 初见','第2章 重逢','第3章 归来']),
 ('chinese', ['第一章 初见','第二章 重逢','第三章 归来']),
 ('financial', ['第壹章 初见','第贰章 重逢','第叁章 归来']),
 ('traditional_financial', ['第壹章 初見','第貳章 重逢','第參章 歸來']),
 ('fullwidth', ['第１章 初见','第２章 重逢','第３章 归来']),
 ('bare_chapter', ['一章 初见','二章 重逢','三章 归来']),
 ('hui', ['第一回 初见','第二回 重逢','第三回 归来']),
 ('traditional_episode', ['第一話 初見','第二話 重逢','第三話 歸來']),
 ('english', ['Chapter 1 Dawn','Chapter 2 Return','Chapter 3 Home']),
 ('english_words', ['Chapter One Dawn','Chapter Two Return','Chapter Three Home']),
 ('roman', ['Chapter I Dawn','Chapter II Return','Chapter III Home']),
 ('unicode_roman', ['Chapter Ⅰ Dawn','Chapter Ⅱ Return','Chapter Ⅲ Home']),
 ('abbreviation', ['Ch. #1 Dawn','Chap. #2 Return','Chapter No. 3 Home']),
 ('brackets', ['【第一章 初见】','【第二章 重逢】','【第三章 归来】']),
 ('decorations', ['★ 第一章 初见 ★','◆ 第二章 重逢 ◆','☆ 第三章 归来 ☆']),
 ('labels', ['正文：第一章 初见','VIP：第二章 重逢','正文 第三章 归来']),
 ('specials', ['序章','第一章 初见','第二章 重逢','最终章（上）','最终章（下）','番外十三']),
 ('bookends', ['自序','第一章 初见','第二章 重逢','后日谈','后记']),
 ('equal_segments', ['第十二章（上）','第十二章（中）','第十二章（下）','第十三章']),
 ('gap_spaced', ['第十二章 开始','第 十 三 章 重逢','第十四章 结束']),
 ('gap_financial', ['第１２章 开始','第 壹 拾 參 章 重逢','第十四章 结束']),
 ('gap_segment', ['第十二章 开始','第 十 三 章 重逢（上）','第 １ ３ 章 重逢（下）','第十四章 结束']),
 ('gap_long', ['第十二章 开始','第十三章 '+ '这是一个很长的章节标题'*5,'第十四章 结束']),
 ('gap_missing', ['第十二章 开始','第十四章 结束']),
]
for label, titles in forms: add(label, titles)
body = '边界测试正文保留。TXT_BOUNDARY_ANCHOR ' + '普通内容和数字42均需保留。'*20
def boundary(label, middle, expected=None):
    raw = f'第十二章 开始\n\n{body}\n\n{middle}\n\n{body}\n\n第十四章 结束\n\n{body}'
    add(label, raw=raw, expected=expected or ['第十二章 开始','第十四章 结束'], blocks=[body]*3)
boundary('ambiguous', f'第 十 三 章 甲\n\n{body}\n\n第 十 三 章 乙')
cases[-1]['blocks'] = [body]*4
boundary('quoted_reference','第 十 三 章：的内容')
boundary('sentence_reference','第 十 三 章 他来了，这只是正文中的一句话。')
boundary('fenced_example',f'```text\n\n第 十 三 章 代码示例\n\n{body}\n```')
cases[-1]['blocks'] = [body]*4
boundary('volume_boundary','第二卷 风雪\n\n第 十 三 章 新卷说明')
add('volume_reset', raw=f'第一卷 春\n第一章 初见\n\n{body}\n\n第二章 重逢\n\n{body}\n\n第二卷 冬\n第一章 再起\n\n{body}\n\n第二章 归来\n\n{body}', expected=['第一章 初见','第二章 重逢','第一章 再起','第二章 归来'], blocks=[body]*4)
add('toc_repeated', raw='目录\n第一章 初见\n第二章 重逢\n第三章 归来\n第四章 结束\n\n'+'\n\n'.join(t+'\n\n'+body for t in ['第一章 初见','第二章 重逢','第三章 归来','第四章 结束']), expected=['第一章 初见','第二章 重逢','第三章 归来','第四章 结束'], blocks=[body]*4)
add('sections_inside_chapters',raw=f'第一章 初见\n\n{body}\n\n第一节 小节\n\n{body}\n\n第二节 小节\n\n{body}\n\n第二章 重逢\n\n{body}',expected=['第一章 初见','第二章 重逢'],blocks=[body]*4)
for encoding in ('utf-8-sig','gbk','gb18030','utf-16','utf-16-be'):
    # Explicit big-endian BOM; BOM-less CJK is intentionally ambiguous.
    add('encoding_'+encoding.replace('-','_'), ['第一章 初见','第二章 重逢','最终章（上）'],encoding=encoding)
    if encoding == 'utf-16-be':
        path = ROOT / cases[-1]['file']; path.write_bytes(b'\xfe\xff'+path.read_bytes())
for label, nl in [('crlf','\r\n'),('cr_only','\r')]:
    add('newline_'+label,['第一章 初见','第二章 重逢','第三章 归来'],newline=nl)
add('zero_width', ['第\u200b一章 初见','第二章 重逢'],expected=['第一章 初见','第二章 重逢'])
add('no_blank_lines',raw='第一章 初见\n'+body+'\n第二章 重逢\n'+body,expected=['第一章 初见','第二章 重逢'],blocks=[body]*2)
add('emoji',raw='第一章 初见\n\n'+body+'🦖🐉🙂𠮷\n\n第二章 重逢\n\n'+body+'🌙',expected=['第一章 初见','第二章 重逢'],blocks=[body]*2)
add('plain_no_heading',raw='普通无标题正文。'*600, expected=None,plain=True)
add('long_single_paragraph',raw='无标题长段落包含数字42和emoji🐉。'*1200, expected=None,plain=True)
add('empty',raw='',reject=True)
add('whitespace_only',raw='  \n\t\n　\n',reject=True)
add('bom_only',raw='\ufeff',reject=True)

# Second acceptance round: real multilingual bodies, not just translated labels.
languages = {
    'japanese': '雨の夜、竜の少女は古い図書館で物語の続きを読んだ。数字42と約束を忘れない。',
    'english': 'The dragon girl opened the old book and remembered the promise from winter. Keep number 42 and every word.',
    'french': 'À Noël, la jeune lectrice découvre une île mystérieuse. Éléonore garde son cœur et le numéro 42.',
    'spanish': 'La niña encontró un libro extraño. ¿Quién recordó la promesa? ¡Mañana volverá con el número 42!',
    'german': 'Die Bücher erzählen über große Wälder. Weißt du, warum die Tür geöffnet wurde? Die Zahl bleibt 42.',
    'russian': 'Девушка открыла старую книгу и вспомнила зимнее обещание. Сохраним каждое слово и число 42.',
    'korean': '용 소녀는 오래된 도서관에서 책을 읽었다. 겨울의 약속과 숫자 42를 잊지 않았다.',
    'arabic': 'فتحت الفتاة الكتاب القديم وتذكرت وعد الشتاء. يجب الاحتفاظ بكل الكلمات وبالرقم 42.',
    'hindi': 'लड़की ने पुरानी किताब खोली और सर्दियों का वादा याद किया। हर शब्द और संख्या 42 सुरक्षित रहे।',
    'greek': 'Η κοπέλα άνοιξε το παλιό βιβλίο και θυμήθηκε την υπόσχεση. Κρατάμε κάθε λέξη και τον αριθμό 42.',
    'vietnamese': 'Cô gái mở cuốn sách cũ và nhớ lời hứa mùa đông. Giữ nguyên tiếng Việt, dấu thanh và số 42.',
    'thai': 'เด็กหญิงเปิดหนังสือเก่าและนึกถึงคำสัญญาในฤดูหนาว เก็บข้อความทั้งหมดและตัวเลข 42 ไว้',
    'mixed': '龙娘 / 竜の少女 / dragon girl / فتاة التنين。é e\u0301 ñ ß Ω Я 한글 ภาษาไทย 🙂🐉𠮷。',
}
def multilingual(label, titles, paragraph, desired=False):
    ident = f'C{len(cases)+1:02d}'
    blocks = [f'LANG_{ident}_{i} ' + paragraph * max(3, 500//len(paragraph)+1) for i in range(len(titles))]
    add(label, raw='\n\n'.join(t+'\n\n'+b for t,b in zip(titles,blocks)), expected=None if desired else titles, blocks=blocks)
    if desired:
        cases[-1]['desired_titles'] = titles
        cases[-1]['recognition'] = 'exploratory'

for lang, paragraph in languages.items():
    multilingual('lang_'+lang, ['Chapter 1 — 朝','Chapter 2 — 昼','Chapter 3 — 夜'], paragraph)
    multilingual('mixed_marker_'+lang, ['第一章 朝','Chapter 2 Noon','第三話 夜'], paragraph)

known = [
 ['第01章 初见','第02章 重逢','第03章 归来'],
 ['第 1 章 初见','第 2 章 重逢','第 3 章 归来'],
 ['第十一章 初见','第十二章 重逢','第十三章 归来'],
 ['第一〇一章 初见','第一〇二章 重逢','第一〇三章 归来'],
 ['第壹佰零壹章 初见','第壹佰零贰章 重逢','第壹佰零叁章 归来'],
 ['第壹佰零壹章 初見','第壹佰零貳章 重逢','第壹佰零參章 歸來'],
 ['1章：初见','2章：重逢','3章：归来'],
 ['1、初见','2、重逢','3、归来'],
 ['一、初见','二、重逢','三、归来'],
 ['（一）初见','（二）重逢','（三）归来'],
 ['(1) 初见','(2) 重逢','(3) 归来'],
 ['1. Dawn','2. Return','3. Home'],
 ['◆ 01','◆ 02','◆ 03'], ['§ 1','§ 2','§ 3'],
 ['① 初见','② 重逢','③ 归来'],
 ['Chapter XI Dawn','Chapter XII Return','Chapter XIII Home'],
 ['Chapter Twenty-one Dawn','Chapter Twenty-two Return','Chapter Twenty-three Home'],
 ['Episode 1 朝','Episode 2 昼','Episode 3 夜'],
 ['Part 1 Dawn','Part 2 Return','Part 3 Home'],
 ['第一节 初见','第二节 重逢','第三节 归来'],
 ['第1幕 朝','第2幕 昼','第3幕 夜'],
 ['第1折 朝','第2折 昼','第3折 夜'],
 ['【第1章】初见','【第2章】重逢','【第3章】归来'],
 ['[Chapter 1 Dawn]','[Chapter 2 Return]','[Chapter 3 Home]'],
 ['「第一章 初见」','「第二章 重逢」','「第三章 归来」'],
 ['(第一章 初见)','(第二章 重逢)','(第三章 归来)'],
 ['※ 第一章 初见 ※','※ 第二章 重逢 ※','※ 第三章 归来 ※'],
 ['ＶＩＰ：第一章 初见','ＶＩＰ：第二章 重逢','ＶＩＰ：第三章 归来'],
 ['第一章 初见（上）','第一章 初见（中）','第一章 初见（下）'],
 ['第一章 初见（1）','第一章 初见（2）','第二章 重逢'],
 ['序曲','第一章 初见','最终回','外傳一'],
 ['プロローグ','第一話 朝','第二話 昼','エピローグ'],
 ['Prologue','Chapter 1 Dawn','Chapter 2 Home','Epilogue'],
 ['第一章 初见','间章','第二章 重逢','后日谈'],
 ['第十二章 开始','【第 十 三 章】重逢','第十四章 结束'],
 ['第十二章 开始','### 第十三章 重逢','第十四章 结束'],
 ['第十二章 开始','第 十 三 章 重逢','第 十 四 章 归来','第十五章 结束'],
 ['第十二章 开始','第 十 四 章 归来','第十五章 结束'],
 ['第十四章 倒序','第十三章 倒序','第十二章 倒序'],
 ['第一百章 卷尾','第一章 重启','第二章 继续'],
]
for n, titles in enumerate(known):
    multilingual(f'form_{n+1:02d}', titles, languages['mixed'])
    if n == 38:
        # Import UI explicitly promises numeric sorting; splitter-only tests preserve source order.
        cases[-1]['source_titles'] = titles
        cases[-1]['titles'] = list(reversed(titles))
        cases[-1]['ordering_reason'] = 'Existing import contract: numeric chapter order (README chapter sorting)'

# Native localized marker names are probes, not silently counted as supported.
native = [
 ['第1話 はじまり','第2話 再会','第3話 帰還'],
 ['一、はじまり','二、再会','三、帰還'],
 ['Capítulo 1 Amanecer','Capítulo 2 Regreso','Capítulo 3 Hogar'],
 ['Chapitre 1 Aube','Chapitre 2 Retour','Chapitre 3 Maison'],
 ['Kapitel 1 Morgen','Kapitel 2 Rückkehr','Kapitel 3 Zuhause'],
 ['Глава 1 Рассвет','Глава 2 Возвращение','Глава 3 Дом'],
 ['제1장 시작','제2장 재회','제3장 귀환'],
 ['الفصل ١ البداية','الفصل ٢ العودة','الفصل ٣ المنزل'],
 ['अध्याय १ आरंभ','अध्याय २ वापसी','अध्याय ३ घर'],
 ['Chương 1 Khởi đầu','Chương 2 Trở lại','Chương 3 Quê nhà'],
 ['บทที่ ๑ เริ่มต้น','บทที่ ๒ กลับมา','บทที่ ๓ บ้าน'],
 ['1','2','3'], ['壹','贰','叁'], ['一','二','三'],
 ['Chapter ١ Dawn','Chapter ٢ Return','Chapter ٣ Home'],
 ['Chapter १ Dawn','Chapter २ Return','Chapter ३ Home'],
 ['第①章 初见','第②章 重逢','第③章 归来'],
 ['第 1-2 章 合章','第 3-4 章 合章','第 5-6 章 合章'],
 ['〔第一章〕初见','〔第二章〕重逢','〔第三章〕归来'],
 ['第一章·初见','第二章·重逢','第三章·归来'],
 ['CHAPTER\t1\tDawn','CHAPTER\t2\tReturn','CHAPTER\t3\tHome'],
 ['第一章 '+ '超长标题'*25,'第二章 '+ '超长标题'*25,'第三章 '+ '超长标题'*25],
]
for n,titles in enumerate(native):
    multilingual(f'native_probe_{n+1:02d}',titles,languages[list(languages)[n%len(languages)]],desired=True)

for n, middle in enumerate([
 '第 十 三 章：的内容','第 十 三 章 里提到的约定','第 十 三 章 他来了，故事结束了。',
 '【第 十 三 章）','[第 十 三 章】','（第 十 三 章]',
 '第 十 三 节 小节','第 十 三 章 “引用的章节”','「第 十 三 章 引用」',
 '第 十 三 章 他问：你是谁？','Chapter XIII is mentioned in this paragraph',
 'Chapter XIII. is an example quoted from another book',
 '第 十 三 章 一个标题\n\n'+body+'\n\n第 十 三 章 另一个标题',
 '第二卷 风雨\n\n第 十 三 章 卷首说明',
 '~~~\n\n第 十 三 章 代码示例\n\n'+body+'\n~~~',
 '第 十 四 章 归来\n\n'+body+'\n\n第 十 三 章 倒序',
 '第十三章'+ '这是一段很长的正文句子。'*12,
 '番外十三','最终章（上）',
]):
    # The last two are real special headings, not negative references.
    expected = ['第十二章 开始',middle,'第十四章 结束'] if n >= 17 else ['第十二章 开始','第十四章 结束']
    boundary(f'hostile_{n+1:02d}',middle,expected=expected)
    if n >= 17:
        # Existing front/back matter policy moves extras/finales to the end on import.
        cases[-1]['source_titles'] = expected
        cases[-1]['titles'] = ['第十二章 开始','第十四章 结束',middle]
        cases[-1]['ordering_reason'] = 'Existing import contract: back matter follows numbered chapters'
    cases[-1]['blocks'] = [body]*cases[-1]['source_text'].count(body)

(ROOT / 'manifest.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'Generated {len(cases)} TXT cases at {ROOT}')
if __import__('sys').argv[-1] == '--push':
    import subprocess, hashlib
    from emulator_device import emulator_adb
    adb = emulator_adb()
    target = f'/sdcard/Download/TXT{len(cases)}'
    subprocess.run(adb + ['shell', 'mkdir', '-p', target], check=True)
    subprocess.run(adb + ['push'] + [c['file'] for c in cases] + [target+'/'], cwd=ROOT, check=True, capture_output=True)
    hashes = subprocess.run(adb + ['shell', f'sha256sum {target}/*.txt'], check=True, capture_output=True).stdout.decode().splitlines()
    actual = {line.split()[-1].split('/')[-1]: line.split()[0] for line in hashes}
    for case in cases:
        assert actual.get(case['file']) == hashlib.sha256((ROOT / case['file']).read_bytes()).hexdigest(), case['file']
    print(f'Verified SHA-256 of all {len(cases)} emulator files')
