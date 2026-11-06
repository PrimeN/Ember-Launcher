"""Generate the app's vector branding from the shared flame and lettering paths."""
from pathlib import Path
import gen

RES = Path(__file__).resolve().parents[2] / 'app/src/main/res'

def write(path, text):
    (RES / path).write_text(text)


def background(w, h):
    return f'<path android:pathData="M0 0 H{w} V{h} H0 Z" android:fillColor="{gen.BG}"/>'


# All themed launcher masks use the same clean, transparent, two-part silhouette.
tx, ty, scale = gen.centred(108, 66)
write('drawable/ic_launcher_monochrome.xml', gen.vector(108, 108, 108, 108, gen.mark_group(tx, ty, scale, gen.vd_mark_mono())))
write('drawable/ic_launcher_background.xml', gen.vector(108, 108, 108, 108, background(108, 108)))
write('drawable/ic_ember_wordmark.xml', gen.vector(120, 20, gen.WORD_WIDTH, gen.WORD_HEIGHT, gen.vd_wordmark()))

for name, suffix in gen.VARIANTS:
    gen.use(name)
    # 66 dp square safe zone in a 108 dp adaptive-icon viewport.
    foreground = gen.vd_glow(54, 104, 62, '30') + gen.mark_group(tx, ty, scale, gen.vd_mark())
    write(f'drawable/ic_launcher_foreground{suffix}.xml', gen.vector(108, 108, 108, 108, foreground))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
                f'    <foreground android:drawable="@drawable/ic_launcher_foreground{suffix}"/>\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
    write(f'mipmap-anydpi/ic_launcher{suffix}.xml', adaptive)
    write(f'mipmap-anydpi/ic_launcher{suffix}_round.xml', adaptive)
    write(f'drawable/ic_ember_mark{suffix}.xml', gen.vector(48, 48, 100, 100, gen.vd_mark()))
    banner = (background(320, 180) + gen.vd_glow(160, 112, 205, '28')
              + f'<path android:pathData="M224 -24 A180 180 0 0 1 344 178" android:fillColor="#00000000" android:strokeColor="#40{gen.G["glow"][1:]}" android:strokeWidth=".7"/>'
              + gen.mark_group(118, 36, .84, gen.vd_mark())
              + gen.mark_group((320-gen.WORD_WIDTH*20/72)/2, 126, 20/72, gen.vd_wordmark()))
    write(f'drawable/banner{suffix}.xml', gen.vector(320, 180, 320, 180, banner))
gen.use('ember')
print('Generated Android icons, wordmark and TV banners in all five accent variants.')
