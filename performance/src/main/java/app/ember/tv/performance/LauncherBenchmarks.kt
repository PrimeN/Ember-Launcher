package app.ember.tv.performance

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class LauncherBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Before fun requireReleaseOptimization() {
        check(InstrumentationRegistry.getArguments().getString("profileGeneration") != "true") {
            "Frame/startup comparisons must omit -PgenerateStartupProfiles so release R8 optimization is enabled."
        }
    }

    @Test fun warmStartup() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(), startupMode = StartupMode.WARM,
        iterations = 5, setupBlock = { pressHome() },
    ) { startLauncher() }

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(), startupMode = StartupMode.COLD,
        iterations = 5, setupBlock = { pressHome() },
    ) { startLauncher() }

    @Test fun homeFrames() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { pressHome(); startLauncher() },
    ) { browseHome() }

    @Test fun searchFrames() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { pressHome(); startLauncher(); openNavigation("Search") },
    ) {
        device.pressDPadDown()
        repeat(8) { device.pressDPadRight() }
        repeat(8) { device.pressDPadLeft() }
        device.pressDPadUp()
    }

    @Test fun settingsFrames() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { pressHome(); startLauncher(); openSettings() },
    ) {
        repeat(6) { device.pressDPadDown() }
        repeat(6) { device.pressDPadUp() }
    }

    @Test fun guideFrames() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { pressHome(); startLauncher(); openNavigation("Live") },
    ) {
        repeat(6) { device.pressDPadDown() }
        repeat(12) { device.pressDPadRight() }
        repeat(12) { device.pressDPadLeft() }
        repeat(6) { device.pressDPadUp() }
    }
}
