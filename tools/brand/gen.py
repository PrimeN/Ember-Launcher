"""Ember's flame, original outlined lettering and shared vector geometry.

The same paths generate Android resources and reusable SVGs. No font installation,
network request or bitmap tracing is needed to rebuild the brand assets.
"""

BG = "#101012"
IVORY = "#F8F3ED"
FLAME_A = "M56 4 C75 17 77 36 63 51 C51 64 32 74 40 96 C20 90 12 72 22 53 C31 36 58 27 56 4 Z"
FLAME_B = "M72 40 C87 55 86 75 74 88 C68 94 59 97 48 96 C42 83 51 72 60 62 C69 51 73 46 72 40 Z"

# Original geometric capital outlines, with open counters and generous tracking.
LETTERS = {
    'E': (48, "M0 0 H48 V7 H8 V31 H43 V38 H8 V65 H48 V72 H0 Z"),
    'M': (70, "M0 72 V0 H8 L35 35 L62 0 H70 V72 H62 V13 L35 47 L8 13 V72 Z"),
    'B': (57, "M0 0 H29 C45 0 53 7 53 19 C53 27 49 32 43 35 C52 38 57 44 57 53 C57 65 48 72 32 72 H0 Z M8 7 V31 H28 C39 31 45 27 45 19 C45 11 39 7 28 7 Z M8 38 V65 H31 C43 65 49 61 49 52 C49 43 42 38 30 38 Z"),
    'R': (64, "M0 72 V0 H31 C49 0 59 8 59 22 C59 33 52 40 42 43 L64 72 H54 L33 44 H8 V72 Z M8 7 V37 H30 C44 37 51 32 51 22 C51 12 44 7 30 7 Z"),
}
TRACKING = 36
WORD_WIDTH = sum(LETTERS[ch][0] for ch in "EMBER") + TRACKING * 4
WORD_HEIGHT = 72

PALETTES = {
    'ember': {'flameA': [(0, '#FFB644'), (.45, '#FF7A14'), (1, '#DC3016')],
              'flameB': [(0, '#FF8D16'), (1, '#DE2612')], 'glow': '#FF7A14'},
    'grey': {'flameA': [(0, '#D6CBC0'), (.45, '#AA9F96'), (1, '#74665D')],
             'flameB': [(0, '#B8AAA0'), (1, '#77665C')], 'glow': '#B0A49C'},
    'blue': {'flameA': [(0, '#B8ECFF'), (.45, '#4A9BFF'), (1, '#2F5BEA')],
             'flameB': [(0, '#779AFF'), (1, '#4930B0')], 'glow': '#4F94FF'},
    'green': {'flameA': [(0, '#D4FFB0'), (.45, '#3FDF8F'), (1, '#0E9E68')],
              'flameB': [(0, '#44DCA0'), (1, '#056150')], 'glow': '#34D399'},
    'rose': {'flameA': [(0, '#FFC2D6'), (.45, '#FF4F86'), (1, '#E0245E')],
             'flameB': [(0, '#FF6C9C'), (1, '#A01950')], 'glow': '#FF5C8A'},
}
VARIANTS = [('ember', ''), ('grey', '_grey'), ('blue', '_blue'), ('green', '_green'), ('rose', '_rose')]
G = PALETTES['ember']


def use(name):
    global G
    G = PALETTES[name]


def vector(w, h, vw, vh, body):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n'
            f'    {body}\n</vector>\n')


def mark_group(tx, ty, s, inner):
    return f'<group android:translateX="{tx:.5f}" android:translateY="{ty:.5f}" android:scaleX="{s:.6f}" android:scaleY="{s:.6f}">{inner}</group>'


def centred(size, box):
    # The complete two-part flame stays inside this square, including adaptive masks.
    s = box / 100
    return size / 2 - 50 * s, size / 2 - 50 * s, s


def vd_gradient(name):
    top = 4 if name == 'flameA' else 40
    stops = ''.join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in G[name])
    return f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="50" android:startY="{top}" android:endX="50" android:endY="96">{stops}</gradient></aapt:attr>'


