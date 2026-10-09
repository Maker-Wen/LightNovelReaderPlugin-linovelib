package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibExplorePageProviderTest {
    @Test
    fun `every tab clears previously displayed rows before its html request completes`() = runBlocking {
        for (tabTitle in listOf("推荐", "文库", "排行", "完结")) {
            val requestStarted = CompletableDeferred<Unit>()
            val htmlResponse = CompletableDeferred<String>()
            val provider = provider {
                requestStarted.complete(Unit)
                htmlResponse.await()
            }
            val pageId = provider.explorePageIdList.single {
                provider.exploreTapPageDataSourceMap.getValue(it).title == tabTitle
            }
            var displayedRows = listOf(ExploreBooksRow("Previous tab", emptyList()))
            val collection = launch {
                provider.exploreTapPageDataSourceMap.getValue(pageId).getRowsFlow()
                    .collect { displayedRows = it }
            }

            try {
                withTimeout(1_000) { requestStarted.await() }

                assertFalse("$tabTitle request should still be pending", htmlResponse.isCompleted)
                assertTrue("$tabTitle collection should still be loading", collection.isActive)
                assertTrue("$tabTitle must clear the previous tab before waiting for HTML", displayedRows.isEmpty())
            } finally {
                withTimeout(1_000) { collection.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `switching from wenku to complete clears wenku rows while loading and then displays complete rows`() = runBlocking {
        val completeRequestStarted = CompletableDeferred<Unit>()
        val completeHtmlResponse = CompletableDeferred<String>()
        val provider = provider { url ->
            if (url == LinovelibUrls.complete(LinovelibUrls.HOST)) {
                completeRequestStarted.complete(Unit)
                completeHtmlResponse.await()
            } else {
                ""
            }
        }
        val pageIds = provider.explorePageIdList.associateBy {
            provider.exploreTapPageDataSourceMap.getValue(it).title
        }
        var displayedRows = emptyList<ExploreBooksRow>()
        val wenkuCollection = launch {
            provider.exploreTapPageDataSourceMap.getValue(pageIds.getValue("文库")).getRowsFlow()
                .collect { displayedRows = it }
        }
        var completeCollection: Job? = null

        try {
            withTimeout(1_000) { wenkuCollection.join() }
            assertEquals(listOf("全部轻小说"), displayedRows.map { it.title })

            val loadingComplete = launch {
                provider.exploreTapPageDataSourceMap.getValue(pageIds.getValue("完结")).getRowsFlow()
                    .collect { displayedRows = it }
            }
            completeCollection = loadingComplete
            withTimeout(1_000) { completeRequestStarted.await() }

            assertFalse(completeHtmlResponse.isCompleted)
            assertTrue(loadingComplete.isActive)
            assertTrue("The completed tab must clear the previously displayed wenku row", displayedRows.isEmpty())

            completeHtmlResponse.complete("")
            withTimeout(1_000) { loadingComplete.join() }

            assertEquals(listOf("完结轻小说"), displayedRows.map { it.title })
        } finally {
            withTimeout(1_000) {
                wenkuCollection.cancelAndJoin()
                completeCollection?.cancelAndJoin()
            }
        }
    }

    private fun provider(htmlLoader: suspend (String) -> String) = LinovelibExplorePageProvider(
        htmlLoader = htmlLoader,
        parser = LinovelibHtmlParser(),
        searchBookIds = { flowOf(SearchResult.End()) }
    )
}
