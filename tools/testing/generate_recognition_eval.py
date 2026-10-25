"""Authored expected headings/paragraphs; never imports or invokes the application parser."""
import base64,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]/'tests/fixtures/recognition-eval'
STORIES={
 'zh':'风从窗外吹来，她合上书，想起走过的道路。每一句文字和每一个段落都应完整保留。',
 'en':'The reader opened the window and remembered the journey. Every paragraph remains in its original order. ',
 'es':'La lectora abrió la ventana y recordó el camino. Cada párrafo conserva todas sus palabras. ',
 'ru':'Читатель открыл окно и вспомнил дорогу домой. Каждый абзац должен сохранить все слова. ',
 'ko':'그는 창문을 열고 집으로 돌아가는 길을 떠올렸다. 모든 문장과 문단은 그대로 남아 있어야 한다. ',
 'ar':'فتح القارئ النافذة وتذكر الطريق إلى البيت. يجب الاحتفاظ بكل كلمة وكل فقرة كما هي. ',
 'hi':'पाठक ने खिड़की खोली और घर लौटने का रास्ता याद किया। प्रत्येक शब्द और अनुच्छेद सुरक्षित रहना चाहिए। ',
 'vi':'Người đọc mở cửa sổ và nhớ con đường về nhà. Mọi câu chữ và đoạn văn đều phải được giữ nguyên. ',
 'th':'ผู้อ่านเปิดหน้าต่างและนึกถึงทางกลับบ้าน ทุกคำและทุกย่อหน้าควรคงอยู่ตามต้นฉบับ ',
 'ja':'彼女は窓を開けて本を読みました。すべての言葉と段落を元の順序で残してください。'}
POS=[
 ('es',['Capítulo 1 Amanecer','Capítulo 2 Regreso','Capítulo 3 Hogar']),
 ('ru',['Глава 1 Утро','Глава 2 Вечер','Глава 3 Дом']),
 ('ko',['제1장 시작','제2장 재회','제3장 귀환']),
 ('ar',['الفصل ١ البداية','الفصل ٢ العودة','الفصل ٣ المنزل']),
 ('hi',['अध्याय १ आरंभ','अध्याय २ वापसी','अध्याय ३ घर']),
 ('vi',['Chương 1 Khởi đầu','Chương 2 Trở lại','Chương 3 Quê nhà']),
 ('th',['บทที่ ๑ เริ่มต้น','บทที่ ๒ กลับมา','บทที่ ๓ บ้าน']),
 ('en',['Chapter ١ Dawn','Chapter ٢ Return','Chapter ٣ Home']),
 ('en',['Chapter १ Dawn','Chapter २ Return','Chapter ३ Home']),
 ('zh',['1','2','3']),('zh',['一','二','三']),('zh',['壹','贰','叁']),
 ('zh',['第1-2章 开始','第3-4章 继续','第5-6章 结束']),
 ('en',['Chapter 1–2 Dawn','Chapter 3–4 Return','Chapter 5–6 Home']),
 ('zh',['第一章 开始','第二章 继续','3. 山','4. 海','5. 家']),
 ('en',['Chapter 1 Dawn','Chapter 2 Day','3. Evening','4. Night','5. Home']),
 ('zh',['〔第一章〕初见','〔第二章〕重逢','〔第三章〕归来']),
 ('es',['〔Capítulo 1 Amanecer〕','〔Capítulo 2 Regreso〕','〔Capítulo 3 Hogar〕']),
 ('ja',['第１話 はじまり','第２話 再会','第３話 帰還']),
 ('zh',['第一章 '+ '很长但确实连续的标题'*10,'第二章 '+ '远方的风和归来的朋友'*10,'第三章 '+ '旅途继续而文字保持原样'*10]),
 ('zh',['第一章 春','第二章 夏','第三章 秋','第四章 冬']),
 ('en',['Chapter 1: Light','Chapter 2: Rain','Chapter 3: Wind']),
 ('zh',['第①章 星','第②章 月','第③章 海']),
 ('zh',['第12章 起点','第 1 3 章 重逢','第14章 归来'])]
