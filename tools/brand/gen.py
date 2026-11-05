import re
"""Ember icon set from one description: SVG previews and Android VectorDrawables."""
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
import sys

BG = "#121317"
# Shapes in a 100x100 design space (the mark only).
FRAME = dict(x=18, y=38, w=54, h=40, rx=12, sw=7)
PLAY = "M36.5 48.5 L36.5 67.5 L52.5 58 Z"
FLAME_A = "M59 50.5 C56.5 42.5 61.5 36 68 31 C75 25.5 78.5 17 73.5 3.5 C84.5 9 90.5 20.5 86.5 32.5 C83.5 41.5 74.5 45.5 67 49 C64 50.4 61.2 51.6 59 50.5 Z"
FLAME_B = "M59.5 50 C59 42 64 36.5 70.5 32 C77.5 27 81.5 20.5 80.5 12.5 C84.5 21 83.5 31 78 37.5 C73 43.5 66 46.5 59.5 50 Z"

def rrect_path(x, y, w, h, r):
    return (f"M{x+r},{y} H{x+w-r} A{r},{r} 0 0 1 {x+w},{y+r} V{y+h-r} A{r},{r} 0 0 1 {x+w-r},{y+h} "
            f"H{x+r} A{r},{r} 0 0 1 {x},{y+h-r} V{y+r} A{r},{r} 0 0 1 {x+r},{y} Z")

FRAME_PATH = rrect_path(FRAME['x'], FRAME['y'], FRAME['w'], FRAME['h'], FRAME['rx'])

# gradients: (kind, coords, stops)
import colorsys

def _grey(c, keep=0.12):
    a = c[1:3] if len(c) == 9 else ""
    h6 = c[-6:]
    r, g, b = (int(h6[k:k + 2], 16) / 255 for k in (0, 2, 4))
    hh, l, ss = colorsys.rgb_to_hls(r, g, b)
    r, g, b = colorsys.hls_to_rgb(hh, l, ss * keep)
    return "#" + a + "%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))

_COORDS = {'frame': ('linear', (18, 40, 74, 76)), 'play': ('linear', (36.5, 48.5, 52.5, 67.5)),
           'flameA': ('linear', (73.5, 3.5, 62, 52)), 'flameB': ('linear', (80, 14, 62, 50)), 'glow': ('radial', (72, 34, 30))}

# Colour of each part, per colourway (accent colours "Ember", "Ember grey", "Blue ember", "Green flame", "Rose ember").
PALETTES = {
  'ember': {
    'frame': [(0, '#55565E'), (0.42, '#3B3C43'), (0.72, '#E8632E'), (1, '#FF8A3A')],
    'play': [(0, '#FF9A3C'), (1, '#EE2E3C')],
    'flameA': [(0, '#FFC24A'), (0.6, '#FF7A2F'), (1, '#F24A2E')],
    'flameB': [(0, '#F0383C'), (1, '#A3123A')],
    'glow': [(0, '#40FF6A2B'), (1, '#00FF6A2B')],
  },
  # Sulphur burns blue: pale cyan tip, azure body, a violet fold.
  'blue': {
    'frame': [(0, '#55565E'), (0.42, '#3B3C43'), (0.72, '#2F6BE8'), (1, '#5AA2FF')],
    'play': [(0, '#7CC4FF'), (1, '#3550F0')],
    'flameA': [(0, '#B8ECFF'), (0.55, '#4A9BFF'), (1, '#2F5BEA')],
    'flameB': [(0, '#6A5CF5'), (1, '#2A1E9C')],
    'glow': [(0, '#403B7BFF'), (1, '#003B7BFF')],
  },
  # Copper burns green: a pale lime tip, emerald body, a deep teal fold.
  'green': {
    'frame': [(0, '#55565E'), (0.42, '#3B3C43'), (0.72, '#14A86B'), (1, '#3FE09A')],
    'play': [(0, '#8CF5B8'), (1, '#0FA36A')],
    'flameA': [(0, '#D4FFB0'), (0.55, '#3FDF8F'), (1, '#0E9E68')],
    'flameB': [(0, '#14B88A'), (1, '#056150')],
    'glow': [(0, '#402FE08F'), (1, '#002FE08F')],
  },
}
PALETTES['rose'] = {
  # Strontium burns crimson: a pale pink tip, rose body, a deep wine fold.
    'frame': [(0, '#55565E'), (0.42, '#3B3C43'), (0.72, '#E0306A'), (1, '#FF6E9A')],
    'play': [(0, '#FF8FB1'), (1, '#E0245E')],
    'flameA': [(0, '#FFC2D6'), (0.55, '#FF4F86'), (1, '#E0245E')],
    'flameB': [(0, '#C2185B'), (1, '#7A0E3A')],
    'glow': [(0, '#40FF4F86'), (1, '#00FF4F86')],
}
PALETTES['grey'] = {k: [(o, _grey(c)) for o, c in v] for k, v in PALETTES['ember'].items()}
PALETTES['grey']['glow'] = [(0, '#26B0A49C'), (1, '#00B0A49C')]

