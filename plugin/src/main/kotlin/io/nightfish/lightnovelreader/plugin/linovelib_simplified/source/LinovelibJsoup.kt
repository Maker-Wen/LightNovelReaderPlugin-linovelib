package io.nightfish.lightnovelreader.plugin.linovelib_simplified.source

import org.jsoup.Connection

internal fun Connection.acceptLinovelibContentTypes(): Connection =
    ignoreContentType(true)
