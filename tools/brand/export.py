"""Writes reusable SVG and PNG files of the Ember brand to tools/brand/svg and tools/brand/png.

    python3 tools/brand/export.py          # SVGs only (needs fontTools)
    python3 tools/brand/export.py --png    # also PNGs (needs Python Playwright with Chromium)
"""
import os, sys, subprocess, colorsys
import gen

HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "svg"); PNG = os.path.join(HERE, "png")
os.makedirs(SVG, exist_ok=True)

WHITE, DARK, SUB = "#F4F4F6", "#202124", "#8E9098"
MARK_BOX = "10 3.5 80 80"  # the mark (x 14.5..86, y 6..81.5) with a little room

def doc(w, h, vb, body, defs=True):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="{vb}">{gen.svg_defs() if defs else ""}{body}</svg>\n'

def grey_defs():
    """The same gradients with the colour taken out (Ember grey)."""
    out = gen.svg_defs()
    import re
    def g(m):
        c = m.group(1); r, gg, b = (int(c[i:i + 2], 16) / 255 for i in (0, 2, 4))
        h, l, s = colorsys.rgb_to_hls(r, gg, b)
        r, gg, b = colorsys.hls_to_rgb(h, l, s * 0.12)
        return 'stop-color="#%02X%02X%02X"' % (int(r * 255), int(gg * 255), int(b * 255))
    return re.sub(r'stop-color="#([0-9A-Fa-f]{6})"', g, out)

def write(name, text):
    open(os.path.join(SVG, name), "w").write(text)

word, ww = gen.wordmark("ember", gen.FONT, 100)
WORD_VB = "3.7 -74 327 75"

# Mark
write("ember-mark.svg", doc(512, 512, MARK_BOX, gen.svg_mark(glow=False)))
write("ember-mark-glow.svg", doc(512, 512, "4 -4 96 96", gen.svg_mark(glow=True)))
write("ember-mark-mono-white.svg", doc(512, 512, MARK_BOX, gen.svg_mark_mono("#FFFFFF"), defs=False))
write("ember-mark-mono-black.svg", doc(512, 512, MARK_BOX, gen.svg_mark_mono("#000000"), defs=False))
# Wordmark
write("ember-wordmark-white.svg", doc(654, 150, WORD_VB, f'<path d="{word}" fill="{WHITE}"/>', defs=False))
write("ember-wordmark-dark.svg", doc(654, 150, WORD_VB, f'<path d="{word}" fill="{DARK}"/>', defs=False))

# Horizontal logo: mark (height 100) + wordmark (x-height to match), for dark or light backgrounds
def horizontal(fill, bg=None):
    s = 100 / 76
    wx = 76 * s + 26
    body = ""
    body += f'<g transform="scale({s}) translate(-12.25 -5.75)">{gen.svg_mark(glow=False)}</g>'
    body += f'<g transform="translate({wx} 86) scale(0.8) translate(-3.7 0)"><path d="{word}" fill="{fill}"/></g>'
    w = wx + 327 * 0.8
    w += 4
    return doc(round((w + 8) * 3), 324, f"-4 -4 {w + 8:.1f} 108", body)
write("ember-logo-horizontal-on-dark.svg", horizontal(WHITE))
write("ember-logo-horizontal-on-light.svg", horizontal(DARK))

# Stacked logo, as in the original drawing: mark, "ember", "TV LAUNCHER"
def stacked(fill, sub):
    sp, sw = gen.spaced("TV LAUNCHER", gen.FONT, 26, 9)
    body = f'<g transform="translate(112 20) scale(2.4) translate(-12.25 -5.75)">{gen.svg_mark(glow=True)}</g>'
    body += f'<g transform="translate({200 - 327 * 0.55 / 2:.1f} 300) scale(0.55) translate(-3.7 0)"><path d="{word}" fill="{fill}"/></g>'
    body += f'<g transform="translate({200 - sw / 2:.1f} 352)"><path d="{sp}" fill="{sub}"/></g>'
    return doc(800, 760, "0 0 400 380", body)
write("ember-logo-stacked-on-dark.svg", stacked(WHITE, SUB))
write("ember-logo-stacked-on-light.svg", stacked(DARK, "#5F6368"))

