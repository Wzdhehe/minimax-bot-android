#!/usr/bin/env python3
"""Generate original geometric M placeholders. Requires Pillow; run from any cwd.

No source artwork or font is used. Keep resource names, gradients, and adaptive
XML layers so existing launcher aliases and user preferences continue to resolve.
"""
from pathlib import Path
from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parents[1] / 'android/app/src/main/res'
POINTS = [(34,72),(34,36),(42,36),(54,52),(66,36),(74,36),(74,72),
          (66,72),(66,50),(54,66),(42,50),(42,72)]
COLORS = {
    '': ('9AB0E8', '405AAD', 'FFFFFF'),
    '_night': ('2C3D6C', '0C122D', 'FFFFFF'),
    '_frost': ('FCFDFF', 'DDE5F8', '405AAD'),
    '_ink': ('292B30', '0D0E11', 'F5F7FB'),
}

def foreground(side, color):
    image = Image.new('RGBA', (side, side))
    ImageDraw.Draw(image).polygon([(round(x*side/108), round(y*side/108)) for x,y in POINTS], fill='#'+color)
    return image

def tile(side, start, end, ink):
    # Render Android's background gradient on the same 108-unit grid, then crop
    # the central 72 units, just as the adaptive launcher and Flutter preview do.
    full = side*3//2
    image = Image.new('RGBA', (full, full))
    a, b = tuple(bytes.fromhex(start)), tuple(bytes.fromhex(end))
    dx, dy = 100.43-7.57, 113.87+5.87
    px = image.load()
    for y in range(full):
        for x in range(full):
            t = max(0, min(1, ((x*108/full-7.57)*dx + (y*108/full+5.87)*dy)/(dx*dx+dy*dy)))
            px[x,y] = tuple(round(c+(d-c)*t) for c,d in zip(a,b))+(255,)
    image.alpha_composite(foreground(full, ink))
    inset=(full-side)//2
    return image.crop((inset,inset,inset+side,inset+side))

def main():
    for density, factor in [('mdpi',1),('hdpi',1.5),('xhdpi',2),('xxhdpi',3),('xxxhdpi',4)]:
        folder = RES / ('mipmap-'+density)
        for suffix,(start,end,ink) in COLORS.items():
            stem = 'ic_launcher'+suffix
            foreground(round(108*factor), ink).save(folder/(stem+'_foreground.png'))
            side=round(48*factor)
            image=tile(side,start,end,ink)
            image.save(folder/(stem+'.png'))
            mask=Image.new('L',(side,side)); ImageDraw.Draw(mask).ellipse((0,0,side-1,side-1),fill=255)
            image.putalpha(mask); image.save(folder/(stem+'_round.png'))
    path='M34,72 L34,36 L42,36 L54,52 L66,36 L74,36 L74,72 L66,72 L66,50 L54,66 L42,50 L42,72 Z'
    (RES/'drawable/ic_launcher_monochrome.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
        f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path}"/>\n</vector>\n')

if __name__ == '__main__':
    main()
