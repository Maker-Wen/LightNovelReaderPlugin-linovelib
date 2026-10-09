package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessingRepositoryApi
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class LinovelibCoverData(
    private val localData: LocalBookDataSourceApi,
    private val textProcessing: TextProcessingRepositoryApi,
    private val bookshelves: BookshelfRepositoryApi,
    private val isActive: () -> Boolean
) : TextProcessor {
    override val enabled: Boolean get() = isActive()

    // Only the active source's onLoad may call this: localData belongs to that source.
    fun onLoad() {
        runBlocking(Dispatchers.IO) {
            val bookIds = localData.getAllUserReadingData().map { it.id } +
                bookshelves.getAllBookshelfBooksMetadata().map { it.id }
            bookIds.distinct().forEach { repairCover(it) }
        }
        textProcessing.registerProcessors(this)
    }

    suspend fun repairCover(bookId: String) {
        localData.getBookInformation(bookId)?.let { book ->
            val original = book.coverUri.toString()
            val current = LinovelibUrls.currentCoverUrl(original)
            if (current != original) {
                localData.updateBookInformation(book.copy().toMutable().apply { coverUri = Uri.parse(current) })
            }
        }
    }

    override fun processBookVolumes(bookVolumes: BookVolumes): BookVolumes = bookVolumes

    override fun processText(text: String): String = text

    override fun processBookInformation(bookInformation: BookInformation): BookInformation {
        val original = bookInformation.coverUri.toString()
        val current = LinovelibUrls.currentCoverUrl(original)
        if (current == original) return bookInformation
        return bookInformation.toMutable().apply { coverUri = Uri.parse(current) }
    }

    override fun processChapterContent(
        bookId: String,
        chapterContent: ChapterContent,
        componentProcessor: ComponentProcessor
    ): ChapterContent = chapterContent

    override fun processExploreBooksRow(exploreDisplayBook: ExploreDisplayBook): ExploreDisplayBook = exploreDisplayBook
}
