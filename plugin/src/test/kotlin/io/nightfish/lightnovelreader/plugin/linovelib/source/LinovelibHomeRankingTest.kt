package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibHomeRankingTest {
    private val html = javaClass.getResource("/html/home-modules-20261027.html")!!.readText()
    private val parser = LinovelibHtmlParser()

    @Test
    fun capturedHomepageKeepsEachRankingInItsOwnRowAndOriginalOrder() {
        val rows = parser.parseExploreRows(html)

        assertEquals(
            listOf("勇者新推", "新书抢鲜", "点击榜", "推荐榜", "新书榜", "鲜花榜"),
            rows.take(6).map { it.title }
        )
        // The captured page puts shared books in several rankings; each occurrence keeps its rank.
        assertEquals(
            listOf(
                listOf("5491", "5370", "3095", "2906", "2139", "2600", "3193", "5428", "2979", "2890"),
                listOf("2059", "5491", "3095", "4649", "5370", "5209", "4971", "4678", "4386", "3042"),
                listOf("5428", "5407", "5470", "5440", "5414", "5457", "5417", "5412", "5419", "5420"),
                listOf("73", "5480", "5491", "1855", "4459", "3285", "3095", "2624", "5440", "1410")
            ),
            rows.subList(2, 6).map { row -> row.books.map { it.id } }
        )
    }

    @Test
    fun capturedHomepageKeepsTheSharedMoreLinkOnlyOnTheDefaultRanking() {
        val rows = parser.parseExploreRows(html)

        assertEquals(
            mapOf(
                "新书抢鲜" to "${LinovelibUrls.HOST}/wenku/postdate_0_0_0_0_0_0_0_1_0.html",
                "点击榜" to "${LinovelibUrls.HOST}/top/monthvisit/1.html",
                "人气完本" to "${LinovelibUrls.HOST}/topfull/weekvote/1.html"
            ),
            rows.filter { it.expandedUrl != null }.associate { it.title to it.expandedUrl }
        )
    }
}