# App icon (rounded square, as most launchers show it) and its adaptive layers
write("ember-icon.svg", gen.svg_icon().replace('width="432" height="432"', 'width="512" height="512"'))
write("ember-icon-mono.svg", gen.svg_icon(True).replace('width="432" height="432"', 'width="512" height="512"'))
tx, ty, s = gen.centred(108, 62)
write("ember-icon-foreground.svg", doc(432, 432, "0 0 108 108", f'<g transform="translate({tx} {ty}) scale({s})">{gen.svg_mark()}</g>'))
write("ember-icon-background.svg", doc(432, 432, "0 0 108 108",
      '<linearGradient id="ibg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1D1E23"/><stop offset="1" stop-color="#0B0C0F"/></linearGradient><rect width="108" height="108" fill="url(#ibg)"/>', defs=False))
# TV banner
write("ember-tv-banner.svg", gen.svg_banner())

# Colourways (accent colours Ember grey, Blue ember, Green flame): mark, icon, foreground, banner
for name, suf in gen.VARIANTS[1:]:
    gen.use(name)
    tag = suf.replace("_", "-")
    write(f"ember-mark{tag}.svg", doc(512, 512, MARK_BOX, gen.svg_mark(glow=False)))
    write(f"ember-icon{tag}.svg", gen.svg_icon().replace('width="432" height="432"', 'width="512" height="512"'))
    write(f"ember-icon-foreground{tag}.svg", doc(432, 432, "0 0 108 108", f'<g transform="translate({tx} {ty}) scale({s})">{gen.svg_mark()}</g>'))
    write(f"ember-tv-banner{tag}.svg", gen.svg_banner())
gen.use("ember")

print("SVGs in", SVG)

if "--png" in sys.argv:
    os.makedirs(PNG, exist_ok=True)
    jobs = [
        ("ember-icon.svg", "ember-icon-512.png", 512, 512),
        ("ember-icon.svg", "ember-icon-1024.png", 1024, 1024),
        ("ember-mark.svg", "ember-mark-512.png", 512, 512),
        ("ember-mark-glow.svg", "ember-mark-glow-512.png", 512, 512),
        ("ember-mark-grey.svg", "ember-mark-grey-512.png", 512, 512),
        ("ember-mark-blue.svg", "ember-mark-blue-512.png", 512, 512),
        ("ember-mark-green.svg", "ember-mark-green-512.png", 512, 512),
        ("ember-icon-grey.svg", "ember-icon-grey-512.png", 512, 512),
        ("ember-icon-blue.svg", "ember-icon-blue-512.png", 512, 512),
        ("ember-icon-green.svg", "ember-icon-green-512.png", 512, 512),
        ("ember-tv-banner-grey.svg", "ember-tv-banner-grey-1280x720.png", 1280, 720),
        ("ember-tv-banner-blue.svg", "ember-tv-banner-blue-1280x720.png", 1280, 720),
        ("ember-tv-banner-green.svg", "ember-tv-banner-green-1280x720.png", 1280, 720),
        ("ember-mark-rose.svg", "ember-mark-rose-512.png", 512, 512),
        ("ember-icon-rose.svg", "ember-icon-rose-512.png", 512, 512),
        ("ember-tv-banner-rose.svg", "ember-tv-banner-rose-1280x720.png", 1280, 720),
        ("ember-mark-mono-white.svg", "ember-mark-mono-white-512.png", 512, 512),
        ("ember-tv-banner.svg", "ember-tv-banner-1280x720.png", 1280, 720),
        ("ember-logo-horizontal-on-dark.svg", "ember-logo-horizontal-on-dark.png", 0, 324),
        ("ember-logo-horizontal-on-light.svg", "ember-logo-horizontal-on-light.png", 0, 324),
        ("ember-logo-stacked-on-dark.svg", "ember-logo-stacked-on-dark.png", 800, 760),
    ]
    for src, out, w, h in jobs:
        text = open(os.path.join(SVG, src)).read()
        import re
        if w == 0:
            w = int(float(re.search(r'width="([\d.]+)"', text).group(1)))
        # Render at the target size: set the outer width and height.
        text = re.sub(r'width="[\d.]+" height="[\d.]+"', f'width="{w}" height="{h}"', text, count=1)
        tmp = os.path.join(PNG, "_tmp.svg"); open(tmp, "w").write(text)
        subprocess.run([sys.executable, os.path.join(HERE, "render.py"), tmp, os.path.join(PNG, out), str(w), str(h)], check=True)
        os.remove(tmp)
    print("PNGs in", PNG)
