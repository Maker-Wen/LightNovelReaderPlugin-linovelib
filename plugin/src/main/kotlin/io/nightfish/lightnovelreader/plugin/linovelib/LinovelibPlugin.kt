package io.nightfish.lightnovelreader.plugin.linovelib

import android.content.Context
import android.util.Log
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import io.nightfish.lightnovelreader.plugin.linovelib.migration.LinovelibMigrationController

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
class LinovelibPlugin(
    context: Context,
    userData: UserDataRepositoryApi,
    localBooks: LocalBookDataSourceApi,
    bookshelves: BookshelfRepositoryApi,
    books: BookRepositoryApi,
    sourceManager: WebBookDataSourceManagerApi,
) : LightNovelReaderPlugin {
    private val migration = LinovelibMigrationController(context, userData, localBooks, bookshelves, books, sourceManager)

    override fun onLoad() {
        Log.i("LinovelibPlugin", "Linovelib plugin loaded")
        migration.onLoad()
    }

    override fun onUnload() = migration.onUnload()

}
