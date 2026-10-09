package io.nightfish.lightnovelreader.plugin.linovelib

import android.util.Log
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin

@Suppress("unused")
@Plugin(
    version = BuildConfig.VERSION_CODE,
    name = "Linovelib",
    versionName = BuildConfig.VERSION_NAME,
    author = "LightNovelReader contributor",
    description = "Linovelib 简体站数据源",
    updateUrl = "",
    apiVersion = 3
)
class LinovelibPlugin : LightNovelReaderPlugin {
    override fun onLoad() {
        Log.i("LinovelibPlugin", "Linovelib plugin loaded")
    }
}
