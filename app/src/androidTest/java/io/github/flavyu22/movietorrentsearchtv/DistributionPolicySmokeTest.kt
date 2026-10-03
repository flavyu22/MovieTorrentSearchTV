package io.github.flavyu22.movietorrentsearchtv

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DistributionPolicySmokeTest {
    @Test
    fun manifestMatchesDistributionPolicy() {
        val context = ApplicationProvider.getApplicationContext<MovieTorrentApplication>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val requestsInstaller = Manifest.permission.REQUEST_INSTALL_PACKAGES in requestedPermissions
        assertEquals(BuildConfig.ENABLE_SELF_UPDATE, requestsInstaller)

        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        val allowsCleartext = info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        assertEquals(BuildConfig.ALLOW_LAN_CLEARTEXT, allowsCleartext)
        assertFalse(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)

        if (BuildConfig.ENABLE_SELF_UPDATE) {
            assertTrue(BuildConfig.ALLOW_LAN_CLEARTEXT)
        }
    }
}
