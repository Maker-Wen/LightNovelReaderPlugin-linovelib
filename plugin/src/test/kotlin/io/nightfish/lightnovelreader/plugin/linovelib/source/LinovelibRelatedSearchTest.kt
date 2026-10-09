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

class LinovelibRelatedSearchTest {
    @Test
    fun `related fallback returns distinct ids without fetching details`() = runBlocking {
        val keywords = mutableListOf<String>()
        val details = mutableListOf<String>()
        val parser = LinovelibHtmlParser()
        val search = LinovelibSearchProvider(
            htmlLoader = { url -> details += url; error("Related pages load their own details") },
            searchHtmlLoader = { keyword ->
                keywords += keyword
                LinovelibSearchResponse("${LinovelibUrls.HOST}/search.html", """
                    <ol class="book-ol">
                      <li><a class="book-layout" href="/novel/100.html"><span class="book-title">One</span></a></li>
                      <li><a class="book-layout" href="/novel/200.html"><span class="book-title">Two</span></a></li>
                      <li><a class="book-layout" href="/novel/100.html"><span class="book-title">One</span></a></li>
                    </ol>
                """)
            },
            parser = parser, diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        val explore = LinovelibExplorePageProvider({ error("No known list link") }, parser, search::searchBookIds)
        val page = explore.exploreExpandedPageDataSourceMap.getValue(explore.registerRelatedPage("作者：Author"))

        val results = page.getResultFlow().toList()

        assertEquals(listOf("Author"), keywords)
        assertEquals(listOf("100", "200"), results.filterIsInstance<SearchResult.SingleBook>().map { it.bookId })
        assertEquals(3, results.size)
        assertTrue(results.last() is SearchResult.End)
        assertTrue(details.isEmpty())
    }

    @Test
    fun `related id search handles direct and redirected books without detail requests`() = runBlocking {
        for (keyword in listOf("3247", "${LinovelibUrls.HOST}/novel/3247.html", "SAO")) {
            val keywords = mutableListOf<String>()
            val details = mutableListOf<String>()
            val search = LinovelibSearchProvider(
                htmlLoader = { url -> details += url; error("Unexpected detail request") },
                searchHtmlLoader = { query ->
                    keywords += query
                    LinovelibSearchResponse("${LinovelibUrls.HOST}/novel/3247.html", "<h1 class=book-title>SAO</h1>")
                },
                parser = LinovelibHtmlParser(), diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
            )

            val results = search.searchBookIds(keyword).toList()

            assertEquals("3247", (results.first() as SearchResult.SingleBook).bookId)
            assertEquals(2, results.size)
            assertTrue(results.last() is SearchResult.End)
            assertEquals(if (keyword == "SAO") listOf(keyword) else emptyList<String>(), keywords)
            assertTrue(details.isEmpty())
        }
    }

    @Test
    fun `related id search distinguishes empty results and failures and propagates cancellation`() = runBlocking {
        for (html in listOf("<ol class=book-ol></ol>", "<h1>Verification required</h1>", null)) {
            val search = LinovelibSearchProvider(
                htmlLoader = { error("Unexpected detail request") },
                searchHtmlLoader = {
                    LinovelibSearchResponse("${LinovelibUrls.HOST}/search.html", html ?: error("Network failure"))
                },
                parser = LinovelibHtmlParser(), diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
            )
            val results = search.searchBookIds("Author").toList()
            assertEquals(2, results.size)
            if (html?.startsWith("<ol") == true) assertTrue(results.first() is SearchResult.Empty)
            else assertTrue(results.first() is SearchResult.Error)
            assertTrue(results.last() is SearchResult.End)
        }
        val cancelled = kotlin.coroutines.cancellation.CancellationException("Search cancelled")
        val search = LinovelibSearchProvider(
            htmlLoader = { error("Unexpected detail request") }, searchHtmlLoader = { throw cancelled },
            parser = LinovelibHtmlParser(), diagnostics = LinovelibDiagnostics { _, _, _, _ -> }
        )
        try {
            search.searchBookIds("Author").toList()
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            org.junit.Assert.assertSame(cancelled, error)
        }
    }

    @Test
    fun `unrecognized linked pages emit errors on the first or a later page`() = runBlocking {
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
                parser = LinovelibHtmlParser()
            )
            val results = withTimeout(1_000) {
                val result = async { page.getResultFlow().toList() }
                while (requested.isEmpty()) yield()
                if (failedPage == 2) page.loadMore()
                result.await()
            }
            assertEquals(failedPage, requested.size)
            assertEquals(failedPage - 1, results.count { it is SearchResult.SingleBook })
            assertTrue(results.none { it is SearchResult.Empty })
            assertTrue(results[results.lastIndex - 1] is SearchResult.Error)
            assertTrue(results.last() is SearchResult.End)
        }
    }

    @Test
    fun `wenku filters retain conditions when paging and reset when recollected`() = runBlocking {
        val host = LinovelibUrls.HOST
        val filterSet = LinovelibWenkuFilters(host)
        val choices = filterSet.filters.map { it as SingleChoiceFilter }
        choices.zip(listOf("韩国轻小说", "恋爱", "收藏数", "未动画化", "50-100万", "接近尾声"))
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
            filteredUrl = { filterSet.url() }
        )
        val first = async { page.getResultFlow().toList() }
        while (requested.size < 1) yield()
        page.loadMore()
        withTimeout(1_000) { first.await() }
        choices[5].value = "已经完本"
        val refreshed = async { page.getResultFlow().toList() }
        while (requested.size < 3) yield()
        page.loadMore()
        withTimeout(1_000) { refreshed.await() }
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
    fun `adds clickable author chip without duplicating tags`() {
        assertEquals(
            listOf("\u4f5c\u8005\uff1a\u5ddd\u539f\u792b", "\u5947\u5e7b", "\u6821\u5712"),
            LinovelibRelatedSearch.displayTags(
                "\u5ddd\u539f\u792b",
                listOf("\u5947\u5e7b", "\u6821\u5712", "\u5947\u5e7b")
            )
        )
        assertEquals("\u5ddd\u539f\u792b", LinovelibRelatedSearch.keyword("\u4f5c\u8005\uff1a\u5ddd\u539f\u792b"))
        assertEquals("\u5947\u5e7b", LinovelibRelatedSearch.keyword("\u5947\u5e7b"))
    }

    @Test
    fun `expanded related page searches current author or tag`() = runBlocking {
        val requested = mutableListOf<String>()
        val related = LinovelibRelatedExpandedPageDataSource(
            { keyword ->
                requested += keyword
                flowOf(SearchResult.SingleBook("3247"), SearchResult.End())
            },
            "\u4f5c\u8005\uff1a\u5ddd\u539f\u792b"
        )
        val results = related.getResultFlow().toList()

        assertEquals("\u4f5c\u8005\uff1a\u5ddd\u539f\u792b", related.title)
        assertEquals(listOf("\u5ddd\u539f\u792b"), requested)
        assertEquals(2, results.size)
    }

    @Test
    fun `resolves expanded route class from current host api`() {
        val route = LinovelibRelatedNavigation.createExpandedRoute(javaClass.classLoader, "related-id")

        assertEquals("related-id", route.javaClass.getMethod("getExpandedPageDataSourceId").invoke(route))
    }

    @Test
    fun `linked related page loads books from website tag url`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "\u79d1\u5e7b",
            targetUrl = "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            htmlLoader = { url ->
                requestedUrls += url
                """
                    <ol class="book-ol">
                      <li class="book-li"><a class="book-layout" href="/novel/3247.html"><span class="book-title">SAO</span></a></li>
                      <li class="book-li"><a class="book-layout" href="/novel/3768.html"><span class="book-title">Other</span></a></li>
                    </ol>
                """.trimIndent()
            },
            parser = LinovelibHtmlParser()
        )

        val results = page.getResultFlow().toList()

        assertEquals(listOf("https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html"), requestedUrls)
        assertEquals("\u79d1\u5e7b", page.title)
        assertEquals(listOf("3247", "3768"), results.filterIsInstance<SearchResult.SingleBook>().map { it.bookId })
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun `linked related page loads and deduplicates the next page`() = runBlocking {
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
            parser = LinovelibHtmlParser()
        )

        val results = async { page.getResultFlow().toList() }
        while (requestedUrls.isEmpty()) yield()
        page.loadMore()
        val collected = withTimeout(1_000) { results.await() }

        assertEquals(
            listOf(
                "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
                "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_2_0.html"
            ),
            requestedUrls
        )
        assertEquals(
            listOf("3247", "3768", "4000"),
            collected.filterIsInstance<SearchResult.SingleBook>().map { it.bookId }
        )
    }

    @Test
    fun `one load request skips a duplicate-only middle page`() = runBlocking {
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
            parser = LinovelibHtmlParser()
        )

        val results = async { page.getResultFlow().toList() }
        while (requestedUrls.isEmpty()) yield()
        page.loadMore()
        val collected = withTimeout(1_000) { results.await() }

        assertEquals(3, requestedUrls.size)
        assertEquals(
            listOf("3247", "4000"),
            collected.filterIsInstance<SearchResult.SingleBook>().map { it.bookId }
        )
    }

    @Test
    fun `empty first page does not scan later pages without results`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val page = LinovelibLinkedExpandedPageDataSource(
            displayTag = "\u79d1\u5e7b",
            targetUrl = "https://www.bilinovel.net/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html",
            htmlLoader = { url ->
                requestedUrls += url
                "<div id=\"pagelink\">${"\u7b2c"}1/20${"\u9875"}</div><ol class=\"book-ol\"></ol>"
            },
            parser = LinovelibHtmlParser()
        )

        val results = withTimeout(1_000) { page.getResultFlow().toList() }

        assertEquals(1, requestedUrls.size)
        assertEquals(1, results.count { it is SearchResult.Empty })
        assertTrue(results.last() is SearchResult.End)
    }

    @Test
    fun `explore provider uses linked page when parser knows tag url`() {
        val parser = LinovelibHtmlParser()
        parser.parseBookInformation(
            "3247",
            """
                <h1 class="book-title">SAO</h1>
                <span class="tag-small-group">
                  <em class="tag-small red"><a href="/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html">${"\u79d1\u5e7b"}</a></em>
                </span>
            """.trimIndent()
        )
        val provider = LinovelibExplorePageProvider({ "" }, parser, { flowOf(SearchResult.End()) })

        assertEquals(listOf("推荐", "文库", "排行", "完结"),
            provider.explorePageIdList.map { provider.exploreTapPageDataSourceMap.getValue(it).title })
        assertEquals(6, provider.exploreExpandedPageDataSourceMap
            .getValue(LinovelibExplorePageProvider.WENKU_PAGE_ID).filters.size)

        val pageId = provider.registerRelatedPage("\u79d1\u5e7b")

        assertTrue(provider.exploreExpandedPageDataSourceMap[pageId] is LinovelibLinkedExpandedPageDataSource)
    }

    @Test
    fun `known tag url replaces fallback page registered before book details load`() {
        val parser = LinovelibHtmlParser()
        val provider = LinovelibExplorePageProvider({ "" }, parser, { flowOf(SearchResult.End()) })
        val pageId = provider.registerRelatedPage("\u79d1\u5e7b")
        assertTrue(provider.exploreExpandedPageDataSourceMap[pageId] is LinovelibRelatedExpandedPageDataSource)

        parser.parseBookInformation(
            "3247",
            """
                <h1 class="book-title">SAO</h1>
                <span class="tag-small-group">
                  <em class="tag-small red"><a href="/wenku/lastupdate_56_0_0_0_0_0_0_1_0.html">${"\u79d1\u5e7b"}</a></em>
                </span>
            """.trimIndent()
        )
        provider.registerRelatedPage("\u79d1\u5e7b")

        assertTrue(provider.exploreExpandedPageDataSourceMap[pageId] is LinovelibLinkedExpandedPageDataSource)
    }
}
