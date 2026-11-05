"""Writes the app's icon, banner and logo drawables from gen.py, in every colourway (gen.VARIANTS)."""
import gen, os
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "res")
os.makedirs(f"{R}/mipmap-anydpi", exist_ok=True)
w = lambda p, s: open(f"{R}/{p}", "w").write(s)

tx, ty, s = gen.centred(108, 62)
icon_group = lambda body: gen.mark_group(round(tx, 3), round(ty, 3), round(s, 4), body)

# Shared by every colourway: background and the single-colour (themed icon) layer.
w("drawable/ic_launcher_monochrome.xml", gen.vector(108, 108, 108, 108, icon_group(gen.vd_mark_mono())))
w("drawable/ic_launcher_background.xml", gen.vector(108, 108, 108, 108,
  '<path android:pathData="M0,0h108v108h-108z"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108"><item android:offset="0" android:color="#FF1D1E23"/><item android:offset="1" android:color="#FF0B0C0F"/></gradient></aapt:attr></path>'))

# The wordmark (white; tinted in the app)
word, ww = gen.wordmark("ember", gen.FONT, 100)
vw = 327.0  # from the left of "e" (3.7) to the right of "r"
w("drawable/ic_ember_wordmark.xml", gen.vector(round(vw * 0.4, 1), 30, vw, 75,
  f'<group android:translateX="-3.7" android:translateY="74"><path android:fillColor="#FFFFFFFF" android:pathData="{word}" /></group>'))

b = gen.banner_parts()
for name, suf in gen.VARIANTS:
    gen.use(name)
    # Adaptive icon (108 x 108, mark inside the 66 dp safe zone)
    w(f"drawable/ic_launcher_foreground{suf}.xml", gen.vector(108, 108, 108, 108, icon_group(gen.vd_mark())))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                f'    <foreground android:drawable="@drawable/ic_launcher_foreground{suf}" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
    w(f"mipmap-anydpi/ic_launcher{suf}.xml", adaptive)
    w(f"mipmap-anydpi/ic_launcher{suf}_round.xml", adaptive)
    # The mark alone, for the app (menu, side rail)
    w(f"drawable/ic_ember_mark{suf}.xml", gen.vector(48, 48, 76, 76, gen.mark_group(-12.25, -5.75, 1, gen.vd_mark(glow=False))))
    # TV banner (320 x 180)
    body = ['<path android:pathData="M0,0h320v180h-320z"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="0" android:endX="320" android:endY="180"><item android:offset="0" android:color="#FF1B1C21"/><item android:offset="1" android:color="#FF0B0C0F"/></gradient></aapt:attr></path>',
            gen.mark_group(round(b["mark_tx"], 3), round(b["mark_ty"], 3), round(b["s"], 4), gen.vd_mark()),
            f'<group android:translateX="{b["word_x"]:.3f}" android:translateY="{b["base"]}"><path android:fillColor="#FFF4F4F6" android:pathData="{b["word"]}"/></group>',
            f'<group android:translateX="{b["sub_x"]:.3f}" android:translateY="{b["sub_base"]}"><path android:fillColor="#FF8E9098" android:pathData="{b["sub"]}"/></group>']
    w(f"drawable/banner{suf}.xml", gen.vector(320, 180, 320, 180, "\n    ".join(body)))
gen.use('ember')
print("written:", ", ".join(n for n, _ in gen.VARIANTS))
