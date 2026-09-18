package io.nightfish.lightnovelreader.plugin.linovelib_simplified.source

internal object LinovelibDataSourceConfiguration {
    const val cacheEntriesPerType = 256
    const val cacheTimeoutMillis = 21_600_000

    fun shouldStartOfflineMonitor(hasActiveMonitor: Boolean): Boolean = !hasActiveMonitor
}
