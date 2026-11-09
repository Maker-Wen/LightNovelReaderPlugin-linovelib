package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibRelatedSearchHostTest {
    @Test
    fun emptyMiddlePagesContinueUsingTheFirstPagesLastPage() = runBlocking {
        val requested = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            "Books", LinovelibUrls.wenku(LinovelibUrls.HOST, "lastupdate", 1),
            htmlLoader = { url ->
                requested += url
                when (requested.size) {
                    1 -> linkedListHtml("1", 3)
                    2 -> linkedListHtml(null, 1)
                    else -> linkedListHtml("2", 1)
                }
            }, parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val results = withTimeout(1_000) {
            val collection = async { page.getResultFlow().toList() }
            while (requested.isEmpty()) yield()
            page.loadMore()
            collection.await()
        }

        assertEquals((1..3).map { LinovelibUrls.wenku(LinovelibUrls.HOST, "lastupdate", it) }, requested)
        assertEquals(listOf("1", "2"), results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
        assertTrue(results.none { it is SearchResult.Empty || it is SearchResult.Error })
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun laterPageMetadataDoesNotExtendTheFirstPagesLastPage() = runBlocking {
        val requested = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            "Books", LinovelibUrls.wenku(LinovelibUrls.HOST, "lastupdate", 1),
            htmlLoader = { url ->
                requested += url
                linkedListHtml(requested.size.toString(), if (requested.size == 1) 2 else 9)
            }, parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val results = withTimeout(1_000) {
            val collection = async { page.getResultFlow().toList() }
            while (requested.isEmpty()) yield()
            page.loadMore()
            collection.await()
        }

        assertEquals((1..2).map { LinovelibUrls.wenku(LinovelibUrls.HOST, "lastupdate", it) }, requested)
        assertEquals(listOf("1", "2"), results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun nonPaginatedTargetsIgnoreThePagesPaginationHint() = runBlocking {
        val target = LinovelibUrls.HOST + "/tag/1.html"
        val requested = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            "Books", target,
            htmlLoader = { requested += it; linkedListHtml("1", 20) },
            parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val results = withTimeout(1_000) { page.getResultFlow().toList() }

        assertEquals(listOf(target), requested)
        assertEquals(listOf("1"), results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun nonPositivePaginationHintsEndAfterTheFirstPage() = runBlocking {
        val target = LinovelibUrls.wenku(LinovelibUrls.HOST, "lastupdate", 1)
        for (lastPage in listOf(-1, 0)) {
            val requested = mutableListOf<String>()
            val page = LinovelibLinkedExpandedPageDataSource(
                "Books", target,
                htmlLoader = { requested += it; linkedListHtml("1", lastPage) },
                parser = LinovelibHtmlParser(),
                metadata = LinovelibMetadataStore()
            )

            val results = withTimeout(1_000) { page.getResultFlow().toList() }

            assertEquals(listOf(target), requested)
            assertEquals(listOf("1"), results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id })
            assertTrue(results.none { it is SearchResult.Error })
            assertTrue(results.last() is SearchResult.End)
        }
    }

    private fun linkedListHtml(bookId: String?, lastPage: Int): String {
        val books = if (bookId == null) "" else """
            <li class="book-li"><a class="book-layout" href="/novel/$bookId.html">
            <span class="book-title">Book $bookId</span></a></li>
        """
        return """<div id="pagelink"><a class="last">$lastPage</a></div><ol class="book-ol">$books</ol>"""
    }

    @Test
    fun relatedFallbackReturnsListSummariesWithoutFetchingDetails() = runBlocking {
        val keywords = mutableListOf<String>()
        val details = mutableListOf<String>()
        val parser = LinovelibHtmlParser()
        val search = testSearchProvider(
            htmlLoader = { url -> details += url; error("Unexpected detail request") },
            searchHtmlLoader = { keyword ->
                keywords += keyword
                val html = if (keyword.endsWith("/Author_2.html")) searchPage(listOf("100", "200"))
                else searchPage(listOf("100", "100"), "/search/Author_2.html")
                LinovelibHtmlResponse(keyword, html)
            },
            parser = parser, diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        val explore = LinovelibExplorePageProvider({ error("No known list link") }, parser, search::searchRelated, metadata = LinovelibMetadataStore())
        val pageId = explore.registerRelatedPage("作者：\u3000 Author")
        val page = explore.exploreExpandedPageDataSourceMap.getValue(pageId)

        val results = page.getResultFlow().toList()

        assertEquals(listOf(
            LinovelibUrls.search(LinovelibUrls.HOST, "Author"),
            "${LinovelibUrls.HOST}/search/Author_2.html"
        ), keywords)
        val summaries = results.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation }
        assertEquals(listOf("100", "200"), summaries.map { it.id })
        assertEquals(listOf("Book 100", "Book 200"), summaries.map { it.title })
        assertEquals(3, results.size)
        assertTrue(results.last() is SearchResult.End)
        assertTrue(details.isEmpty())
    }

    @Test
    fun relatedSearchTrimsAuthorQueriesAndReusesRedirectedDetailResponses() = runBlocking {
        for (keyword in listOf("3247", "${LinovelibUrls.HOST}/novel/3247.html", "SAO")) {
            val keywords = mutableListOf<String>()
            val details = mutableListOf<String>()
            val search = testSearchProvider(
                htmlLoader = { url -> details += url; error("Unexpected detail request") },
                searchHtmlLoader = { query ->
                    keywords += query
                    LinovelibHtmlResponse("${LinovelibUrls.HOST}/novel/3247.html", "<h1 class=book-title>SAO</h1>")
                },
                parser = LinovelibHtmlParser(), diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
            )

            val related = LinovelibRelatedExpandedPageDataSource(
                search::searchRelated, LinovelibRelatedSearch.authorDisplayTag(" \u3000$keyword \u2003")
            )
            val results = related.getResultFlow().toList()

            val summary = (results.first() as SearchResult.MultipleBook).bookInformation
            assertEquals("3247", summary.id)
            assertEquals("SAO", summary.title)
            assertEquals(2, results.size)
            assertTrue(results.last() is SearchResult.End)
            assertEquals(listOf(LinovelibUrls.search(LinovelibUrls.HOST, keyword)), keywords)
            assertTrue(details.isEmpty())
        }
    }

    @Test
    fun relatedSearchDistinguishesEmptyResultsAndFailuresAndPropagatesCancellation() = runBlocking {
        for (html in listOf("<ol class=book-ol></ol>", "<h1>Verification required</h1>", null)) {
            val search = testSearchProvider(
                htmlLoader = { error("Unexpected detail request") },
                searchHtmlLoader = {
                    LinovelibHtmlResponse("${LinovelibUrls.HOST}/search.html", html ?: error("Network failure"))
                },
                parser = LinovelibHtmlParser(), diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
            )
            val results = search.searchRelated("Author").toList()
            assertEquals(2, results.size)
            if (html?.startsWith("<ol") == true) assertTrue(results.first() is SearchResult.Empty)
            else assertTrue(results.first() is SearchResult.Error)
            assertTrue(results.last() is SearchResult.End)
        }
        val cancelled = kotlin.coroutines.cancellation.CancellationException("Search cancelled")
        val search = testSearchProvider(
            htmlLoader = { error("Unexpected detail request") }, searchHtmlLoader = { throw cancelled },
            parser = LinovelibHtmlParser(), diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        try {
            search.searchRelated("Author").toList()
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            org.junit.Assert.assertSame(cancelled, error)
        }
    }

    @Test
    fun unrecognizedLinkedPagesEmitErrorsOnTheFirstOrALaterPage() = runBlocking {
        for (failedPage in listOf(1, 2)) {
            val requested = mutableListOf<String>()
            val page = LinovelibLinkedExpandedPageDataSource(
                "文库", LinovelibUrls.wenku(LinovelibUrls.HOST, "lastupdate", 1),
                htmlLoader = { url ->
                    requested += url
                    if (requested.size == failedPage) {
                        "<html><body><h1>请完成验证</h1></body></html>"
                    } else {
                        """<div id="pagelink">第1/2页</div>
                            <ol class="book-ol"><li class="book-li"><a class="book-layout" href="/novel/100.html">
                            <span class="book-title">Book</span></a></li></ol>"""
                    }
                },
                parser = LinovelibHtmlParser(),
                metadata = LinovelibMetadataStore()
            )
            val results = withTimeout(1_000) {
                val result = async { page.getResultFlow().toList() }
                while (requested.isEmpty()) yield()
                if (failedPage == 2) page.loadMore()
                result.await()
            }
            assertEquals(failedPage, requested.size)
            assertEquals(failedPage - 1, results.count { it is SearchResult.MultipleBook })
            assertTrue(results.none { it is SearchResult.Empty })
            assertTrue(results[results.lastIndex - 1] is SearchResult.Error)
            assertTrue(results.last() is SearchResult.End)
        }
    }

    @Test
    fun wenkuFiltersRetainConditionsWhenPagingAndResetWhenRecollected() = runBlocking {
        val host = LinovelibUrls.HOST
        val filterSet = LinovelibWenkuFilters(host)
        val choices = filterSet.filters.map { it as SingleChoiceFilter }
        choices.zip(listOf("韩国轻小说", "不限", "恋爱", "收藏数", "未动画化", "50-100万", "接近尾声"))
            .forEach { (filter, value) -> filter.value = value }
        // Verified by applying these controls on the public wenku page.
        val expected = "$host/wenku/goodnum_64_4_2_5_0_0_3_1_0.html"
        assertEquals(expected, filterSet.url())
        val requested = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            "文库", LinovelibUrls.wenku(host, "lastupdate", 1),
            htmlLoader = { url ->
                requested += url
                val id = if ("_2_0.html" in url) "200" else "100"
                """<div id="pagelink">第1/2页</div>
                    <a class="book-layout" href="/novel/$id.html"><span class="book-title">Book $id</span></a>"""
            },
            parser = LinovelibHtmlParser(host), filters = filterSet.filters,
            filteredUrl = { filterSet.url() },
            metadata = LinovelibMetadataStore()
        )
        withTimeout(1_000) {
            val first = async { page.getResultFlow().toList() }
            while (requested.size < 1) yield()
            page.loadMore()
            first.await()
        }
        choices[6].value = "已经完本"
        withTimeout(1_000) {
            val refreshed = async { page.getResultFlow().toList() }
            while (requested.size < 3) yield()
            page.loadMore()
            refreshed.await()
        }
        assertEquals(listOf(
            expected,
            "$host/wenku/goodnum_64_4_2_5_0_0_3_2_0.html",
            "$host/wenku/goodnum_64_5_2_5_0_0_3_1_0.html",
            "$host/wenku/goodnum_64_5_2_5_0_0_3_2_0.html"
        ), requested)
        assertEquals("https://www.bilinovel.net/top/monthvisit/2.html",
            LinovelibUrls.listPage("https://www.bilinovel.net/top/monthvisit/1.html", 2))
        assertEquals("https://www.bilinovel.net/topfull/postdate/2.html",
            LinovelibUrls.listPage("https://www.bilinovel.net/topfull/postdate/1.html", 2))
    }

    @Test
    fun mapsAuthorDisplayTagAndDistinctTagsToSearchKeywords() {
        val book = LinovelibHtmlParser().parseBookInformation("3247", """
            <h1 class="book-title">SAO</h1>
            <span class="authorname"><a href="/author/kawahara.html">川原礫</a></span>
            <span class="tag-small-group">
                <em class="tag-small"> 奇幻 </em><em class="tag-small"> </em>
                <em class="tag-small">校園</em><em class="tag-small">奇幻</em>
            </span>
        """)
        assertEquals(
            listOf("\u4f5c\u8005\uff1a\u5ddd\u539f\u792b", "\u5947\u5e7b", "\u6821\u5712"),
            LinovelibRelatedSearch.displayTags(book.author, book.tags)
        )
        assertEquals("\u5ddd\u539f\u792b", LinovelibRelatedSearch.keyword("\u4f5c\u8005\uff1a\u5ddd\u539f\u792b"))
        assertEquals("\u5947\u5e7b", LinovelibRelatedSearch.keyword("\u5947\u5e7b"))
    }

    @Test
    fun expandedRelatedPageSearchesCurrentAuthorOrTag() = runBlocking {
        val requested = mutableListOf<String>()
        val related = LinovelibRelatedExpandedPageDataSource(
            { keyword ->
                requested += keyword
                val summary = ParsedExploreBook("3247", "SAO", "川原礫", "").toParsedBookInformation()
                flowOf(SearchResult.MultipleBook(summary.toMutableBookInformation()), SearchResult.End())
            },
            "\u4f5c\u8005\uff1a\u5ddd\u539f\u792b"
        )
        val results = related.getResultFlow().toList()

        assertEquals("\u4f5c\u8005\uff1a\u5ddd\u539f\u792b", related.title)
        assertEquals(listOf("\u5ddd\u539f\u792b"), requested)
        assertEquals(2, results.size)
    }

    @Test
    fun linkedRelatedPageReturnsWebsiteListFieldsWithoutFetchingDetails() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val listHtml = checkNotNull(javaClass.getResourceAsStream("/html/list-title-3343.html"))
            .bufferedReader().use { it.readText() }
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "\u79d1\u5e7b",
            targetUrl = "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            htmlLoader = { url ->
                requestedUrls += url
                listHtml
            },
            parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val results = page.getResultFlow().toList()

        assertEquals(listOf("https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html"), requestedUrls)
        assertEquals("\u79d1\u5e7b", page.title)
        val book = results.filterIsInstance<SearchResult.MultipleBook>().single().bookInformation
        assertEquals("3343", book.id)
        assertEquals("妹妹是不能当女友的，可是……", book.title)
        assertEquals("镜游", book.author)
        assertEquals("${LinovelibUrls.HOST}/files/article/image/3/3343/3343s.jpg", book.coverUri.toString())
        assertTrue(book.description.startsWith("这是一段无论发生什么"))
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun linkedRelatedPageLoadsAndDeduplicatesTheNextPage() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "\u79d1\u5e7b",
            targetUrl = "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            htmlLoader = { url ->
                requestedUrls += url
                val ids = if ("_2_0.html" in url) listOf("3768", "4000") else listOf("3247", "3768")
                """
                    <div id="pagelink">${"\u7b2c"}1/2${"\u9875"}</div>
                    <ol class="book-ol">
                      ${ids.joinToString("\n") { id ->
                          "<li class=\"book-li\"><a class=\"book-layout\" href=\"/novel/$id.html\"><span class=\"book-title\">$id</span></a></li>"
                      }}
                    </ol>
                """.trimIndent()
            },
            parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val collected = withTimeout(1_000) {
            val results = async { page.getResultFlow().toList() }
            while (requestedUrls.isEmpty()) yield()
            page.loadMore()
            results.await()
        }

        assertEquals(
            listOf(
                "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
                "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_2_0.html"
            ),
            requestedUrls
        )
        assertEquals(
            listOf("3247", "3768", "4000"),
            collected.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id }
        )
    }

    @Test
    fun oneLoadRequestSkipsADuplicateOnlyMiddlePage() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "\u79d1\u5e7b",
            targetUrl = "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            htmlLoader = { url ->
                requestedUrls += url
                val id = if ("_3_0.html" in url) "4000" else "3247"
                """
                    <div id="pagelink">${"\u7b2c"}1/3${"\u9875"}</div>
                    <ol class="book-ol">
                      <li class="book-li"><a class="book-layout" href="/novel/$id.html"><span class="book-title">$id</span></a></li>
                    </ol>
                """.trimIndent()
            },
            parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val collected = withTimeout(1_000) {
            val results = async { page.getResultFlow().toList() }
            while (requestedUrls.isEmpty()) yield()
            page.loadMore()
            results.await()
        }

        assertEquals(3, requestedUrls.size)
        assertEquals(
            listOf("3247", "4000"),
            collected.filterIsInstance<SearchResult.MultipleBook>().map { it.bookInformation.id }
        )
    }

    @Test
    fun emptyFirstPageDoesNotScanLaterPagesWithoutResults() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "\u79d1\u5e7b",
            targetUrl = "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            htmlLoader = { url ->
                requestedUrls += url
                "<div id=\"pagelink\">${"\u7b2c"}1/20${"\u9875"}</div><ol class=\"book-ol\"></ol>"
            },
            parser = LinovelibHtmlParser(),
            metadata = LinovelibMetadataStore()
        )

        val results = withTimeout(1_000) { page.getResultFlow().toList() }

        assertEquals(1, requestedUrls.size)
        assertEquals(1, results.count { it is SearchResult.Empty })
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun exploreProviderUsesLinkedPageWhenMetadataKnowsTagUrl() {
        val parser = LinovelibHtmlParser()
        val metadata = LinovelibMetadataStore()
        parser.parseBookInformation(
            "3247",
            """
                <h1 class="book-title">SAO</h1>
                <span class="tag-small-group">
                  <em class="tag-small red"><a href="/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html">${"\u79d1\u5e7b"}</a></em>
                </span>
            """.trimIndent()
        ).also(metadata::rememberBook)
        val provider = LinovelibExplorePageProvider({ "" }, parser, { flowOf(SearchResult.End()) }, metadata = metadata)

        assertEquals(listOf("推荐", "排行", "文库"),
            provider.explorePageIdList.map { provider.exploreTapPageDataSourceMap.getValue(it).title })
        assertEquals(7, provider.exploreExpandedPageDataSourceMap
            .getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID).filters.size)

        val pageId = provider.registerRelatedPage("  \u79d1\u5e7b  ")

        assertEquals(pageId, provider.registerRelatedPage("\u79d1\u5e7b"))
        assertEquals("\u79d1\u5e7b", provider.exploreExpandedPageDataSourceMap.getValue(pageId).title)
        assertTrue(provider.exploreExpandedPageDataSourceMap[pageId] is LinovelibLinkedExpandedPageDataSource)
    }
}
