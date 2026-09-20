package io.nightfish.lightnovelreader.plugin.linovelib_simplified.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibSourceIdTest {
    @Test
    fun `API 4 identifier keeps API 2 source id`() {
        assertEquals("linovelib_simplified".hashCode().toString(), LINOVELIB_LEGACY_SOURCE_ID)
        assertEquals("lightnovelreader", LINOVELIB_SOURCE_ID.namespace)
        assertEquals(LINOVELIB_LEGACY_SOURCE_ID, LINOVELIB_SOURCE_ID.id)
    }
}
