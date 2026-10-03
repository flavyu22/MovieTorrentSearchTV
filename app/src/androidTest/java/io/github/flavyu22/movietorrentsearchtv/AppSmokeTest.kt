package io.github.flavyu22.movietorrentsearchtv

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test fun productionIdentityAndBackupPolicyAreApplied() {
        val context = ApplicationProvider.getApplicationContext<MovieTorrentApplication>()
        assertEquals("io.github.flavyu22.movietorrentsearchtv", context.packageName)
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertFalse(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
