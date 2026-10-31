package com.technicallyvu.scope.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TrustInfoTest {
    @Test
    fun `attributions are non-blank and well-formed`() {
        assertTrue(TrustInfo.attributions.size >= 6, "expected at least 6 attributions")
        for (a in TrustInfo.attributions) {
            assertTrue(a.name.isNotBlank(), "attribution name blank: $a")
            assertTrue(a.url.startsWith("https://"), "attribution url not https: $a")
            assertTrue(a.license.isNotBlank(), "attribution license blank: $a")
        }
    }

    @Test
    fun `source and donate urls are https and distinct`() {
        assertTrue(TrustInfo.sourceUrl.startsWith("https://"), "source url not https: ${TrustInfo.sourceUrl}")
        assertTrue(TrustInfo.donateUrl.startsWith("https://"), "donate url not https: ${TrustInfo.donateUrl}")
        assertTrue(TrustInfo.donateUrl != TrustInfo.sourceUrl, "donate url is still the source url")
    }

    @Test
    fun `license text is the full MIT license with attribution`() {
        assertTrue(TrustInfo.licenseText.contains("MIT License"))
        assertTrue(TrustInfo.licenseText.contains("Anthony Vu"))
    }

    @Test
    fun `no-network statement mentions INTERNET`() {
        assertTrue(TrustInfo.noNetworkStatement.contains("INTERNET"))
    }

    @Test
    fun `permissions not requested lists the sensitive android permissions`() {
        val perms = TrustInfo.permissionsNotRequested
        assertTrue(perms.contains("android.permission.INTERNET"))
        assertFalse(perms.contains("android.permission.CAMERA"))
        assertTrue(perms.contains("android.permission.RECORD_AUDIO"))
        assertTrue(perms.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(perms.contains("android.permission.READ_CONTACTS"))
    }
}
