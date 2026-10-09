package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibChapterIdsTest {
    @Test
    fun appIdUsesTheReleasedV3Namespace() {
        listOf("61960", "linovelib-v2:61960", "linovelib-v3:61960").forEach { id ->
            assertEquals("linovelib-v3:61960", LinovelibChapterIds.forApp(id))
        }
    }

    @Test
    fun websiteIdRemovesInternalVersionPrefix() {
        assertEquals("61960", LinovelibChapterIds.forWebsite("linovelib-v3:61960"))
        assertEquals("61960", LinovelibChapterIds.forWebsite("linovelib-v2:61960"))
        assertEquals("61960", LinovelibChapterIds.forWebsite("61960"))
    }

    @Test
    fun emptyNavigationTargetsArePreserved() {
        listOf("", " ").forEach { id ->
            assertEquals(id, LinovelibChapterIds.forWebsite(id))
            assertEquals(id, LinovelibChapterIds.forApp(id))
        }
    }

    @Test
    fun nonVersionedIdsKeepTheirValueUnderTheAppPrefix() {
        listOf("chapter-one", "linovelib-v4:61960").forEach { id ->
            assertEquals(id, LinovelibChapterIds.forWebsite(id))
            assertEquals("linovelib-v3:$id", LinovelibChapterIds.forApp(id))
        }
    }
}