def vd_glow(cx=50, cy=62, radius=70, alpha='24'):
    path = f'M{cx-radius} {cy} a{radius} {radius} 0 1 0 {radius*2} 0 a{radius} {radius} 0 1 0 {-radius*2} 0 Z'
    return (f'<path android:pathData="{path}"><aapt:attr name="android:fillColor">'
            f'<gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{radius}">'
            f'<item android:offset="0" android:color="#{alpha}{G["glow"][1:]}"/>'
            f'<item android:offset="1" android:color="#00{G["glow"][1:]}"/></gradient></aapt:attr></path>')


def vd_mark(glow=False):
    return (vd_glow() if glow else '') + ''.join(
        f'<path android:pathData="{path}">{vd_gradient(name)}</path>'
        for name, path in [('flameA', FLAME_A), ('flameB', FLAME_B)])


def vd_mark_mono():
    return ''.join(f'<path android:pathData="{path}" android:fillColor="#FFFFFFFF"/>' for path in (FLAME_A, FLAME_B))


def letters():
    x = 0
    for ch in "EMBER":
        width, path = LETTERS[ch]
        yield x, path
        x += width + TRACKING


def vd_wordmark(color='#FFF8F3ED'):
    return ''.join(f'<group android:translateX="{x}"><path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{path}"/></group>' for x, path in letters())


def svg_wordmark(color=IVORY):
    return ''.join(f'<path transform="translate({x} 0)" fill="{color}" fill-rule="evenodd" d="{path}"/>' for x, path in letters())


def svg_defs():
    parts = []
    for name in ('flameA', 'flameB'):
        top = 4 if name == 'flameA' else 40
        stops = ''.join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in G[name])
        parts.append(f'<linearGradient id="{name}" gradientUnits="userSpaceOnUse" x1="50" y1="{top}" x2="50" y2="96">{stops}</linearGradient>')
    parts.append(f'<radialGradient id="glow"><stop stop-color="{G["glow"]}" stop-opacity=".16"/><stop offset="1" stop-color="{G["glow"]}" stop-opacity="0"/></radialGradient>')
    return '<defs>' + ''.join(parts) + '</defs>'


def svg_mark(glow=False):
    return (f'<circle cx="50" cy="62" r="70" fill="url(#glow)"/>' if glow else '') + ''.join(
        f'<path d="{path}" fill="url(#{name})"/>' for name, path in [('flameA', FLAME_A), ('flameB', FLAME_B)])


def svg_mark_mono(color='#fff'):
    return ''.join(f'<path d="{path}" fill="{color}"/>' for path in (FLAME_A, FLAME_B))


def background_svg(w, h):
    return f'<rect width="{w}" height="{h}" fill="{BG}"/>'


def svg_banner():
    scale = .84
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 320 180">{svg_defs()}'
            + background_svg(320, 180)
            + '<ellipse cx="160" cy="112" rx="205" ry="142" fill="url(#glow)"/>'
            + f'<path d="M224 -24 A180 180 0 0 1 344 178" fill="none" stroke="{G["glow"]}" stroke-opacity=".25" stroke-width=".7"/>'
            + f'<g transform="translate(118 36) scale({scale})">{svg_mark()}</g>'
            + f'<g transform="translate({(320-WORD_WIDTH*20/72)/2} 126) scale({20/72})">{svg_wordmark()}</g></svg>\n')


def svg_icon(mono=False):
    tx, ty, scale = centred(108, 90)
    mark = svg_mark_mono('#F8F3ED') if mono else svg_mark()
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">{svg_defs()}'
            '<clipPath id="mask"><rect width="108" height="108" rx="25"/></clipPath><g clip-path="url(#mask)">'
            + background_svg(108, 108)
            + ('<ellipse cx="54" cy="100" rx="75" ry="38" fill="url(#glow)"/>' if not mono else '')
            + f'<g transform="translate({tx} {ty}) scale({scale})">{mark}</g></g></svg>\n')