HOLD=[
 ('es',['CAPÍTULO ７: Río','CAPÍTULO ８: Bosque','CAPÍTULO ９: Mar']),
 ('ru',['глава ٧ Север','глава ٨ Восток','глава ٩ Юг']),
 ('ko',['제７장 숲','제８장 바다','제９장 집']),
 ('ar',['الفصل ٧ النهر','الفصل ٨ الغابة','الفصل ٩ البحر']),
 ('hi',['अध्याय ७ नदी','अध्याय ८ जंगल','अध्याय ९ समुद्र']),
 ('vi',['Chương 7 Dòng sông','Chương 8 Rừng xanh','Chương 9 Biển']),
 ('th',['บทที่ ๗ แม่น้ำ','บทที่ ๘ ป่า','บทที่ ๙ ทะเล']),
 ('zh',['7','8','9','10']),('zh',['七','八','九','十']),
 ('zh',['第7～8章 远方','第9～10章 归途','第11～12章 家园']),
 ('en',['Chapter ٧–٨ River','Chapter ٩–١٠ Forest','Chapter ١١–١٢ Sea']),
 ('zh',['第七章 河','第八章 林','9. 风','10. 雨','11. 家']),
 ('en',['Chapter 7 River','Chapter 8 Forest','9. Wind','10. Rain','11. Home']),
 ('ja',['〔第七話〕川','〔第八話〕森','〔第九話〕海']),
 ('zh',['第一章 光','第二章 雨','第三章 风','第四章 夜']),
 ('zh',['第七章 '+ '这是属于这段旅途的长标题'*9,'第八章 '+ '朋友归来时窗外仍然有风'*9,'第九章 '+ '黎明之后道路继续向前延伸'*9])]

def build():
 ROOT.mkdir(parents=True,exist_ok=True);rows=[]
 def save(ident,part,lang,group,titles,source,paragraphs):
  (ROOT/(ident+'.txt')).write_text(source,encoding='utf-8')
  gold=[];cursor=0
  for title in titles:
   position=source.index(title+'\n',cursor);cursor=position+len(title)
   gold += ['T\t'+title,'L\t'+str(source.count('\n',0,position)+1)]
  gold += ['P64\t'+base64.b64encode(p.encode()).decode() for p in paragraphs]
  (ROOT/(ident+'.gold')).write_text('\n'.join(gold)+'\n',encoding='utf-8')
  rows.append([ident,part,lang,group])
 for part,items in [('development',POS),('holdout',HOLD)]:
  prefix='D' if part=='development' else 'H'
  for i,(lang,titles) in enumerate(items,1):
   ident=f'{prefix}{i:02d}';paragraphs=[ident+f'_P{j} '+STORIES[lang]*(5+j%3) for j in range(len(titles))]
   source=ident+'\n\n'+'\n\n'.join(t+'\n\n'+p for t,p in zip(titles,paragraphs))
   # Contents are independently authored from the explicit heading list, including page leaders.
   if (part=='development' and i==21) or (part=='holdout' and i==15):
    toc='目录\n\n'+'\n'.join(t+' …… '+str(10+j*17) for j,t in enumerate(titles))
    source=ident+'\n\n'+toc+'\n\n'+source[len(ident)+2:]
   save(ident,part,lang,part+'-positive-'+str(i),titles,source,paragraphs)
  negatives=16 if part=='development' else 8
  for j in range(negatives):
   ident=f'{prefix}N{j+1:02d}';lang='zh' if j%2==0 else 'en';paragraphs=[ident+'_P0 '+STORIES[lang]*7]
   if j%4==0:
    # Page numbers follow substantive prose, rather than introducing the document.
    paragraphs += [str(n)+'\n\n'+ident+f'_P{n} '+STORIES[lang]*4 for n in [1,2,3]]
   elif j%4==1:
    paragraphs += [str(y)+'\n\n'+ident+f'_Y{y} '+STORIES[lang]*4 for y in [1984,1985,1986]]
   elif j%4==2:
    paragraphs += ['第一章里提到的事情','第二章的内容','第三章里说到的结局']
   else:
    paragraphs += ['操作步骤：' if lang=='zh' else 'Checklist:']
    paragraphs += [str(n)+'. '+('先准备材料' if lang=='zh' else 'Prepare the materials')+'\n\n'+ident+f'_S{n} '+STORIES[lang]*4 for n in [1,2,3,4]]
   source=ident+'\n\n'+'\n\n'.join(paragraphs)
   save(ident,part,lang,part+'-negative-'+str(j),[],source,paragraphs)
 (ROOT/'manifest.tsv').write_text('id\tpartition\tlanguage\tgroup\n'+''.join('\t'.join(r)+'\n' for r in rows),encoding='utf-8')
 hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in ROOT.iterdir() if p.suffix in ['.txt','.gold','.tsv']}
 (ROOT/'frozen-sha256.json').write_text(json.dumps(hashes,sort_keys=True,indent=2)+'\n')
 print('Authored evaluation cases:',len(rows))

if __name__=='__main__':
 if ROOT.exists():raise SystemExit('Refusing to overwrite authored expectations. Review and version dataset changes explicitly.')
 build()
