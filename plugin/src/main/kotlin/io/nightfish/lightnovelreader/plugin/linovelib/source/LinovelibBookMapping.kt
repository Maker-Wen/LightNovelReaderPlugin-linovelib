package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount

internal fun ParsedExploreBook.toParsedBookInformation() = ParsedBookInformation(
    id = id,
    title = title,
    subtitle = "",
    coverUrl = coverUrl,
    author = author,
    description = "",
    tags = emptyList(),
    publishingHouse = "",
    wordCount = 0,
    lastUpdated = LinovelibDates.unknownLocalDate,
    isComplete = false
)

internal fun ParsedBookInformation.toBookInformation(): BookInformation =
    if (title.isBlank()) BookInformation.empty(id) else toMutableBookInformation()

internal fun ParsedBookInformation.toMutableBookInformation(): MutableBookInformation =
    MutableBookInformation(
        id = id,
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl.takeIf(String::isNotEmpty)?.let(Uri::parse) ?: Uri.EMPTY,
        author = author,
        description = description,
        tags = LinovelibRelatedSearch.displayTags(author, tags),
        publishingHouse = publishingHouse,
        wordCount = WordCount(wordCount),
        lastUpdated = if (LinovelibDates.isUnknown(lastUpdated)) LinovelibDates.unknownDateTime() else lastUpdated.atStartOfDay(),
        isComplete = isComplete
    )

internal fun ParsedCatalog.toBookVolumes(): BookVolumes = BookVolumes(
    bookId = bookId,
    volumes = volumes.mapIndexed { index, volume ->
        Volume(
            volumeId = "$bookId-${index + 1}",
            volumeTitle = volume.title,
            chapters = volume.chapters.map {
                ChapterInformation(id = LinovelibChapterIds.forApp(it.id), title = it.title)
            }
        )
    }
)
