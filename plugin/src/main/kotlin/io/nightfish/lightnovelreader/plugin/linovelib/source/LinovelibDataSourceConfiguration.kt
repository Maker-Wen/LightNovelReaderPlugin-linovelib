package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.content.Context

internal object LinovelibDataSourceConfiguration {
    const val cacheEntriesPerType = 256
    const val cacheTimeoutMillis = 21_600_000

    private const val preferencesName = "io.nightfish.lightnovelreader.plugin.linovelib"
    private const val siteKey = "site"

    fun shouldStartOfflineMonitor(hasActiveMonitor: Boolean): Boolean = !hasActiveMonitor

    fun site(context: Context): LinovelibSite = LinovelibSite.fromStoredValue(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString(siteKey, null)
    )

    fun setSite(context: Context, site: LinovelibSite) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(siteKey, site.name)
            .apply()
    }
}
