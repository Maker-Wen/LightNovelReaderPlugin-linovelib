package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibHomeRankingHostTest {
    @Test
    fun capturedHomepageDisplaysRecommendationGroupsAndOnlyTheirVerifiedLinks() = runBlocking {
        val html = javaClass.getResource("/html/home-modules-20261027.html")!!.readText()
        val requested = mutableListOf<String>()
        val metadata = LinovelibMetadataStore()
        val provider = LinovelibExplorePageProvider(
            htmlLoader = { url -> requested += url; html },
            parser = LinovelibHtmlParser(),
            searchBookIds = { flowOf(SearchResult.End()) },
            metadata = metadata
        )
        val rows = provider.exploreTapPageDataSourceMap.values.single { it.title == "推荐" }
            .getRowsFlow().toList().last()

        assertEquals(listOf(LinovelibUrls.HOST), requested)
        assertEquals(
            listOf("推荐", "排行", "文库"),
            provider.explorePageIdList.map { provider.exploreTapPageDataSourceMap.getValue(it).title }
        )
        assertEquals(
            listOf("勇者新推", "新书抢鲜", "人气完本"),
            rows.map { it.title }
        )
        assertEquals(listOf(10, 6, 6), rows.map { it.bookList.size })
        assertEquals(
            listOf(
                listOf("3080", "5407", "1855", "4164", "5190", "2636", "5454", "3663", "2640", "4947"),
                listOf("5497", "5496", "5495", "5494", "5493", "5492"),
                listOf("3080", "1855", "4164", "3663", "87", "3678")
            ),
            rows.map { row -> row.bookList.map { it.id } }
        )
        assertFalse(rows.first().expandable)
        assertNull(rows.first().expandedPageDataSourceId)
        rows.drop(1).forEach { row -> assertTrue(row.expandable) }
        assertEquals(
            listOf(
                "linovelib-list-/wenku/postdate_0_0_0_0_0_0_0_1_0.html",
                "linovelib-list-/topfull/weekvote/1.html"
            ),
            rows.drop(1).map { it.expandedPageDataSourceId }
        )
        assertEquals(
            setOf(
                LinovelibExplorePageProvider.WENKU_PAGE_ID,
                "linovelib-list-/wenku/postdate_0_0_0_0_0_0_0_1_0.html",
                "linovelib-list-/topfull/weekvote/1.html"
            ),
            provider.exploreExpandedPageDataSourceMap.keys
        )
        rows.flatMap { it.bookList }.forEach { book ->
            assertEquals(book.title, metadata.fallbackBookInformation(book.id)?.title)
            assertEquals(LinovelibUrls.cover(LinovelibUrls.HOST, book.id), book.coverUri.toString())
        }
        val displayedIds = rows.flatMap { row -> row.bookList.map { it.id } }.toSet()
        LinovelibHtmlParser().parseExploreRows(html).flatMap { it.books }
            .filterNot { it.id in displayedIds }
            .forEach { book -> assertNull(metadata.fallbackBookInformation(book.id)) }
    }
}
