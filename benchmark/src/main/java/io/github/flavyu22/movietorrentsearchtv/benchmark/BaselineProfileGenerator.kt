package io.github.flavyu22.movietorrentsearchtv.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "io.github.flavyu22.movietorrentsearchtv",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // The benchmark-only target starts authenticated. Catalogue availability remains
        // external, so startup profiling succeeds even when no list is returned.
        device.wait(Until.hasObject(By.scrollable(true)), CATALOGUE_WAIT_MS)
        val grid = device.findObject(By.scrollable(true))
        grid?.let {
            it.setGestureMargin(device.displayWidth / 10)
            it.fling(Direction.DOWN)
            device.waitForIdle()
            it.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    private companion object {
        const val CATALOGUE_WAIT_MS = 15_000L
    }
}