# Suffix of the Android resources for each colourway, in the order of the app's accent list.
VARIANTS = [('ember', ''), ('grey', '_grey'), ('blue', '_blue'), ('green', '_green'), ('rose', '_rose')]

G = {}
def use(name):
    """Switches every drawing function below to a colourway."""
    G.clear()
    for k, (kind, coords) in _COORDS.items():
        G[k] = (kind, coords, PALETTES[name][k])

use('ember')
FLAME_T = (61, 50, 0.9)  # flame scaled about its base

# ---------- VectorDrawable ----------
def vd_grad(name, attr):
    kind, c, stops = G[name]
    if kind == 'linear':
        head = f'<gradient android:type="linear" android:startX="{c[0]}" android:startY="{c[1]}" android:endX="{c[2]}" android:endY="{c[3]}">'
    else:
        head = f'<gradient android:type="radial" android:centerX="{c[0]}" android:centerY="{c[1]}" android:gradientRadius="{c[2]}">'
    items = "".join(f'<item android:offset="{o}" android:color="{col}"/>' for o, col in stops)
    return f'<aapt:attr name="{attr}">{head}{items}</gradient></aapt:attr>'

def vd_mark(mono=False, glow=True):
    out = []
    if glow and not mono:
        out.append(f'<path android:pathData="M42,34 a30,30 0 1,0 60,0 a30,30 0 1,0 -60,0 Z">{vd_grad("glow", "android:fillColor")}</path>')
    if mono:
        out.append(f'<path android:pathData="{FRAME_PATH}" android:strokeWidth="{FRAME["sw"]}" android:strokeColor="#FFFFFFFF" android:fillColor="#00000000"/>')
        out.append(f'<path android:pathData="{PLAY}" android:fillColor="#FFFFFFFF" android:strokeColor="#FFFFFFFF" android:strokeWidth="3.5" android:strokeLineJoin="round"/>')
    else:
        out.append(f'<path android:pathData="{FRAME_OPEN}" android:strokeWidth="{FRAME["sw"]}" android:strokeLineCap="round" android:fillColor="#00000000">{vd_grad("frame", "android:strokeColor")}</path>')
        out.append(f'<path android:pathData="{PLAY}" android:strokeWidth="3.5" android:strokeLineJoin="round">{vd_grad("play", "android:fillColor")}{vd_grad("play", "android:strokeColor")}</path>')
    cx, cy, s = FLAME_T
    out.append(f'<group android:pivotX="{cx}" android:pivotY="{cy}" android:scaleX="{s}" android:scaleY="{s}">')
    # A cut in the background colour separates the flame from the frame (transparent gap in mono).
    if mono:
        out.append(f'<path android:pathData="{FLAME_A}" android:strokeColor="#FF000000" android:strokeWidth="2.6" android:fillColor="#FF000000"/>')
        out.append(f'<path android:pathData="{FLAME_A}" android:fillColor="#FFFFFFFF"/>')
    else:
        out.append(f'<path android:pathData="{FLAME_A}">{vd_grad("flameA", "android:fillColor")}</path>')
        out.append(f'<path android:pathData="{FLAME_B}">{vd_grad("flameB", "android:fillColor")}</path>')
    out.append('</group>')
    return "\n        ".join(out)

