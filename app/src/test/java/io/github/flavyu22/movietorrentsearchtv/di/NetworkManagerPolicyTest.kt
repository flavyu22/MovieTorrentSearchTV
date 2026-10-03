package io.github.flavyu22.movietorrentsearchtv.di

import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkManagerPolicyTest {
    @Test
    fun acceptsOnlyLoopbackPrivateAndLinkLocalHostsForCleartext() {
        listOf(
            "localhost",
            "127.0.0.1",
            "10.20.30.40",
            "169.254.10.20",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.20",
            "::1",
            "fc00::1",
            "fd12:3456::1",
            "fe80::1",
        ).forEach { host ->
            assertTrue(host, NetworkManager.isPrivateNetworkHost(host))
        }

        listOf(
            "example.org",
            "torrserver.local",
            "evillocal",
            "8.8.8.8",
            "169.253.1.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.169.1.1",
            "192.168.001.020",
            "2001:4860:4860::8888",
        ).forEach { host ->
            assertFalse(host, NetworkManager.isPrivateNetworkHost(host))
        }
    }

    @Test
    fun cleartextIsAlwaysAllowedForLoopbackOnly() {
        // On-device TorrServer traffic stays possible in every distribution flavor.
        listOf("localhost", "127.0.0.1", "127.5.6.7", "::1").forEach { host ->
            assertTrue(host, NetworkManager.isCleartextHostPermitted(host))
        }

        // Public hosts are never eligible for cleartext, in any flavor.
        listOf("example.org", "8.8.8.8", "2001:4860:4860::8888").forEach { host ->
            assertFalse(host, NetworkManager.isCleartextHostPermitted(host))
        }

        // Broader private ranges follow the distribution capability flag.
        listOf(
            "10.20.30.40",
            "169.254.10.20",
            "172.16.0.1",
            "192.168.1.20",
            "fc00::1",
            "fe80::1",
        ).forEach { host ->
            assertEquals(
                host,
                BuildConfig.ALLOW_LAN_CLEARTEXT,
                NetworkManager.isCleartextHostPermitted(host),
            )
        }
    }
}
