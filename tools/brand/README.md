# Ember brand assets

Ready-made files to reuse (websites, store listings, GitHub, documents):

| Folder | What |
|---|---|
| `svg/` | Vector originals. `ember-mark` (colour, with glow, grey, white, black), `ember-wordmark` (white, dark), `ember-logo-horizontal` and `ember-logo-stacked` (on dark / on light), `ember-icon` (rounded square, and the adaptive foreground/background layers, monochrome), `ember-tv-banner`. |
| `png/` | Common sizes: app icon 512 and 1024 (store icon), mark 512, TV banner 1280×720 (Google Play TV banner), logos. |

Colourways (one per accent colour; the app icon and logo follow the accent): Ember orange `#FF7A2F`,
Ember grey `#B0A49C`, Blue ember `#4F94FF` (sulphur flame), Green flame `#34D399` (copper flame), Rose ember `#FF5C8A` (strontium flame). Files
for the other colourways end in `-grey`, `-blue`, `-green`, `-rose`; all colours are in `gen.PALETTES`.

Ember colourway: flame
`#FFC24A → #FF7A2F → #F24A2E`, inner flame `#F0383C → #A3123A`, play `#FF9A3C → #EE2E3C`, screen outline
`#55565E → #3B3C43 → #E8632E → #FF8A3A`, background `#1D1E23 → #0B0C0F`. Lettering: Poppins Medium.

## Making changes

`gen.py` describes the mark once (screen outline, play button, flame, gradients). After changing it:

- `python3 tools/brand/android.py` writes the app's drawables: the adaptive icon
  (`drawable/ic_launcher_*`, `mipmap-anydpi/ic_launcher*`), the TV banner (`drawable/banner.xml`),
  and `ic_ember_mark` / `ic_ember_wordmark` used in the app (`ui/Brand.kt`).
- `python3 tools/brand/export.py --png` rewrites `svg/` and `png/` (PNGs need Python Playwright with Chromium).

Needs fontTools and the Poppins font (path in `gen.FONT`). Based on the icon drawn by Arash N, redrawn as vectors.
`preview.png` shows the set.
