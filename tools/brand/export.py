"""Export reusable flame-brand SVGs and optional PNGs from the shared paths.

    python3 tools/brand/export.py
    python3 tools/brand/export.py --png

Only PNG rendering requires Playwright and Chromium. Vector generation uses
Python's standard library and never needs an installed typeface or network.
"""
from pathlib import Path
import subprocess
import sys
import re
import gen

HERE = Path(__file__).resolve().parent
SVG = HERE / 'svg'
PNG = HERE / 'png'
SVG.mkdir(exist_ok=True)
WHITE, DARK = gen.IVORY, '#211D1A'


def doc(w, h, vb, body, defs=True):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="{vb}">{gen.svg_defs() if defs else ""}{body}</svg>\n'


def write(name, content):
    (SVG / name).write_text(content)


def horizontal(color):
    word_scale = 40 / gen.WORD_HEIGHT
    width = 94 + gen.WORD_WIDTH * word_scale
    body = f'<g transform="translate(0 0) scale(.8)">{gen.svg_mark()}</g>'
    body += f'<g transform="translate(94 20) scale({word_scale})">{gen.svg_wordmark(color)}</g>'
    return doc(round(width * 3), 240, f'0 0 {width} 80', body)


def stacked(color):
    word_scale = 30 / gen.WORD_HEIGHT
    body = f'<g transform="translate(110 0) scale(1.8)">{gen.svg_mark()}</g>'
    body += f'<g transform="translate({200-gen.WORD_WIDTH*word_scale/2} 194) scale({word_scale})">{gen.svg_wordmark(color)}</g>'
    return doc(800, 500, '0 0 400 250', body)


write('ember-mark.svg', doc(512, 512, '0 0 100 100', gen.svg_mark()))
write('ember-mark-glow.svg', doc(512, 512, '-20 -20 140 140', gen.svg_mark(True)))
for suffix, color in [('white', '#FFFFFF'), ('black', '#000000')]:
    write(f'ember-mark-mono-{suffix}.svg', doc(512, 512, '0 0 100 100', gen.svg_mark_mono(color), False))
for suffix, color in [('white', WHITE), ('dark', DARK)]:
    write(f'ember-wordmark-{suffix}.svg', doc(gen.WORD_WIDTH*2, gen.WORD_HEIGHT*2, f'0 0 {gen.WORD_WIDTH} {gen.WORD_HEIGHT}', gen.svg_wordmark(color), False))
for suffix, color in [('dark', WHITE), ('light', DARK)]:
    write(f'ember-logo-horizontal-on-{suffix}.svg', horizontal(color))
    write(f'ember-logo-stacked-on-{suffix}.svg', stacked(color))
write('ember-icon-mono.svg', gen.svg_icon(True))
write('ember-icon-background.svg', doc(432, 432, '0 0 108 108', gen.background_svg(108, 108), False))

for name, suffix in gen.VARIANTS:
    gen.use(name)
    tag = suffix.replace('_', '-')
    write(f'ember-mark{tag}.svg', doc(512, 512, '0 0 100 100', gen.svg_mark()))
    write(f'ember-icon{tag}.svg', gen.svg_icon())
    tx, ty, scale = gen.centred(108, 66)
    write(f'ember-icon-foreground{tag}.svg', doc(432, 432, '0 0 108 108', f'<g transform="translate({tx} {ty}) scale({scale})">{gen.svg_mark()}</g>'))
    write(f'ember-tv-banner{tag}.svg', gen.svg_banner())
gen.use('ember')
print('Generated reusable SVG branding.')

if '--png' in sys.argv:
    PNG.mkdir(exist_ok=True)
    jobs = [('ember-icon.svg', 'ember-icon-1024.png', 1024, 1024),
            ('ember-mark-glow.svg', 'ember-mark-glow-512.png', 512, 512),
            ('ember-mark-mono-white.svg', 'ember-mark-mono-white-512.png', 512, 512)]
    for _, suffix in gen.VARIANTS:
        tag = suffix.replace('_', '-')
        jobs += [(f'ember-icon{tag}.svg', f'ember-icon{tag}-512.png', 512, 512),
                 (f'ember-mark{tag}.svg', f'ember-mark{tag}-512.png', 512, 512),
                 (f'ember-tv-banner{tag}.svg', f'ember-tv-banner{tag}-1280x720.png', 1280, 720)]
    for suffix in ('dark', 'light'):
        jobs.append((f'ember-logo-horizontal-on-{suffix}.svg', f'ember-logo-horizontal-on-{suffix}.png', 0, 240))
    jobs.append(('ember-logo-stacked-on-dark.svg', 'ember-logo-stacked-on-dark.png', 800, 500))
    for source, output, width, height in jobs:
        text = (SVG / source).read_text()
        if not width:
            width = int(float(re.search(r'width="([\d.]+)"', text).group(1)))
        with __import__('tempfile').TemporaryDirectory() as temporary:
            svg = Path(temporary) / 'asset.svg'
            svg.write_text(re.sub(r'width="[\d.]+" height="[\d.]+"', f'width="{width}" height="{height}"', text, count=1))
            subprocess.run([sys.executable, str(HERE / 'render.py'), str(svg), str(PNG / output), str(width), str(height)], check=True)
    print('Rendered PNG branding.')

    # Reusable asset sheets, generated from the same SVGs rather than old screenshots.
    import base64
    def embedded(name, x, y, width, height):
        data = base64.b64encode((SVG / name).read_bytes()).decode()
        return f'<image x="{x}" y="{y}" width="{width}" height="{height}" href="data:image/svg+xml;base64,{data}"/>'
    def label(text, x, y):
        return f'<text x="{x}" y="{y}" fill="{WHITE}" font-size="22" font-family="sans-serif" letter-spacing="3">{text}</text>'
    sheets = {
        'preview.png': (1400, 900,
            label('EMBER BRAND ASSETS', 50, 55)
            + embedded('ember-icon.svg', 70, 95, 310, 310)
            + embedded('ember-logo-horizontal-on-dark.svg', 460, 170, 850, 180)
            + embedded('ember-tv-banner.svg', 50, 465, 780, 439)
            + embedded('ember-logo-stacked-on-dark.svg', 850, 465, 500, 340)),
        'preview-colourways.png': (1600, 500,
            label('EMBER ACCENT VARIANTS', 50, 55)
            + ''.join(embedded(f'ember-icon{suffix.replace("_", "-")}.svg', 30+i*316, 110, 275, 275)
                      + label(name, 50+i*316, 440)
                      for i, ((_, suffix), name) in enumerate(zip(gen.VARIANTS, ('EMBER', 'DARK GREY', 'BLUE', 'GREEN', 'ROSE'))))),
    }
    for name, (width, height, body) in sheets.items():
        with __import__('tempfile').TemporaryDirectory() as temporary:
            svg = Path(temporary) / 'sheet.svg'
            svg.write_text(doc(width, height, f'0 0 {width} {height}', gen.background_svg(width, height)+body, False))
            subprocess.run([sys.executable, str(HERE / 'render.py'), str(svg), str(HERE / name), str(width), str(height)], check=True)
