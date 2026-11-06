package com.dragonemperor9480.yaedock.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun v(text: String) = Version.parse(text)!!

    @Test
    fun ordersVersionsAsSemverDoes() {
        assertTrue(v("0.9.0") < v("0.10.0"))
        assertTrue(v("0.1.1-beta.1") < v("0.1.1"))
        assertTrue(v("0.1.1-beta.2") < v("0.1.1-beta.10"))
        assertTrue(v("0.1.1-alpha") < v("0.1.1-beta"))
        assertTrue(v("0.1.1-1") < v("0.1.1-alpha"))
        assertTrue(v("0.1.1-beta") < v("0.1.1-beta.1"))
        assertEquals(0, v("v1.2.3").compareTo(v("1.2.3")))
        assertEquals("0.2.0-rc.1", v("0.2.0-rc.1").toString())
        assertNull(Version.parse("nightly"))
        assertNull(Version.parse("1.2"))
    }

    private val list = """[
      {"tag_name":"v0.3.0","draft":true,"assets":[]},
      {"tag_name":"v0.2.0-beta.1","draft":false,"prerelease":true,"body":" Beta \n","html_url":"b","assets":[
        {"name":"yaedock-0.2.0-beta.1-arm64-v8a.apk","browser_download_url":"https://x/a.apk","size":10,"state":"uploaded"},
        {"name":"SHA256SUMS","browser_download_url":"https://x/s","size":1}]},
      {"tag_name":"nightly","assets":[]},
      {"tag_name":"v0.1.0","body":null,"html_url":"a","assets":[]}
    ]"""

    @Test
    fun picksTheNewestPublishedRelease() {
        val newest = Releases.newest(list)!!
        // The draft is newer but not published; a pre-release counts.
        assertEquals(v("0.2.0-beta.1"), newest.version)
        assertEquals("Beta", newest.notes)
        assertEquals("https://x/a.apk", newest.apk(listOf("x86_64", "arm64-v8a"))?.url)
        assertNull(newest.apk(listOf("armeabi-v7a")))
        assertNull(Releases.newest("[]"))
    }

    @Test
    fun readsChecksumsAsSha256sumWritesThem() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = "$a  yaedock-0.1.0-arm64-v8a.apk\n$b *yaedock\n"
        assertEquals("b".repeat(64), Releases.checksum(sums, "yaedock"))
        assertEquals(a, Releases.checksum(sums, "yaedock-0.1.0-arm64-v8a.apk"))
        assertNull(Releases.checksum(sums, "other"))
        assertNull(Releases.checksum("short  yaedock", "yaedock"))
    }
}