def vector(w, h, vw, vh, body):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n'
            f'    {body}\n</vector>\n')

def mark_group(tx, ty, s, inner):
    # design point (x,y) -> (tx + s*x, ty + s*y)
    return f'<group android:translateX="{tx}" android:translateY="{ty}" android:scaleX="{s}" android:scaleY="{s}">\n        {inner}\n    </group>'

# Mark bounds in design space (frame with stroke, flame tip): x 14.5..86, y 6..81.5 -> centre (50.25, 43.75)
def centred(size, box):
    s = box / 76.0
    cx, cy = 50.25, 43.75
    return size / 2 - s * cx, size / 2 - s * cy, s

# ---------- wordmark ----------
def _r(p):
    return re.sub(r"-?\d+\.\d+", lambda m: f"{float(m.group()):.2f}".rstrip("0").rstrip("."), p)

def wordmark(text, font, height_units):
    f = TTFont(font)
    gs = f.getGlyphSet(); cmap = f.getBestCmap(); upm = f['head'].unitsPerEm
    x = 0; paths = []
    scale = height_units / upm
    for ch in text:
        g = cmap[ord(ch)]
        pen = SVGPathPen(gs)
        tp = TransformPen(pen, (scale, 0, 0, -scale, x * scale, 0))
        gs[g].draw(tp)
        paths.append(pen.getCommands())
        x += gs[g].width
    return _r(" ".join(paths)), x * scale

if __name__ == "__main__":
    pass

FRAME_OPEN = "M58,38 H30 A12,12 0 0 0 18,50 V66 A12,12 0 0 0 30,78 H60 A12,12 0 0 0 72,66 V55"

def vd_mark_mono():
    cx, cy, s = FLAME_T
    return "\n        ".join([
        f'<path android:pathData="{FRAME_OPEN}" android:strokeWidth="{FRAME["sw"]}" android:strokeColor="#FFFFFFFF" android:strokeLineCap="round" android:fillColor="#00000000"/>',
        f'<path android:pathData="{PLAY}" android:fillColor="#FFFFFFFF" android:strokeColor="#FFFFFFFF" android:strokeWidth="3.5" android:strokeLineJoin="round"/>',
        f'<group android:pivotX="{cx}" android:pivotY="{cy}" android:scaleX="{s}" android:scaleY="{s}">',
        f'<path android:pathData="{FLAME_A}" android:fillColor="#FFFFFFFF"/>',
        '</group>',
    ])

# ---------- SVG preview (same shapes) ----------
def svg_defs():
    out = []
    for n, (kind, c, stops) in G.items():
        st = ""
        for o, col in stops:
            if len(col) == 9:
                a = int(col[1:3], 16) / 255; col = '#' + col[3:]
                st += f'<stop offset="{o}" stop-color="{col}" stop-opacity="{a:.2f}"/>'
            else:
                st += f'<stop offset="{o}" stop-color="{col}"/>'
        if kind == 'linear':
            out.append(f'<linearGradient id="{n}" gradientUnits="userSpaceOnUse" x1="{c[0]}" y1="{c[1]}" x2="{c[2]}" y2="{c[3]}">{st}</linearGradient>')
        else:
            out.append(f'<radialGradient id="{n}" gradientUnits="userSpaceOnUse" cx="{c[0]}" cy="{c[1]}" r="{c[2]}">{st}</radialGradient>')
    return "<defs>" + "".join(out) + "</defs>"

def svg_mark(glow=True):
    cx, cy, s = FLAME_T
    g = f'<circle cx="72" cy="34" r="30" fill="url(#glow)"/>' if glow else ""
    return (g + f'<path d="{FRAME_OPEN}" fill="none" stroke="url(#frame)" stroke-width="{FRAME["sw"]}" stroke-linecap="round"/>'
            f'<path d="{PLAY}" fill="url(#play)" stroke="url(#play)" stroke-width="3.5" stroke-linejoin="round"/>'
            f'<g transform="translate({cx} {cy}) scale({s}) translate({-cx} {-cy})">'
            f'<path d="{FLAME_A}" fill="url(#flameA)"/><path d="{FLAME_B}" fill="url(#flameB)"/></g>')

