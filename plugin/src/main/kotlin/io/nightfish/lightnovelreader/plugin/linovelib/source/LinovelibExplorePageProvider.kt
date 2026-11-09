package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.web.explore.AbstractDefaultExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class LinovelibExplorePageProvider(
    private val htmlLoader: suspend (String) -> String,
    private val parser: LinovelibHtmlParser,
    private val searchRelated: (String) -> Flow<SearchResult>,
    private val host: String = LinovelibUrls.HOST,
    private val metadata: LinovelibMetadataStore
) : AbstractDefaultExplorePageProvider() {
    private data class ExpandedPageDefinition(val title: String, val targetUrl: String?)

    private val definitionLock = Any()
    private val expandedPageDefinitions = linkedMapOf<String, ExpandedPageDefinition>()

    override val exploreExpandedPageDataSourceMap: MutableMap<String, ExploreExpandedPageDataSource>
        get() {
            val definitions = synchronized(definitionLock) { expandedPageDefinitions.toMap() }
            // ponytail: API 3 exposes a Map, so each read creates O(definitions) lightweight pages.
            // The host keeps one page per ViewModel; construction must never start I/O.
            return definitions.mapValuesTo(linkedMapOf()) { (id, definition) ->
                createExpandedPage(id, definition)
            }
        }

    init {
        val wenkuUrl = LinovelibUrls.wenku(host, "lastupdate", 1)
        registerExpandedPageDefinition(WENKU_PAGE_ID, ExpandedPageDefinition("文库", wenkuUrl))
        registerTapPage(LinovelibRowsExploreTapPage("推荐") {
            val rows = parser.parseExploreRows(htmlLoader(host))
            listOf("勇者新推", "新书抢鲜", "人气完本")
                .mapNotNull { title -> rows.firstOrNull { it.title == title } }
                .map { displayRow(it) }
        })
        registerTapPage(LinovelibRowsExploreTapPage("排行") {
            parser.parseRankingRows(htmlLoader(LinovelibUrls.top(host))).map { displayRow(it) }
        })
        registerTapPage(LinovelibRowsExploreTapPage("文库") {
            val books = parser.parseListPage(htmlLoader(wenkuUrl))?.books.orEmpty()
                .take(MAX_WENKU_PREVIEW_BOOKS)
            val row = ParsedExploreRow("全部轻小说", books)
            listOf(displayRow(row, WENKU_PAGE_ID))
        })
    }

    private fun displayRow(row: ParsedExploreRow, fixedExpandedPageId: String? = null): ExploreBooksRow {
        metadata.rememberBooks(row.books)
        val pageId = fixedExpandedPageId ?: row.expandedUrl?.let { url ->
            "linovelib-list-${url.removePrefix(host)}".also { id ->
                registerExpandedPageDefinition(id, ExpandedPageDefinition(row.title, url))
            }
        }
        return ExploreBooksRow(
            title = row.title,
            expandable = pageId != null,
            expandedPageDataSourceId = pageId,
            bookList = row.books.map { book ->
                ExploreDisplayBook(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    coverUri = Uri.parse(book.coverUrl)
                )
            }
        )
    }

    private fun registerExpandedPageDefinition(id: String, definition: ExpandedPageDefinition) {
        // Do not use the inherited registration method: it would write into a disposable snapshot.
        synchronized(definitionLock) {
            if (id !in expandedPageDefinitions) expandedPageDefinitions[id] = definition
        }
    }

    fun registerRelatedPage(tag: String): String {
        val displayTag = tag.trim()
        val baseId = "$RELATED_PAGE_ID-${Integer.toHexString(displayTag.hashCode())}"
        synchronized(definitionLock) {
            val targetUrl = metadata.relatedTarget(displayTag)
            var pageId = baseId
            var suffix = 2
            while (true) {
                val existing = expandedPageDefinitions[pageId]
                if (existing == null) {
                    expandedPageDefinitions[pageId] = ExpandedPageDefinition(displayTag, targetUrl)
                    return pageId
                }
                if (existing.title == displayTag) {
                    if (targetUrl != null && targetUrl != existing.targetUrl) {
                        expandedPageDefinitions[pageId] = existing.copy(targetUrl = targetUrl)
                    }
                    return pageId
                }
                pageId = "$baseId-${suffix++}"
            }
        }
    }

    private fun createExpandedPage(id: String, definition: ExpandedPageDefinition): ExploreExpandedPageDataSource {
        val targetUrl = definition.targetUrl
            ?: return LinovelibRelatedExpandedPageDataSource(searchRelated, definition.title)
        val wenkuFilters = if (id == WENKU_PAGE_ID) LinovelibWenkuFilters(host) else null
        return LinovelibLinkedExpandedPageDataSource(
            displayTag = definition.title,
            targetUrl = targetUrl,
            htmlLoader = htmlLoader,
            parser = parser,
            filters = wenkuFilters?.filters.orEmpty(),
            filteredUrl = wenkuFilters?.let { filters -> { filters.url() } },
            metadata = metadata
        )
    }

    companion object {
        private const val MAX_WENKU_PREVIEW_BOOKS = 12
        const val RELATED_PAGE_ID = "linovelib-related"
        const val WENKU_PAGE_ID = "linovelib-wenku"
    }
}

private class LinovelibRowsExploreTapPage(
    override val title: String,
    private val rowsLoader: suspend () -> List<ExploreBooksRow>
) : ExploreTapPageDataSource {
    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        // API 3 hosts retain the previous tab's rows until this flow emits.
        emit(emptyList())
        emit(rowsLoader())
    }
}
