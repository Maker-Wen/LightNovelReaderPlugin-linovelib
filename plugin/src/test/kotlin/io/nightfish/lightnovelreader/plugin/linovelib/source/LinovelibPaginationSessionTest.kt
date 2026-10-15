package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinovelibPaginationSessionTest {
    @Test
    fun `two pages with the same id paginate independently and returning to the first keeps its session`() = runBlocking {
        withTimeout(5_000) {
            val requested = mutableListOf<String>()
            val provider = LinovelibExplorePageProvider(
                htmlLoader = { url ->
                    requested += url
                    val id = when {
                        url.endsWith("_3_0.html") -> "300"
                        url.endsWith("_2_0.html") -> "200"
                        else -> "100"
                    }
                    listPage(id, lastPage = 3)
                },
                parser = LinovelibHtmlParser(),
                searchBookIds = { flowOf(SearchResult.End()) }
            )
            val first = provider.exploreExpandedPageDataSourceMap.getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID)
            val firstRegion = first.filters.first() as SingleChoiceFilter
            firstRegion.value = "日本轻小说"
            val firstResults = Channel<SearchResult>(Channel.UNLIMITED)
            val secondResults = Channel<SearchResult>(Channel.UNLIMITED)
            first.loadMore() // No session yet: this must not queue a request for the next collection.
            val firstCollection = launch { first.getResultFlow().collect { firstResults.send(it) } }
            var secondCollection: Job? = null
            try {
                assertEquals("100", (firstResults.receive() as SearchResult.SingleBook).bookId)
                val second = provider.exploreExpandedPageDataSourceMap.getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID)
                assertEquals("不限", (second.filters.first() as SingleChoiceFilter).value)
                secondCollection = launch { second.getResultFlow().collect { secondResults.send(it) } }
                assertEquals("100", (secondResults.receive() as SearchResult.SingleBook).bookId)

                first.loadMore()
                assertEquals("200", (firstResults.receive() as SearchResult.SingleBook).bookId)
                assertTrue(secondResults.tryReceive().isFailure)
                secondCollection.cancelAndJoin()

                assertEquals("日本轻小说", firstRegion.value)
                first.loadMore()
                assertEquals("300", (firstResults.receive() as SearchResult.SingleBook).bookId)
                assertTrue(firstResults.receive() is SearchResult.End)
                firstCollection.join()
                assertEquals(listOf(
                    "$HOST/wenku/lastupdate_0_0_0_1_0_0_0_1_0.html",
                    "$HOST/wenku/lastupdate_0_0_0_0_0_0_0_1_0.html",
                    "$HOST/wenku/lastupdate_0_0_0_1_0_0_0_2_0.html",
                    "$HOST/wenku/lastupdate_0_0_0_1_0_0_0_3_0.html"
                ), requested)
            } finally {
                firstCollection.cancelAndJoin()
                secondCollection?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `each collection snapshots filters and resets paging deduplication and pending requests`() = runBlocking {
        withTimeout(5_000) {
            val filters = LinovelibWenkuFilters(HOST)
            val region = filters.filters.first() as SingleChoiceFilter
            val requested = mutableListOf<String>()
            val page = LinovelibLinkedExpandedPageDataSource(
                displayTag = "文库", targetUrl = "$HOST/wenku/",
                htmlLoader = { url ->
                    requested += url
                    listPage(if (url.endsWith("_2_0.html")) "200" else "100")
                },
                parser = LinovelibHtmlParser(), filters = filters.filters, filteredUrl = { filters.url() }
            )
            val flow = page.getResultFlow()
            val results = Channel<SearchResult>(Channel.UNLIMITED)
            region.value = "日本轻小说"
            val first = launch { flow.collect { results.send(it) } }
            var second: Job? = null
            try {
                assertEquals("100", (results.receive() as SearchResult.SingleBook).bookId)
                region.value = "华文轻小说"
                page.loadMore()
                assertEquals("200", (results.receive() as SearchResult.SingleBook).bookId)
                assertTrue(results.receive() is SearchResult.End)
                first.join()
                page.loadMore() // A completed session must not leave a request for the next one.

                second = launch { flow.collect { results.send(it) } }
                assertEquals("100", (results.receive() as SearchResult.SingleBook).bookId)
                assertTrue(results.tryReceive().isFailure)
                assertEquals(3, requested.size)
                page.loadMore()
                assertEquals("200", (results.receive() as SearchResult.SingleBook).bookId)
                assertTrue(results.receive() is SearchResult.End)
                second.join()
                assertEquals(listOf(
                    "$HOST/wenku/lastupdate_0_0_0_1_0_0_0_1_0.html",
                    "$HOST/wenku/lastupdate_0_0_0_1_0_0_0_2_0.html",
                    "$HOST/wenku/lastupdate_0_0_0_2_0_0_0_1_0.html",
                    "$HOST/wenku/lastupdate_0_0_0_2_0_0_0_2_0.html"
                ), requested)
            } finally {
                first.cancelAndJoin()
                second?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancelled filter snapshot cannot request or replace a newer collection`() = runBlocking {
        withTimeout(5_000) {
            val oldSnapshotStarted = CompletableDeferred<Unit>()
            val releaseOldSnapshot = CountDownLatch(1)
            val snapshots = AtomicInteger()
            val requested = CopyOnWriteArrayList<String>()
            val oldResults = mutableListOf<SearchResult>()
            val newResults = Channel<SearchResult>(Channel.UNLIMITED)
            val page = LinovelibLinkedExpandedPageDataSource(
                displayTag = "排行",
                targetUrl = "$HOST/top/weekvisit/1.html",
                parser = LinovelibHtmlParser(),
                htmlLoader = { url ->
                    requested += url
                    listPage(if (url.endsWith("/2.html")) "200" else "100")
                },
                filteredUrl = {
                    if (snapshots.incrementAndGet() == 1) {
                        oldSnapshotStarted.complete(Unit)
                        check(releaseOldSnapshot.await(5, TimeUnit.SECONDS))
                        "$HOST/top/weekvisit/1.html"
                    } else {
                        "$HOST/top/monthvisit/1.html"
                    }
                }
            )
            val oldCollection = launch(Dispatchers.Default) { page.getResultFlow().collect { oldResults += it } }
            var newCollection: Job? = null
            try {
                oldSnapshotStarted.await()
                oldCollection.cancel()
                newCollection = launch { page.getResultFlow().collect { newResults.send(it) } }
                assertEquals("100", (newResults.receive() as SearchResult.SingleBook).bookId)

                releaseOldSnapshot.countDown()
                oldCollection.join()

                assertTrue(oldResults.isEmpty())
                assertEquals(listOf("$HOST/top/monthvisit/1.html"), requested.toList())
                page.loadMore()
                assertEquals("200", (newResults.receive() as SearchResult.SingleBook).bookId)
                assertTrue(newResults.receive() is SearchResult.End)
                newCollection.join()
            } finally {
                releaseOldSnapshot.countDown()
                oldCollection.cancelAndJoin()
                newCollection?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `late completion of a cancelled request cannot emit results or clear the newer session`() = runBlocking {
        withTimeout(5_000) {
            val oldRequestStarted = CompletableDeferred<Unit>()
            val releaseOldRequest = CompletableDeferred<Unit>()
            val requested = mutableListOf<String>()
            val oldResults = mutableListOf<SearchResult>()
            val newResults = Channel<SearchResult>(Channel.UNLIMITED)
            val page = LinovelibLinkedExpandedPageDataSource(
                displayTag = "排行", targetUrl = "$HOST/top/weekvisit/1.html",
                parser = LinovelibHtmlParser(),
                htmlLoader = { url ->
                    requested += url
                    if (requested.size == 1) {
                        oldRequestStarted.complete(Unit)
                        // Model a transport that returns after its caller has already cancelled.
                        withContext(NonCancellable) { releaseOldRequest.await() }
                        listPage("999")
                    } else {
                        listPage(if (url.endsWith("/2.html")) "200" else "100")
                    }
                }
            )
            val old = launch { page.getResultFlow().collect { oldResults += it } }
            var current: Job? = null
            try {
                oldRequestStarted.await()
                old.cancel()
                current = launch { page.getResultFlow().collect { newResults.send(it) } }
                assertEquals("100", (newResults.receive() as SearchResult.SingleBook).bookId)
                releaseOldRequest.complete(Unit)
                old.join()
                assertTrue(oldResults.isEmpty())

                page.loadMore()
                assertEquals("200", (newResults.receive() as SearchResult.SingleBook).bookId)
                assertTrue(newResults.receive() is SearchResult.End)
                current.join()
                assertEquals(listOf(
                    "$HOST/top/weekvisit/1.html", "$HOST/top/weekvisit/1.html", "$HOST/top/weekvisit/2.html"
                ), requested)
            } finally {
                releaseOldRequest.complete(Unit)
                old.cancelAndJoin()
                current?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancellation from URL snapshot or loader emits no terminal result and allows recollection`() = runBlocking {
        withTimeout(5_000) {
            for (cancelInSnapshot in listOf(true, false)) {
                var firstAttempt = true
                val page = LinovelibLinkedExpandedPageDataSource(
                    displayTag = "排行", targetUrl = "$HOST/top/weekvisit/1.html",
                    parser = LinovelibHtmlParser(),
                    filteredUrl = {
                        if (firstAttempt && cancelInSnapshot) throw CancellationException("Snapshot cancelled")
                        "$HOST/top/weekvisit/1.html"
                    },
                    htmlLoader = {
                        if (firstAttempt) throw CancellationException("Request cancelled")
                        listPage("100", lastPage = 1)
                    }
                )
                val cancelledResults = mutableListOf<SearchResult>()
                val cancelled = launch { page.getResultFlow().collect { cancelledResults += it } }
                cancelled.join()
                assertTrue(cancelled.isCancelled)
                assertTrue(cancelledResults.isEmpty())

                firstAttempt = false
                page.loadMore()
                val refreshed = page.getResultFlow().toList()
                assertEquals(2, refreshed.size)
                assertEquals("100", (refreshed.first() as SearchResult.SingleBook).bookId)
                assertTrue(refreshed.last() is SearchResult.End)
            }
        }
    }

    private fun listPage(bookId: String, lastPage: Int = 2) = """
        <div id="pagelink">第1/${lastPage}页</div>
        <a class="book-layout" href="/novel/$bookId.html"><span class="book-title">Book $bookId</span></a>
    """.trimIndent()

    private companion object {
        const val HOST = "https://www.bilinovel.net"
    }
}
