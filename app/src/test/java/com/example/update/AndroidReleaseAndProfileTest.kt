package com.example.update

import com.example.storage.preferences.PortableChartProfile
import com.example.storage.preferences.TerminalSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidReleaseAndProfileTest {
    private fun artifact(v: Int, tag: String = "android-v$v", url: String = AndroidRelease(v).downloadUrl, draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":false,"assets":[{"name":"LUMAVUN-Android.apk","state":"uploaded","size":100,"browser_download_url":"$url"}]}"""
    @Test fun updaterOnlySelectsNewOfficialAndroidRelease() {
        val data = "[" + listOf(artifact(100002),artifact(100010,"windows-v100010"),artifact(100020,url="https://example.com/evil.apk"),artifact(100030,draft=true),artifact(100004)).joinToString(",") + "]"
        assertEquals(100004,AndroidReleaseIndex.findNewer(data,100001)!!.versionCode)
        assertNull(AndroidReleaseIndex.findNewer(data,100004))
    }
    @Test fun chartBackupRoundTripsOnlyWhitelistedSettings() {
        val profile = PortableChartProfile.from(TerminalSettings())
        assertEquals(profile,PortableChartProfile.decode(profile.encode()))
        assertFalse(profile.encode().contains("apiKey",ignoreCase=true))
        assertFalse(profile.encode().contains("token",ignoreCase=true))
        assertFalse(profile.encode().contains("bot",ignoreCase=true))
        assertTrue(runCatching { PortableChartProfile.decode("{}") }.isFailure)
    }
}