def svg_mark_mono(color="#fff"):
    cx, cy, s = FLAME_T
    return (f'<path d="{FRAME_OPEN}" fill="none" stroke="{color}" stroke-width="{FRAME["sw"]}" stroke-linecap="round"/>'
            f'<path d="{PLAY}" fill="{color}" stroke="{color}" stroke-width="3.5" stroke-linejoin="round"/>'
            f'<g transform="translate({cx} {cy}) scale({s}) translate({-cx} {-cy})"><path d="{FLAME_A}" fill="{color}"/></g>')

FONT = "/usr/share/fonts/truetype/google-fonts/Poppins-Medium.ttf"
FONT_SMALL = "/usr/share/fonts/truetype/google-fonts/Poppins-Medium.ttf"

def spaced(text, font, size, spacing):
    """Each letter separately with extra spacing (for TV LAUNCHER)."""
    f = TTFont(font); gs = f.getGlyphSet(); cmap = f.getBestCmap(); upm = f['head'].unitsPerEm
    sc = size / upm; x = 0; paths = []
    for ch in text:
        g = cmap[ord(ch)]
        pen = SVGPathPen(gs); gs[g].draw(TransformPen(pen, (sc, 0, 0, -sc, x, 0)))
        paths.append(pen.getCommands()); x += gs[g].width * sc + spacing
    return _r(" ".join(p for p in paths if p)), x - spacing

# Banner layout (320 x 180)
def banner_parts():
    mark_box = 84
    tx, ty, s = centred(0, mark_box)  # centred on (0,0); moved below
    word, ww = wordmark("ember", FONT, 54)
    sub, sw = spaced("TV LAUNCHER", FONT_SMALL, 11, 2.8)
    mark_w = 71.5 * s
    gap = 12
    total = mark_w + gap + ww
    left = (320 - total) / 2
    mark_tx = left - 14.5 * s
    mark_ty = 90 - s * 43.75
    word_x = left + mark_w + gap
    base = 98
    sub_x = word_x + (ww - sw) / 2
    return dict(s=s, mark_tx=mark_tx, mark_ty=mark_ty, word=word, word_x=word_x, base=base, sub=sub, sub_x=sub_x, sub_base=base + 22)

def svg_banner():
    b = banner_parts()
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540" viewBox="0 0 320 180">{svg_defs()}'
            f'<linearGradient id="bbg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1B1C21"/><stop offset="1" stop-color="#0B0C0F"/></linearGradient>'
            f'<rect width="320" height="180" fill="url(#bbg)"/>'
            f'<g transform="translate({b["mark_tx"]} {b["mark_ty"]}) scale({b["s"]})">{svg_mark()}</g>'
            f'<path transform="translate({b["word_x"]} {b["base"]})" d="{b["word"]}" fill="#F4F4F6"/>'
            f'<path transform="translate({b["sub_x"]} {b["sub_base"]})" d="{b["sub"]}" fill="#8E9098"/>'
            '</svg>')

def svg_icon(mono=False):
    tx, ty, s = centred(108, 62)
    bg = '<linearGradient id="ibg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1D1E23"/><stop offset="1" stop-color="#0B0C0F"/></linearGradient>'
    inner = svg_mark_mono("#E8E8EA") if mono else svg_mark()
    back = '#2A2B30' if mono else 'url(#ibg)'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">{svg_defs()}{bg}'
            f'<clipPath id="m"><rect width="108" height="108" rx="26"/></clipPath><g clip-path="url(#m)"><rect width="108" height="108" fill="{back}"/>'
            f'<g transform="translate({tx} {ty}) scale({s})">{inner}</g></g>'
            # safe zone guide
            f'</svg>')
