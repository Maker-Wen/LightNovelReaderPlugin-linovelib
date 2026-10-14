package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val filteredUrl: (() -> String)? = null,
    private val metadata: LinovelibMetadataStore = LinovelibMetadataStore()
) : ExploreExpandedPageDataSource {
    override val title: String = displayTag
    private val sessionLock = Any()
    private var loadMoreRequests: Channel<Unit>? = null

    override fun loadMore() {
        val requests = synchronized(sessionLock) { loadMoreRequests }
        requests?.trySend(Unit)
    }

    override fun getResultFlow(): Flow<SearchResult> = flow {
        val requests = Channel<Unit>(Channel.CONFLATED)
        try {
            // The host cancels and recollects on filter changes. Snapshot before publishing
            // so a cancelled collection cannot replace a newer collection's request channel.
            val firstPageUrl = filteredUrl?.invoke() ?: targetUrl
            val context = currentCoroutineContext()
            synchronized(sessionLock) {
                context.ensureActive()
                loadMoreRequests = requests
            }
            val emittedBookIds = mutableSetOf<String>()
            var currentPage = 1
            var lastPage = 1
            do {
                context.ensureActive()
                val html = runCatching {
                    htmlLoader(LinovelibUrls.listPage(firstPageUrl, currentPage)
                        ?: error("Unsupported Linovelib pagination URL: $firstPageUrl"))
                }.onFailure(Throwable::rethrowIfCancellation).getOrElse {
                    emit(SearchResult.Error("书单加载失败，请稍后重试"))
                    emit(SearchResult.End())
                    return@flow
                }
                val page = parser.parseListPage(html)
                context.ensureActive()
                if (page == null) {
                    emit(SearchResult.Error("未能识别书单页面，请稍后重试"))
                    emit(SearchResult.End())
                    return@flow
                }
                if (currentPage == 1) {
                    lastPage = if (LinovelibUrls.listPage(firstPageUrl, 2) == null) 1 else page.lastPage
                }
                metadata.rememberBooks(page.books)
                val books = page.books.filter { emittedBookIds.add(it.id) }
                books.forEach { emit(SearchResult.SingleBook(it.id)) }
                if (currentPage == 1 && books.isEmpty()) break
                if (currentPage >= lastPage) break
                if (books.isNotEmpty()) requests.receive()
                currentPage++
            } while (true)
            if (emittedBookIds.isEmpty()) emit(SearchResult.Empty())
            emit(SearchResult.End())
        } finally {
            synchronized(sessionLock) {
                if (loadMoreRequests === requests) loadMoreRequests = null
            }
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
