package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibSourceIdTest {
    @Test
    fun `identifier uses unified Linovelib id`() {
        assertEquals("lightnovelreader", LINOVELIB_SOURCE_ID.namespace)
        assertEquals("linovelib", LINOVELIB_SOURCE_ID.id)
    }
}
