package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibDataSourceConfigurationTest {
    @Test
    fun `cache configuration is 256 entries per type and six hours`() {
        assertEquals(256, LinovelibDataSourceConfiguration.cacheEntriesPerType)
        assertEquals(21_600_000, LinovelibDataSourceConfiguration.cacheTimeoutMillis)
    }

    @Test
    fun `every default endpoint uses the single site`() {
        val host = "https://www.bilinovel.net"
        assertEquals(host, LinovelibUrls.HOST)
        assertEquals("$host/top.html", LinovelibUrls.top(host))
        assertEquals("$host/wenku/lastupdate_0_0_0_0_0_0_0_1_0.html", LinovelibUrls.wenku(host, "lastupdate", 1))
        assertEquals("$host/novel/1804.html", LinovelibUrls.book(host, "1804"))
        assertEquals("$host/novel/1804/catalog", LinovelibUrls.catalog(host, "1804"))
        assertEquals("$host/novel/1804/65891.html", LinovelibUrls.chapter(host, "1804", "65891"))
        assertEquals("$host/files/article/image/1/1804/1804s.jpg", LinovelibUrls.cover(host, "1804"))
    }
}
