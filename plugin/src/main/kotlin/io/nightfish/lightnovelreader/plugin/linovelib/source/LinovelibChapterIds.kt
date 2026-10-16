package io.nightfish.lightnovelreader.plugin.linovelib.source

internal object LinovelibChapterIds {
    private const val PREFIX = "linovelib-v3:"

    fun forApp(chapterId: String): String =
        if (chapterId.isBlank()) chapterId else PREFIX + forWebsite(chapterId)

    fun forWebsite(chapterId: String): String =
        chapterId.removePrefix(PREFIX)
}
