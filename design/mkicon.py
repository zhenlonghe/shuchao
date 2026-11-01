"""墨案图标：在书巢原稿（design/shuchao-icon-original.svg）的翻开书页上加层次。
全部平涂（墨水屏上渐变会抖成色带）；层次靠同一种墨的几个浓淡 + 纸的两个浓淡。
用法：python3 design/mkicon.py design/moan-icon.svg app/src/main/res/drawable/ic_launcher_foreground.xml
"""
import re, sys

def tokens(d):
    return re.findall(r"[MLQZ]|-?\d+(?:\.\d+)?", d)

BG     = "#628794"   # 底：中墨。比上一版 #6F919D 深一点，放在桌面饱和的系统图标旁才站得住；仍比原稿 #4E6B78 浅（Kaleido 会压暗）
NEST   = "#B3CAD2"   # 后层「巢」：淡墨
NEST_D = "#93AFB9"   # 巢贴着书页的那一圈暗面
SHEET2 = "#D9E1E2"   # 书页下垫着的第二张纸：沿 45° 往外上错开，纸角从上沿和外沿露出来，下面的尖不露（下缘正好是 45°）
SHEET3 = "#C3D2D7"   # 第三张
PAPER  = "#F4F1E8"   # 书页：纸色（原稿的颜色），不用纯白
FOLD   = "#E8E3D6"   # 书脊旁的折痕暗面
SPINE  = BG

def mirror(d):
    """把左半边的绝对坐标路径镜像到右半边（x → 1024 − x）。只认 M/L/Q/Z 和成对的数字。"""
    out, toks = [], tokens(d)
    i = 0
    while i < len(toks):
        t = toks[i]
        if t in "MLQZ":
            out.append(t); i += 1
        else:
            x, y = float(toks[i]), float(toks[i + 1])
            out.append(f"{1024 - x:g} {y:g}"); i += 2
    return " ".join(out)

NEST_L  = "M248 350 Q248 318 278 330 L470 404 Q500 416 512 448 L512 770 L292 690 Q248 674 248 628 Z"
# 巢的暗面：沿书页下缘往外一圈（书页压在巢上的影子）
NESTD_L = "M292 554 L292 600 L512 800 L512 770 L314 606 Q292 584 292 554 Z"
PAGE_L  = "M318 292 Q292 268 292 308 L292 554 Q292 584 314 606 L512 784 L512 426 Z"
FOLD_L  = "M512 426 L512 784 L490 764 L490 446 Z"

def shift(d, dx, dy):
    out, toks = [], tokens(d)
    i = 0
    while i < len(toks):
        t = toks[i]
        if t in "MLQZ":
            out.append(t); i += 1
        else:
            out.append(f"{float(toks[i]) + dx:g} {float(toks[i + 1]) + dy:g}"); i += 2
    return " ".join(out)

layers = [
    (NEST,   NEST_L),
    (NEST_D, NESTD_L),
    (SHEET3, shift(PAGE_L, -28, -28)),
    (SHEET2, shift(PAGE_L, -14, -14)),
    (PAPER,  PAGE_L),
    (FOLD,   FOLD_L),
]
paths = []
for color, d in layers:
    paths.append((color, d)); paths.append((color, mirror(d)))

svg = ['<svg width="1024" height="1024" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">',
       '  <!-- 墨案 · 图标。design/mkicon.py 生成；全部平涂 -->',
       f'  <rect x="72" y="72" width="880" height="880" rx="210" fill="{BG}"/>']
svg += [f'  <path d="{d}" fill="{c}"/>' for c, d in paths]
svg.append(f'  <path d="M512 426 L512 784" stroke="{SPINE}" stroke-width="12" stroke-linecap="round"/>')
svg.append('</svg>')
open(sys.argv[1], "w").write("\n".join(svg) + "\n")

xml = ['<?xml version="1.0" encoding="utf-8"?>',
       '<!-- 墨案图标前景，design/mkicon.py 生成（源 design/moan-icon.svg，1024 画布）。',
       '     原稿的 880 圆角方块对应自适应图标的 72dp 可见区（72/880），再放大 1.2 倍：墨案桌面的蒙版近乎直角方形，',
       '     原比例下书显得小；最远的角距中心 ≈ 31dp，仍在 33dp 的安全圆内。中心 (512,512) → (54,54) -->',
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
       '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
       '    <group android:scaleX="0.098182" android:scaleY="0.098182" android:translateX="3.731" android:translateY="3.731">']
xml += [f'        <path android:fillColor="{c}" android:pathData="{d}" />' for c, d in paths]
xml.append(f'        <path android:strokeColor="{SPINE}" android:strokeWidth="12" android:strokeLineCap="round" android:pathData="M512,426 L512,784" />')
xml += ['    </group>', '</vector>']
open(sys.argv[2], "w").write("\n".join(xml) + "\n")
print("bg", BG)
