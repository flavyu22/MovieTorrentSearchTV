package io.github.flavyu22.movietorrentsearchtv.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupJankBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage = "io.github.flavyu22.movietorrentsearchtv"

    @Test
    fun startupAndFrameTiming() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric(),
            ),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
        ) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(targetPackage)
            }
            startActivityAndWait(intent)
            device.waitForIdle()
        }
    }
}
