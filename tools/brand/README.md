# Ember brand assets

Ember uses a standalone two-part flame, a widely spaced uppercase **EMBER** wordmark,
and a charcoal, amber and orange palette. The wordmark consists of original vector
outlines; generating it requires no external typeface. The progress ring belongs to
the startup screen, while the TV banner uses a restrained partial arc as background artwork.

| Folder | Assets |
|---|---|
| `svg/` | Flame marks, light/dark wordmarks, horizontal and stacked logos, adaptive-icon layers, app icons and TV banners. |
| `png/` | Ready-to-use store icons, marks, TV banners and logo exports. |

The default palette runs from amber `#FFB644` through orange `#FF7A14` to ember red
`#DC3016`, with charcoal `#101012` and ivory `#F8F3ED`. Dark grey, blue, green and rose
variants use the same geometry and follow the app's existing accent selection.
Monochrome marks retain both flame lobes with a transparent gap.

## Rebuilding

The shared paths and palettes live in `gen.py`. Use Python 3 to regenerate:

```sh
python3 tools/brand/android.py
python3 tools/brand/export.py
```

These commands use only Python's standard library. Android assets are lightweight
VectorDrawables; adaptive icon foregrounds stay inside the mask's safe zone. The app
uses the same flame and wordmark resources in navigation, About and startup.

For PNG exports and the preview sheets:

```sh
python3 -m pip install playwright
python3 -m playwright install chromium
python3 tools/brand/export.py --png
```

An installed `chromium` or `google-chrome` is also supported. Set
`CHROMIUM_EXECUTABLE` to select another browser executable. Raster exports are for
store listings and documentation; the app's branding does not load these PNGs.
