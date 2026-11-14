package com.mukimov.salom

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun release(code: Long = 9, version: String = "0.7.0", url: String = "https://mukimov.site/salom/salom-0.7.0.apk", minSdk: Int = 24) =
        AppRelease(version, code, minSdk, url, 2_000_000, "a".repeat(64))

    @Test fun latestRequiresMatchingBuildAndLabel() {
        assertEquals(ReleaseRelation.CURRENT, releaseRelation(9, "0.7.0", 34, release()))
        assertThrows(IllegalArgumentException::class.java) { releaseRelation(9, "0.7.1", 34, release()) }
    }
    @Test fun olderServerBuildIsNotPresentedAsLatest() {
        assertEquals(ReleaseRelation.LOCAL_AHEAD, releaseRelation(10, "0.7.1", 34, release()))
    }
    @Test fun newerBuildAndIncompatibleAndroidAreDistinct() {
        assertEquals(ReleaseRelation.NEWER_AVAILABLE, releaseRelation(8, "0.6.0", 34, release()))
        assertEquals(ReleaseRelation.INCOMPATIBLE, releaseRelation(8, "0.6.0", 24, release(minSdk = 35)))
    }
    @Test fun rejectsUntrustedDownloadLocations() {
        listOf("http://mukimov.site/salom/salom-0.7.0.apk", "https://evil.example/salom/salom-0.7.0.apk",
            "https://mukimov.site.evil.example/salom/salom-0.7.0.apk", "https://user@mukimov.site/salom/salom-0.7.0.apk",
            "https://mukimov.site:444/salom/salom-0.7.0.apk", "https://mukimov.site/salom/../other.apk",
            "https://mukimov.site/salom/salom-0.7.0.apk?redirect=evil").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { release(url = url) }
        }
    }
    @Test fun rejectsInvalidChecksumAndOversizedDownload() {
        assertThrows(IllegalArgumentException::class.java) { release().copy(sha256 = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { release().copy(size = 104_857_601) }
    }
}
