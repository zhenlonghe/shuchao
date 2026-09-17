import os, zlib, struct, zipfile, random, shutil
random.seed(7)
ROOT = 'reader-test'
shutil.rmtree(ROOT, ignore_errors=True)

def png(w, h, rgb, stripe):
    row_a = b'\x00' + bytes(rgb) * w
    row_b = b'\x00' + bytes(stripe) * w
    raw = b''.join(row_b if (y // 40) % 4 == 0 else row_a for y in range(h))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')

CONTAINER = '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'

def epub(path, title, author, cover=True, size=(600, 900)):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    color = [random.randint(40, 230) for _ in range(3)]
    stripe = [random.randint(0, 255) for _ in range(3)]
    manifest = '<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>'
    meta = f'<dc:title>{title}</dc:title><dc:language>zh</dc:language>'
    if author: meta += f'<dc:creator>{author}</dc:creator>'
    if cover:
        manifest += '<item id="cover-img" href="images/cover.png" media-type="image/png" properties="cover-image"/>'
    opf = f'<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">{title}</dc:identifier>{meta}</metadata><manifest>{manifest}</manifest><spine><itemref idref="ch1"/></spine></package>'
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr(zipfile.ZipInfo('mimetype'), 'application/epub+zip')
        z.writestr('META-INF/container.xml', CONTAINER, zipfile.ZIP_DEFLATED)
        z.writestr('OEBPS/content.opf', opf, zipfile.ZIP_DEFLATED)
        z.writestr('OEBPS/ch1.xhtml', f'<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>{title}</h1><p>正文</p></body></html>', zipfile.ZIP_DEFLATED)
        if cover: z.writestr('OEBPS/images/cover.png', png(*size, color, stripe), zipfile.ZIP_DEFLATED)

singles = [('三体', '刘慈欣'), ('活着', '余华'), ('百年孤独', '加西亚·马尔克斯'), ('The Pragmatic Programmer', 'Hunt'), ('围城', '钱锺书'),
           ('白夜行', '东野圭吾'), ('1984', 'George Orwell'), ('挪威的森林', '村上春树')]
for t, a in singles: epub(f'{ROOT}/{t}.epub', t, a)
epub(f'{ROOT}/没有封面的书.epub', '没有封面的一本名字特别特别长的书用来测试占位封面的换行', None, cover=False)
epub(f'{ROOT}/宽封面.epub', '宽封面', '测试', size=(900, 600))
open(f'{ROOT}/notes.txt', 'w').write('ignored')
open(f'{ROOT}/broken.epub', 'wb').write(b'not a zip at all')

for i in range(1, 92): epub(f'{ROOT}/海贼王/第{i}卷.epub', f'海贼王 第{i}卷', '尾田荣一郎')
for i in range(1, 43): epub(f'{ROOT}/龙珠/{"完全版" if i > 34 else "单行本"}/Vol.{i:02d}.epub', f'龙珠 Vol.{i}', '鸟山明')
open(f'{ROOT}/龙珠/cover.png', 'wb').write(png(600, 900, (250, 140, 0), (0, 0, 0)))
for s in range(1, 9):
    for i in range(1, 21): epub(f'{ROOT}/系列{s}/book{i}.epub', f'系列{s} 第{i}册', f'作者{s}')
os.makedirs(f'{ROOT}/空文件夹')
n = sum(f.endswith('.epub') for _, _, fs in os.walk(ROOT) for f in fs)
print('epubs:', n)


def long_text_epub(path, title='LongText 长篇测试', chapters=14):
    """多章节 + 嵌套目录的文字书，用来测分页、目录跳转、进度恢复。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sentences = ['天色渐渐暗了下来，远处的山影像一幅没有干透的水墨。', '他把书合上，又翻开，终究没有读进去一个字。',
                 '“你真的决定了吗？”她问，声音轻得像怕惊动什么。', '风从窗缝里钻进来，灯火晃了一晃，墙上的影子也跟着晃。',
                 '很多年以后他才明白，那个下午其实什么都没有发生。', 'The quick brown fox jumps over the lazy dog, again and again.']
    items, spine, nav = [], [], []
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr(zipfile.ZipInfo('mimetype'), 'application/epub+zip')
        z.writestr('META-INF/container.xml', CONTAINER, zipfile.ZIP_DEFLATED)
        for c in range(1, chapters + 1):
            body = f'<h1 id="top">第{c}章 章节标题{c}</h1>'
            for sec in range(1, 4):
                body += f'<h2 id="s{sec}">第{c}章 第{sec}节</h2>'
                for p in range(12):
                    body += '<p>' + ''.join(random.choice(sentences) for _ in range(6)) + '</p>'
            z.writestr(f'OEBPS/ch{c}.xhtml', f'<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>第{c}章</title></head><body>{body}</body></html>', zipfile.ZIP_DEFLATED)
            items.append(f'<item id="ch{c}" href="ch{c}.xhtml" media-type="application/xhtml+xml"/>')
            spine.append(f'<itemref idref="ch{c}"/>')
            subs = ''.join(f'<li><a href="ch{c}.xhtml#s{s}">第{c}章 第{s}节</a></li>' for s in range(1, 4))
            nav.append(f'<li><a href="ch{c}.xhtml">第{c}章 章节标题{c}</a><ol>{subs}</ol></li>')
        z.writestr('OEBPS/nav.xhtml', f'<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>{"".join(nav)}</ol></nav></body></html>', zipfile.ZIP_DEFLATED)
        z.writestr('OEBPS/images/cover.png', png(600, 900, (240, 240, 235), (20, 20, 20)), zipfile.ZIP_DEFLATED)
        opf = f'<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">{title}</dc:identifier><dc:title>{title}</dc:title><dc:language>zh</dc:language><dc:creator>测试作者</dc:creator><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover-img" href="images/cover.png" media-type="image/png" properties="cover-image"/>{"".join(items)}</manifest><spine>{"".join(spine)}</spine></package>'
        z.writestr('OEBPS/content.opf', opf, zipfile.ZIP_DEFLATED)


long_text_epub(f'{ROOT}/LongText.epub')
