package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal object LinovelibRelatedSearch {
    private const val AUTHOR_PREFIX = "\u4f5c\u8005\uff1a"

    fun authorDisplayTag(author: String): String = "$AUTHOR_PREFIX$author"

    fun displayTags(author: String, tags: List<String>, publishingHouse: String = ""): List<String> = buildList {
        if (publishingHouse.isNotBlank()) add(publishingHouse)
        if (author.isNotBlank()) add(authorDisplayTag(author))
        addAll(tags.filter(String::isNotBlank))
    }.distinct()

    fun keyword(displayTag: String): String = displayTag.removePrefix(AUTHOR_PREFIX).trim()
}

internal class LinovelibLinkedExpandedPageDataSource(
    private val displayTag: String,
    private val targetUrl: String,
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag
    override val filters: List<Filter<*>> = emptyList()
    @Volatile
    private var loadMoreRequests: Channel<Unit>? = null

    override fun loadMore() {
        loadMoreRequests?.trySend(Unit)
    }

    override fun getResultFlow(): Flow<SearchResult> = flow {
        val requests = Channel<Unit>(Channel.CONFLATED)
        loadMoreRequests = requests
        val emittedBookIds = mutableSetOf<String>()
        var currentPage = 1
        var lastPage = 1
        try {
            do {
                val html = runCatching {
                    htmlLoader(pageUrl(currentPage) ?: error("Unsupported Linovelib pagination URL: $targetUrl"))
                }.onFailure(Throwable::rethrowIfCancellation).getOrElse {
                    emit(SearchResult.Error("Failed to request Linovelib related books"))
                    emit(SearchResult.End())
                    return@flow
                }
                if (currentPage == 1) {
                    lastPage = if (pageUrl(2) == null) 1 else parser.parseLastPage(html)
                }
                val books = parser.parseListRow(displayTag, html).books
                    .filter { emittedBookIds.add(it.id) }
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

    private fun pageUrl(page: Int): String? {
        if (page == 1) return targetUrl
        PAGE_SEGMENT_REGEX.find(targetUrl)?.let {
            return PAGE_SEGMENT_REGEX.replace(targetUrl) { match ->
                "_${page}_0${match.groupValues[1]}"
            }
        }
        if ("/wenku/" !in targetUrl) return null
        return WENKU_PATH_PAGE_REGEX.takeIf { it.containsMatchIn(targetUrl) }
            ?.replace(targetUrl) { match -> "/$page${match.groupValues[1]}" }
    }

    private companion object {
        val PAGE_SEGMENT_REGEX = Regex("_\\d+_0(\\.html(?:\\?.*)?)$")
        val WENKU_PATH_PAGE_REGEX = Regex("/\\d+(\\.html(?:\\?.*)?)$")
    }
}

internal class LinovelibRelatedExpandedPageDataSource(
    private val searchProvider: SearchProvider,
    private val displayTag: String
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag
    override val filters: List<Filter<*>> = emptyList()

    override fun loadMore() = Unit

    override fun getResultFlow(): Flow<SearchResult> {
        val searchType = searchProvider.searchTypes.first()
        return searchProvider.search(searchType, LinovelibRelatedSearch.keyword(displayTag))
    }
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
