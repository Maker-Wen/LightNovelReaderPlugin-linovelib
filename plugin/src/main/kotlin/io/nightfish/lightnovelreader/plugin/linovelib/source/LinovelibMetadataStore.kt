package io.nightfish.lightnovelreader.plugin.linovelib.source

/** Disposable metadata for one source instance; independent of the host's content cache. */
internal class LinovelibMetadataStore {
    private val books = linkedMapOf<String, ParsedBookInformation>()
    private val summaries = linkedMapOf<String, ParsedExploreBook>()
    private val relatedTargets = linkedMapOf<String, String>()

    @Synchronized
    fun rememberBook(book: ParsedBookInformation) {
        books.remember(book.id, book)
        if (book.author.isNotBlank() && book.authorUrl.isNotBlank()) {
            relatedTargets.remember(LinovelibRelatedSearch.authorDisplayTag(book.author), book.authorUrl)
        }
        book.relatedLinks.forEach { (tag, target) ->
            relatedTargets.remember(tag, target)
        }
    }

    @Synchronized
    fun rememberBooks(books: List<ParsedExploreBook>) {
        books.forEach { summaries.remember(it.id, it) }
    }

    @Synchronized
    fun bookInformation(id: String): ParsedBookInformation? = books[id]

    @Synchronized
    fun fallbackBookInformation(id: String): ParsedBookInformation? =
        books[id] ?: summaries[id]?.toParsedBookInformation()

    @Synchronized
    fun relatedTarget(displayTag: String): String? = relatedTargets[displayTag]

    // Refresh write order explicitly. Reads must not prolong retention.
    private fun <T> LinkedHashMap<String, T>.remember(key: String, value: T) {
        remove(key)
        put(key, value)
        if (size > LinovelibDataSourceConfiguration.cacheEntriesPerType) {
            val oldest = entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}
