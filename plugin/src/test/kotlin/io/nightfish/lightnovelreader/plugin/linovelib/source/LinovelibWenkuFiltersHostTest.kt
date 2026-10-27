package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibWenkuFiltersHostTest {
    @Test
    fun publisherDefaultsToUnrestrictedAndUsesTheSiteSortIds() {
        val filters = LinovelibWenkuFilters(HOST)
        val publisher = filters.filters[1] as SingleChoiceFilter
        val publishers = listOf(
            "不限", "电击文库", "富士见文库", "角川文库", "MF文库J", "Fami通文库", "GA文库",
            "HJ文库", "一迅社", "集英社", "小学馆", "讲谈社", "少女文库", "其他文库"
        )

        assertEquals("不限", publisher.value)
        assertEquals(publishers, publisher.getAllChoices())
        assertEquals("$HOST/wenku/lastupdate_0_0_0_0_0_0_0_1_0.html", filters.url())
        assertEquals(LinovelibUrls.wenku(HOST, "lastupdate", 1), filters.url())
        publishers.forEachIndexed { sortId, title ->
            publisher.value = title
            assertEquals("$HOST/wenku/lastupdate_0_0_0_0_${sortId}_0_0_1_0.html", filters.url())
        }
    }

    @Test
    fun publisherCombinesWithTheExistingConditionsWithoutChangingTheRegion() {
        val filters = LinovelibWenkuFilters(HOST)
        filters.filters.map { it as SingleChoiceFilter }
            .zip(listOf("韩国轻小说", "GA文库", "恋爱", "收藏数", "未动画化", "50-100万", "接近尾声"))
            .forEach { (filter, value) -> filter.value = value }

        assertEquals("$HOST/wenku/goodnum_64_4_2_5_6_0_3_1_0.html", filters.url())
        (filters.filters[1] as SingleChoiceFilter).value = "不限"
        assertEquals("$HOST/wenku/goodnum_64_4_2_5_0_0_3_1_0.html", filters.url())
    }

    @Test
    fun publisherChangesRestartTheListAndRemainAppliedToLaterPages() = runBlocking {
        val filters = LinovelibWenkuFilters(HOST)
        val choices = filters.filters.map { it as SingleChoiceFilter }
        choices.zip(listOf("华文轻小说", "电击文库", "奇幻", "月推荐", "已动画化", "100-200万", "已经完本"))
            .forEach { (filter, value) -> filter.value = value }
        val requested = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "文库",
            targetUrl = LinovelibUrls.wenku(HOST, "lastupdate", 1),
            htmlLoader = { url ->
                requested += url
                val id = if (url.endsWith("_2_0.html")) "200" else "100"
                """<div id="pagelink">第1/2页</div>
                    <a class="book-layout" href="/novel/$id.html"><span class="book-title">Book $id</span></a>"""
            },
            parser = LinovelibHtmlParser(),
            filters = filters.filters,
            filteredUrl = { filters.url() },
            metadata = LinovelibMetadataStore()
        )

        withTimeout(5_000) {
            val first = async { page.getResultFlow().toList() }
            while (requested.size < 1) yield()
            page.loadMore()
            first.await()

            choices[1].value = "讲谈社"
            val refreshed = async { page.getResultFlow().toList() }
            while (requested.size < 3) yield()
            page.loadMore()
            refreshed.await()
        }

        assertEquals(listOf(
            "$HOST/wenku/monthvote_15_5_1_2_1_0_4_1_0.html",
            "$HOST/wenku/monthvote_15_5_1_2_1_0_4_2_0.html",
            "$HOST/wenku/monthvote_15_5_1_2_11_0_4_1_0.html",
            "$HOST/wenku/monthvote_15_5_1_2_11_0_4_2_0.html"
        ), requested)
    }

    companion object {
        private const val HOST = LinovelibUrls.HOST
    }
}
