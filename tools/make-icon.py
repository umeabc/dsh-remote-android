#!/usr/bin/env python3
"""Render launcher icons from the whale-girl emoji frame (pixel art, NEAREST).

Usage: python tools/make-icon.py [source.png]
The source is one 49x48 frame cropped from the upstream sprite sheet; pass a
different PNG to reuse the composition.
"""
import sys
from PIL import Image, ImageDraw

SOURCE = sys.argv[1] if len(sys.argv) > 1 else 'tools/icon-source.png'
RES = 'app/src/main/res'
FILL_LEGACY = 0.66
FILL_ADAPTIVE = 0.60
BG = (255, 255, 255, 26)  # 10% white: keeps some launchers from forcing an opaque tile

src = Image.open(SOURCE).convert('RGBA')
art_src = src.crop(src.getbbox())


def scaled(im, k):
    return im.resize((im.width * k, im.height * k), Image.NEAREST)


def fit(size, ratio):
    k = max(1, round(size * ratio / art_src.width))
    art = scaled(art_src, k)
    while (art.width > size or art.height > size) and k > 1:
        k -= 1
        art = scaled(art_src, k)
    return art


for density, s in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    canvas = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(canvas).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=BG)
    art = fit(s, FILL_LEGACY)
    canvas.alpha_composite(art, ((s - art.width) // 2, (s - art.height) // 2))
    canvas.save(f'{RES}/mipmap-{density}/ic_launcher.png')
    canvas.save(f'{RES}/mipmap-{density}/ic_launcher_round.png')

for density, s in {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}.items():
    fg = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    art = fit(s, FILL_ADAPTIVE)
    fg.alpha_composite(art, ((s - art.width) // 2, (s - art.height) // 2))
    fg.save(f'{RES}/mipmap-{density}/ic_launcher_foreground.png')

print('icons written')
