package io.nightfish.lightnovelreader.plugin.linovelib_simplified

import android.util.Log
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin

@Suppress("unused")
@Plugin(
    version = BuildConfig.VERSION_CODE,
    name = "Linovelib 简体",
    versionName = BuildConfig.VERSION_NAME,
    author = "LightNovelReader contributor",
    description = "tw.linovelib.com 简体输出数据源",
    updateUrl = "",
    apiVersion = 2
)
class LinovelibPlugin : LightNovelReaderPlugin {
    override fun onLoad() {
        Log.i("LinovelibPlugin", "Linovelib 简体 plugin loaded")
    }
}
