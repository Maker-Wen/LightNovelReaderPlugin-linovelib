package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibExplorePageProviderTest {
    @Test
    fun `each host page gets independent filters while a map snapshot keeps stable instances`() {
        val provider: ExplorePageProvider.DefaultExplorePageProvider = provider {
            error("Obtaining a page must not load HTML")
        }
        val firstMap = provider.exploreExpandedPageDataSourceMap
        val first = firstMap.getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID)
        val firstRegion = first.filters.first() as SingleChoiceFilter
        firstRegion.value = "日本轻小说"
        var firstPageChanges = 0
        firstRegion.addOnChangeListener { firstPageChanges++ }

        val secondMap = provider.exploreExpandedPageDataSourceMap
        val second = secondMap.getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID)

        assertSame(first, firstMap.getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID))
        assertNotSame(firstMap, secondMap)
        assertNotSame(first, second)
        assertEquals(6, second.filters.size)
        first.filters.zip(second.filters).forEach { (a, b) -> assertNotSame(a, b) }
        val secondRegion = second.filters.first() as SingleChoiceFilter
        assertEquals("不限", secondRegion.value)
        secondRegion.value = "华文轻小说"
        assertEquals("日本轻小说", firstRegion.value)
        assertEquals(0, firstPageChanges)
    }

    @Test
    fun `registration and mutations of one map do not change other snapshots`() {
        val provider = provider { error("Creating a snapshot must not load HTML") }
        val before = provider.exploreExpandedPageDataSourceMap
        val relatedId = provider.registerRelatedPage("未知标签")
        val after = provider.exploreExpandedPageDataSourceMap

        assertFalse(before.containsKey(relatedId))
        assertTrue(after.containsKey(relatedId))
        assertEquals(relatedId, provider.registerRelatedPage("  未知标签  "))
        assertSame(after.getValue(relatedId), after.getValue(relatedId))
        assertNotSame(after.getValue(relatedId), provider.exploreExpandedPageDataSourceMap.getValue(relatedId))

        after.clear()
        val latest = provider.exploreExpandedPageDataSourceMap
        assertEquals(setOf(LinovelibExplorePageProvider.WENKU_PAGE_ID, relatedId), latest.keys)
        assertEquals(setOf(LinovelibExplorePageProvider.WENKU_PAGE_ID), before.keys)
    }

    @Test
    fun `learned related URLs keep the id and leave existing pages on their original target`() = runBlocking {
        val parser = LinovelibHtmlParser()
        val metadata = LinovelibMetadataStore()
        val requested = mutableListOf<String>()
        val searched = mutableListOf<String>()
        val provider = LinovelibExplorePageProvider(
            htmlLoader = { url ->
                requested += url
                """<a class="book-layout" href="/novel/100.html"><span class="book-title">Book</span></a>"""
            },
            parser = parser,
            searchBookIds = { keyword -> searched += keyword; flowOf(SearchResult.End()) },
            metadata = metadata
        )
        val id = provider.registerRelatedPage("科幻")
        val fallbackMap = provider.exploreExpandedPageDataSourceMap
        val fallback = fallbackMap.getValue(id)
        fun learnTarget(path: String) {
            parser.parseBookInformation("100", """
                <h1 class="book-title">Book</h1>
                <span class="tag-small-group"><em class="tag-small red"><a href="$path">科幻</a></em></span>
            """).also(metadata::rememberBook)
            assertEquals(id, provider.registerRelatedPage("科幻"))
        }
        learnTarget("/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html")
        val firstLinkedMap = provider.exploreExpandedPageDataSourceMap
        val firstLinked = firstLinkedMap.getValue(id)
        learnTarget("/wenku/goodnum_56_0_0_0_0_0_0_1_0.html")
        val latest = provider.exploreExpandedPageDataSourceMap.getValue(id)

        assertSame(fallback, fallbackMap.getValue(id))
        assertSame(firstLinked, firstLinkedMap.getValue(id))
        fallback.getResultFlow().toList()
        firstLinked.getResultFlow().toList()
        latest.getResultFlow().toList()

        assertEquals(listOf("科幻"), searched)
        assertEquals(listOf(
            "${LinovelibUrls.HOST}/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            "${LinovelibUrls.HOST}/wenku/goodnum_56_0_0_0_0_0_0_1_0.html"
        ), requested)
    }

    @Test
    fun `author tag and publisher links all create independent pages`() = runBlocking {
        val parser = LinovelibHtmlParser()
        val metadata = LinovelibMetadataStore()
        parser.parseBookInformation("100", """
            <h1 class="book-title">Book</h1>
            <span class="authorname"><a href="/author/123.html">Author</a></span>
            <span class="tag-small-group">
              <em class="tag-small red"><a href="/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html">科幻</a></em>
              <em class="tag-small orange"><a href="/wenku/lastupdate_0_0_0_0_0_1_0_1_0.html">文库出版社</a></em>
            </span>
        """).also(metadata::rememberBook)
        val requested = mutableListOf<String>()
        val provider = LinovelibExplorePageProvider(
            { url -> requested += url; "<ol class=\"book-ol\"></ol>" }, parser,
            { error("Known related links must not fall back to keyword search") }, metadata = metadata
        )
        val ids = listOf("作者：Author", "科幻", "文库出版社").map(provider::registerRelatedPage)
        val first = provider.exploreExpandedPageDataSourceMap
        val second = provider.exploreExpandedPageDataSourceMap
        ids.forEach { id ->
            assertNotSame(first.getValue(id), second.getValue(id))
            second.getValue(id).getResultFlow().toList()
        }
        assertEquals(listOf(
            "${LinovelibUrls.HOST}/author/123.html",
            "${LinovelibUrls.HOST}/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            "${LinovelibUrls.HOST}/wenku/lastupdate_0_0_0_0_0_1_0_1_0.html"
        ), requested)
    }

    @Test
    fun `concurrent registrations keep colliding titles on separate stable ids`() = runBlocking {
        withTimeout(5_000) {
            assertEquals("FB".hashCode(), "Ea".hashCode())
            val provider = provider { error("Registration must not load HTML") }
            val start = CompletableDeferred<Unit>()
            val registrations = List(8) { worker ->
                async(Dispatchers.Default) {
                    start.await()
                    List(50) { iteration ->
                        val title = if ((worker + iteration) % 2 == 0) "FB" else "Ea"
                        val id = provider.registerRelatedPage(title)
                        assertEquals(title, provider.exploreExpandedPageDataSourceMap.getValue(id).title)
                        title to id
                    }
                }
            }
            start.complete(Unit)
            val idsByTitle = registrations.awaitAll().flatten().groupBy({ it.first }, { it.second })
            assertEquals(setOf("FB", "Ea"), idsByTitle.keys)
            assertTrue(idsByTitle.values.all { it.distinct().size == 1 })
            assertEquals(2, idsByTitle.values.flatten().distinct().size)
            assertEquals(3, provider.exploreExpandedPageDataSourceMap.size)
        }
    }

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
