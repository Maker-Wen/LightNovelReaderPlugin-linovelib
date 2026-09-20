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
    fun `site setting defaults to simplified and restores known values`() {
        assertEquals(LinovelibSite.SIMPLIFIED, LinovelibSite.fromStoredValue(null))
        assertEquals(LinovelibSite.SIMPLIFIED, LinovelibSite.fromStoredValue("unknown"))
        assertEquals(LinovelibSite.TRADITIONAL, LinovelibSite.fromStoredValue("TRADITIONAL"))
    }

    @Test
    fun `site host controls every book endpoint`() {
        assertEquals(
            "https://www.bilinovel.com/novel/1804.html",
            LinovelibUrls.book(LinovelibSite.SIMPLIFIED.host, "1804")
        )
        assertEquals(
            "https://tw.linovelib.com/novel/1804/catalog",
            LinovelibUrls.catalog(LinovelibSite.TRADITIONAL.host, "1804")
        )
        assertEquals(
            "https://tw.linovelib.com/novel/1804/65891.html",
            LinovelibUrls.fullChapter(LinovelibSite.TRADITIONAL.host, "1804", "65891")
        )
    }
}
