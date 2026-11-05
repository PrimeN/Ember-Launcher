# Device performance and startup profiles

This opt-in Android test module generates observed Baseline/Startup Profiles and
measures warm startup and Home, Search, Settings and guide frames. It adds no dependencies to
the shipped launcher. Normal Gradle invocations do
not include this module or create the benchmark app variant.

The `benchmark` variant copies release optimization, uses a local debug certificate
and installs as `app.ember.tv.benchmark`. It does not overwrite the real launcher,
its account data or preferences. Do not make it the device's default Home app.
The device must support one of the existing packaged ARM architectures. Use an
Android 13+ test TV for non-root profile collection, or a compatible rooted ARM
test image for older Android. Baseline collection may be unavailable on OEM builds
that restrict ART profile access; do not disable those restrictions.

Build/install the test launcher, then configure an **English adult profile** and
enable Home, Discover and Search. Dismiss onboarding/profile/PIN dialogs and turn
off the startup profile prompt for this test installation. Use the
same catalogs and theme when comparing commits. Select Low or High before each
run and record Auto's resolved value if using Auto. A large configured IPTV feed
is required for the guide benchmark; do not compare an empty guide with a full one.

```bash
gradle -PincludePerformanceTests=true -PgenerateStartupProfiles=true :app:installBenchmark

# Actual startup and browsing profile generation. Live is opt-in with a seeded feed.
gradle -PincludePerformanceTests=true -PgenerateStartupProfiles=true :performance:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.ember.tv.performance.LauncherProfiles
# Add this argument only after configuring a Live feed:
# -Pandroid.testInstrumentationRunnerArguments.includeLive=true

# Frame measurements, separately from profile collection:
gradle -PincludePerformanceTests=true :app:installBenchmark
gradle -PincludePerformanceTests=true :performance:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.ember.tv.performance.LauncherBenchmarks
```

For a device without a feed, run only `LauncherBenchmarks#warmStartup` or
`LauncherBenchmarks#homeFrames` via the class argument. These tests may force-stop
and change ART compilation of the **benchmark** package; keep testing on that
separate installation. Connected test output under `performance/build/outputs/`
contains profile text, benchmark JSON and traces. Preserve those artifacts with
the tested commit, profile, model/OS, refresh rate, feed size and cache conditions.
The tests intentionally fail when required navigation is unavailable instead of
silently generating a profile for the wrong screen. UI labels currently require
English. Frame measurements are not remote input-to-focus latency measurements.

`generateStartupProfiles` disables R8/resource shrinking only in the separate
benchmark installation while collecting original method names. It leaves normal
debug/release builds unchanged. The profile tests require this flag; frame/startup
benchmarks reject it so measurements use release optimization. Switching between
the two keeps the benchmark package's configured feeds/preferences.

Inspect generated profiles, validate representative coverage and compare release
startup/frame results before replacing `app/src/main/baseline-prof.txt`. Copy the
observed startup-only rules to `app/src/main/startup-prof.txt` and the merged
observed journey rules to `app/src/main/baseline-prof.txt` after that validation.
Do not mark every method as startup/hot or call a hand-written list measured.
Keep generated device artifacts and account/feed data out of Git.

The existing broad profile remains until actual device generation/validation.
Building this module checks the harness; it does not generate a profile, execute
TV journeys or demonstrate a speed increase without a connected device.

## Additional measurements

The harness also measures cold startup and includes Tonight/Now jumps in the
opt-in populated-guide profile journey. Keep `includeLive` off unless a playlist
and XMLTV programmes are present. Use `LauncherBenchmarks#coldStartup` for cold
startup separately from warm startup; neither number is first-input latency.

For the effects presets, choose Fast, Balanced or Cinematic on the TV before using
`tools/profile-tv.sh OUTPUT_DIR fast|balanced|cinematic`. These are recording labels;
the script does not modify or verify the TV's settings. Record artwork quality,
refresh rate and the live/cached frost method independently. For Custom, the JSON
also accepts the optional `navigationGlassMethod` field (`live` or `cached`).

Auto's observed-frame adaptation runs in release builds only. Prefer a manual mode
for fixed comparisons, or reset Auto observations before each run and record if it
switches to lighter effects. Do not compare debug learning with release learning.

Generate profiles on a connected test device using the process above. Inspect the
measured rules and validate release timings before replacing checked-in profiles.
