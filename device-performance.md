# Device performance and measurement

**Settings → Appearance → Device performance** selects the launcher rendering
budget. The default is Auto; manual choices are Low, Medium, High, Ultra (4K)
and Custom. Ultra is offered only on a 4K display.

| Profile | Intended behaviour |
|---|---|
| Auto | Chooses Low, Medium or High from the device's reported memory capabilities. This is a heuristic, not a CPU/GPU benchmark. |
| Low | Uses conservative artwork and effects budgets for limited-memory devices. |
| Medium | Keeps subtle motion and focus colours while skipping hero analysis, progressive artwork and collection motion. |
| High | Allows richer artwork and effects within the normal memory guards. |
| Ultra | Enables the 4K artwork tier on supported displays. |
| Custom | Separates image quality, analysis, motion, progressive loading, collection previews and prefetch choices. |

Reduced-motion preferences and device memory guards still apply. These controls
change launcher rendering and optional background work; they do not select a lower
video stream quality or change playback source-ranking rules.

## Measurement

Use a release-optimized build and record the device/OS, refresh rate, profile,
artwork settings, feed size, network conditions and cache state. Compare the same
Home, Search, Settings and Live journeys with the same configured sources. Check
both initial loading and repeat navigation; an empty guide is not comparable to a
populated guide.

[The performance module](performance/README.md) documents the separate benchmark
installation and measured startup-profile generation. It is excluded from ordinary
builds. [tools/profile-tv.sh](tools/profile-tv.sh) records device traces for the
user-supplied settings label; it does not change those settings.

Use actual device measurements before changing checked-in startup profiles or
claiming a speed increase. Avoid committing device account data, private playlists,
trace exports or generated benchmark artifacts.
