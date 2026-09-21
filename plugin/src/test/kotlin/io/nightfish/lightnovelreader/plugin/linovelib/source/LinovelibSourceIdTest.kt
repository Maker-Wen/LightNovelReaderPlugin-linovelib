package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibSourceIdTest {
    @Test
    fun `API 2 source id uses unified Linovelib name`() {
        assertEquals("linovelib".hashCode(), LINOVELIB_SOURCE_ID)
    }
}
