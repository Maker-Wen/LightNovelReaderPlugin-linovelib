package io.nightfish.lightnovelreader.plugin.linovelib

import io.nightfish.lightnovelreader.api.plugin.Plugin
import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibPluginCompatibilityTest {
    @Test
    fun `plugin targets API 3`() {
        val metadata = requireNotNull(LinovelibPlugin::class.java.getAnnotation(Plugin::class.java))

        assertEquals(3, metadata.apiVersion)
    }
}
