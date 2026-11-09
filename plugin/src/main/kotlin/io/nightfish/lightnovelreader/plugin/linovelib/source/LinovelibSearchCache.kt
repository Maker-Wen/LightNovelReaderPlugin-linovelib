package io.nightfish.lightnovelreader.plugin.linovelib.source

/** Retains completed keyword lists for one data source instance. */
internal class LinovelibSearchCache(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Entry(val books: List<ParsedExploreBook>, val storedAtMillis: Long)

    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun get(url: String): List<ParsedExploreBook>? {
        val entry = entries[url] ?: return null
        if (nowMillis() - entry.storedAtMillis >= TTL_MILLIS) {
            entries.remove(url)
            return null
        }
        return entry.books
    }

    @Synchronized
    fun remember(url: String, books: List<ParsedExploreBook>) {
        val now = nowMillis()
        entries.entries.removeAll { now - it.value.storedAtMillis >= TTL_MILLIS }
        entries.remove(url)
        entries[url] = Entry(books.toList(), now)
        if (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    private companion object {
        const val TTL_MILLIS = 30_000L
        const val MAX_ENTRIES = 8
    }
}
