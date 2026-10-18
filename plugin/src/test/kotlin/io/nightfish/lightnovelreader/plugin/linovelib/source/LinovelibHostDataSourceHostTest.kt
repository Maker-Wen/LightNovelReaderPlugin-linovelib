package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.ContextWrapper
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run against the installed host: the Maven API cannot reproduce the renamed callback type. */
class LinovelibHostDataSourceHostTest {
    @Test
    fun actualHostCallbackDispatchesAuthorAndTagToPlugin() {
        val received = mutableListOf<String>()
        val wrapped = LinovelibHostDataSource.wrap(source()) { tag, controller ->
            received += tag
            assertNull(controller)
        }
        val callback = WebBookDataSource::class.java.methods.single { it.name == "progressBookTagClick" }
        // A concrete method with the real host descriptor must exist, rather than inheriting its no-op.
        assertNotNull(wrapped.javaClass.getDeclaredMethod(callback.name, *callback.parameterTypes))
        // Navigation itself is exercised by UI tests; null isolates the actual interface dispatch here.
        callback.invoke(wrapped, "作者：川原砾", null)
        callback.invoke(wrapped, "科幻", null)
        assertEquals(listOf("作者：川原砾", "科幻"), received)
    }

    @Test
    fun proxyPreservesSourceObjectsAndSuspendingBookResults() = runBlocking {
        val original = source()
        val wrapped = LinovelibHostDataSource.wrap(original) { _, _ -> fail("Unexpected tag callback") }
        assertEquals(original.id, wrapped.id)
        assertSame(original.cache, wrapped.cache)
        assertSame(original.explorePageProvider, wrapped.explorePageProvider)
        assertSame(original.searchProvider, wrapped.searchProvider)
        assertEquals("Live title", wrapped.getBookInformation("1667").title)
        assertTrue(wrapped == wrapped)
        assertFalse(wrapped == original)
        assertEquals(System.identityHashCode(wrapped), wrapped.hashCode())
    }

    @Test
    fun delegatedCancellationIsNotWrappedInReflectionException() = runBlocking {
        val cancelled = CancellationException("Cancelled by caller")
        val wrapped = LinovelibHostDataSource.wrap(source { throw cancelled }) { _, _ -> }
        assertSame(cancelled, runCatching { wrapped.getBookInformation("1667") }.exceptionOrNull())
    }

    @Test
    fun blankTagDoesNotRegisterPageOrNavigate() {
        val original = source()
        val wrapped = LinovelibHostDataSource.wrap(original, original::openRelatedPage)
        val pages = (original.explorePageProvider as LinovelibExplorePageProvider).exploreExpandedPageDataSourceMap
        val before = pages.keys.toSet()
        LinovelibHostDataSource.tagClickMethod.invoke(wrapped, "  ", null)
        assertEquals(before, pages.keys)
    }

    private fun source(load: suspend (String) -> String = { "<h1 class=\"book-title\">Live title</h1>" }) =
        LinovelibWebDataSource(
            object : ContextWrapper(null) {
                override fun getFilesDir() = File("/data/local/tmp/linovelib-navigation-host-test-unused")
            },
            LinovelibMetadataStore(),
            LinovelibDiagnostics { _, _, _, _ -> },
            load,
        )
}
