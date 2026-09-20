package io.nightfish.lightnovelreader.plugin.linovelib

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.plugin.linovelib.source.LinovelibDataSourceConfiguration
import io.nightfish.lightnovelreader.plugin.linovelib.source.LinovelibSite
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@Suppress("unused")
@Plugin(
    version = BuildConfig.VERSION_CODE,
    name = "Linovelib",
    versionName = BuildConfig.VERSION_NAME,
    author = "LightNovelReader contributor",
    description = "可切换简体站与繁体站的 Linovelib 数据源",
    updateUrl = "",
    apiVersion = 3
)
class LinovelibPlugin(
    private val context: Context
) : LightNovelReaderPlugin {
    override fun onLoad() {
        Log.i("LinovelibPlugin", "Linovelib plugin loaded")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun PageContent(paddingValues: PaddingValues) {
        var selectedSite by remember {
            mutableStateOf(LinovelibDataSourceConfiguration.site(context))
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("内容来源", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    LinovelibSite.entries.forEachIndexed { index, site ->
                        SegmentedButton(
                            selected = selectedSite == site,
                            onClick = {
                                if (selectedSite != site) {
                                    LinovelibDataSourceConfiguration.setSite(context, site)
                                    selectedSite = site
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "重启应用以生效",
                                            actionLabel = "重启"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) restartApp()
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, LinovelibSite.entries.size)
                        ) {
                            Text(if (site == LinovelibSite.SIMPLIFIED) "简体站" else "繁体站")
                        }
                    }
                }
                Text(
                    "切换后重启应用生效。书架和阅读进度会保留，已下载章节不会自动转换。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    private fun restartApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Handler(Looper.getMainLooper()).postDelayed({
            context.startActivity(launchIntent)
            if (context is Activity) context.finishAffinity()
            exitProcess(0)
        }, 500)
    }
}
