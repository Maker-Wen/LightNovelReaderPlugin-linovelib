package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal object LinovelibRelatedSearch {
    private const val AUTHOR_PREFIX = "\u4f5c\u8005\uff1a"

    fun authorDisplayTag(author: String): String = "$AUTHOR_PREFIX$author"

    fun displayTags(author: String, tags: List<String>): List<String> = buildList {
        if (author.isNotBlank()) add(authorDisplayTag(author))
        addAll(tags.filter(String::isNotBlank))
    }.distinct()

    fun keyword(displayTag: String): String = displayTag.removePrefix(AUTHOR_PREFIX).trim()
}

internal class LinovelibLinkedExpandedPageDataSource(
    private val displayTag: String,
    private val targetUrl: String,
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    override val filters: List<Filter<*>> = emptyList(),
    private val filteredUrl: (() -> String)? = null
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag
    @Volatile
    private var loadMoreRequests: Channel<Unit>? = null

    override fun loadMore() {
        loadMoreRequests?.trySend(Unit)
    }

    override fun getResultFlow(): Flow<SearchResult> = flow {
        val requests = Channel<Unit>(Channel.CONFLATED)
        loadMoreRequests = requests
        // The host cancels and recollects on filter changes. Keep each collection's pages consistent.
        val firstPageUrl = filteredUrl?.invoke() ?: targetUrl
        val emittedBookIds = mutableSetOf<String>()
        var currentPage = 1
        var lastPage = 1
        try {
            do {
                val html = runCatching {
                    htmlLoader(LinovelibUrls.listPage(firstPageUrl, currentPage)
                        ?: error("Unsupported Linovelib pagination URL: $firstPageUrl"))
                }.onFailure(Throwable::rethrowIfCancellation).getOrElse {
                    emit(SearchResult.Error("书单加载失败，请稍后重试"))
                    emit(SearchResult.End())
                    return@flow
                }
                if (currentPage == 1) {
                    lastPage = if (LinovelibUrls.listPage(firstPageUrl, 2) == null) 1 else parser.parseLastPage(html)
                }
                val parsedBooks = parser.parseListRow(displayTag, html).books
                if (parsedBooks.isEmpty() && !LinovelibSearchResponse(firstPageUrl, html).hasEmptyResultList()) {
                    emit(SearchResult.Error("未能识别书单页面，请稍后重试"))
                    emit(SearchResult.End())
                    return@flow
                }
                val books = parsedBooks.filter { emittedBookIds.add(it.id) }
                books.forEach { emit(SearchResult.SingleBook(it.id)) }
                if (currentPage == 1 && books.isEmpty()) break
                if (currentPage >= lastPage) break
                if (books.isNotEmpty()) requests.receive()
                currentPage++
            } while (true)
            if (emittedBookIds.isEmpty()) emit(SearchResult.Empty())
            emit(SearchResult.End())
        } finally {
            if (loadMoreRequests === requests) loadMoreRequests = null
            requests.close()
        }
    }

}

internal class LinovelibRelatedExpandedPageDataSource(
    private val searchBookIds: (String) -> Flow<SearchResult>,
    private val displayTag: String
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag
    override val filters: List<Filter<*>> = emptyList()

    override fun loadMore() = Unit

    override fun getResultFlow(): Flow<SearchResult> = searchBookIds(LinovelibRelatedSearch.keyword(displayTag))
}

internal object LinovelibRelatedNavigation {
    private val expandedRouteClassNames = listOf(
        "io.nightfish.lightnovelreader.api.Route\$Main\$Explore\$Expanded",
        "indi.dmzz_yyhyy.lightnovelreader.ui.navigation.Route\$Main\$Explore\$Expanded"
    )

    fun createExpandedRoute(classLoader: ClassLoader?, pageId: String): Any {
        val loader = classLoader ?: ClassLoader.getSystemClassLoader()
        expandedRouteClassNames.forEach { className ->
            val route = runCatching {
                Class.forName(className, true, loader)
                    .getConstructor(String::class.java)
                    .newInstance(pageId)
            }.getOrNull()
            if (route != null) return route
        }
        error("No compatible LightNovelReader expanded-page route was found")
    }
}
