package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.net.Uri
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import java.time.LocalDateTime

internal fun ParsedExploreBook.toParsedBookInformation() = ParsedBookInformation(
    id = id,
    title = title,
    subtitle = "",
    coverUrl = coverUrl,
    author = author,
    description = description,
    tags = tags,
    publishingHouse = publishingHouse,
    wordCount = wordCount,
    lastUpdated = LinovelibDates.unknownLocalDate,
    isComplete = isComplete
)

internal fun ParsedBookInformation.toMutableBookInformation(): MutableBookInformation =
    MutableBookInformation(
        id = id,
        title = title,
        subtitle = subtitle,
        coverUrl = Uri.parse(coverUrl),
        author = author,
        description = description,
        tags = LinovelibRelatedSearch.displayTags(author, tags),
        publishingHouse = publishingHouse,
        wordCount = WordCount(wordCount),
        lastUpdated = if (lastUpdated == LinovelibDates.unknownLocalDate) LocalDateTime.MIN else lastUpdated.atStartOfDay(),
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
