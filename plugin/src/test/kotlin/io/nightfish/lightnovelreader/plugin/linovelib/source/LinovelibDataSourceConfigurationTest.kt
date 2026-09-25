package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibDataSourceConfigurationTest {
    @Test
    fun `session cache retains book 3768 chapter count for six hours`() {
        assertEquals(256, LinovelibDataSourceConfiguration.cacheEntriesPerType)
        assertEquals(21_600_000, LinovelibDataSourceConfiguration.cacheTimeoutMillis)
    }

    @Test
    fun `offline monitor starts only when no active monitor exists`() {
        assertTrue(LinovelibDataSourceConfiguration.shouldStartOfflineMonitor(false))
        assertFalse(LinovelibDataSourceConfiguration.shouldStartOfflineMonitor(true))
    }

    @Test
    fun `every default endpoint uses the single site`() {
        val host = "https://www.bilinovel.net"
        assertEquals(host, LinovelibUrls.HOST)
        assertEquals("$host/top.html", LinovelibUrls.top(host))
        assertEquals("$host/topfull/postdate/1.html", LinovelibUrls.complete(host))
        assertEquals("$host/wenku/lastupdate_0_0_0_0_0_0_0_1_0.html", LinovelibUrls.wenku("lastupdate", 1))
        assertEquals("$host/novel/1804.html", LinovelibUrls.book("1804"))
        assertEquals("$host/novel/1804/catalog", LinovelibUrls.catalog("1804"))
        assertEquals("$host/novel/1804/65891.html", LinovelibUrls.chapter("1804", "65891"))
        assertEquals("$host/novel/1804/65891.html", LinovelibUrls.fullChapter("1804", "65891"))
        assertEquals("$host/files/article/image/1/1804/1804s.jpg", LinovelibUrls.cover("1804"))
    }
}
