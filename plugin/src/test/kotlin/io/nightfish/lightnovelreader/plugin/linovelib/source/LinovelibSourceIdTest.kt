package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibSourceIdTest {
    @Test
    fun `source id retains the unified Linovelib identity`() {
        assertEquals("linovelib".hashCode(), LINOVELIB_SOURCE_ID)
    }
}
