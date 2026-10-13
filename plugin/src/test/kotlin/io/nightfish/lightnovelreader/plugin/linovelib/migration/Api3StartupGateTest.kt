package io.nightfish.lightnovelreader.plugin.linovelib.migration

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Api3StartupGateTest {
    @Test
    fun waitsUntilTheDefaultBookshelfWriterCompletes() {
        val parent = Job()
        repeat(10) { Job(parent) }
        val initializer = Job(parent)
        try {
            assertFalse(api3DefaultInitializationFinished(parent, 37))
            initializer.complete()
            assertTrue(api3DefaultInitializationFinished(parent, 37))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun cancelledParentCannotLookLikeACompletedInitializer() {
        val parent = Job()
        repeat(10) { Job(parent) }
        parent.cancel()
        assertFalse(api3DefaultInitializationFinished(parent, 37))
        assertFalse(api3DefaultInitializationFinished(null, 37))
    }

    @Test
    fun doesNotAdmitMissingOrAdditionalChildren() {
        val parent = Job()
        repeat(9) { Job(parent) }
        try {
            assertFalse(api3DefaultInitializationFinished(parent, 37))
            assertTrue(api3DefaultInitializationFinished(parent, 30))
            val initializer = Job(parent)
            assertFalse(api3DefaultInitializationFinished(parent, 30))
            initializer.complete()
            assertTrue(api3DefaultInitializationFinished(parent, 30))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun acceptsOnlyTheIdentifiedIdlePeriodicWorkerInProcessorMaps() {
        assertTrue(api3ProcessorContainsOnlyIdlePeriodicWork(
            listOf(emptySet<String>(), emptySet<String>(), emptySet<String>()), emptySet()
        ))
        assertTrue(api3ProcessorContainsOnlyIdlePeriodicWork(
            listOf(emptySet(), setOf("known-id"), setOf("known-id")), setOf("known-id")
        ))
        assertFalse(api3ProcessorContainsOnlyIdlePeriodicWork(
            listOf(emptySet(), setOf("cache-worker"), setOf("cache-worker")), setOf("known-id")
        ))
        assertFalse(api3ProcessorContainsOnlyIdlePeriodicWork(
            listOf(setOf("known-id", "import-worker")), setOf("known-id")
        ))
        assertFalse(api3ProcessorContainsOnlyIdlePeriodicWork(listOf(setOf(42)), setOf("known-id")))
    }
}
