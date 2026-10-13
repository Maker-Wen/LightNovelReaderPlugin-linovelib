package io.nightfish.lightnovelreader.plugin.linovelib.migration

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** One cold-start session. The database receipt, rather than this UI state, prevents repeats. */
internal class LinovelibMigrationController(
    context: Context,
    private val userData: UserDataRepositoryApi,
    localBooks: LocalBookDataSourceApi,
    bookshelves: BookshelfRepositoryApi,
    books: BookRepositoryApi,
    sourceManager: WebBookDataSourceManagerApi,
) {
    private val context = context.applicationContext
    private val application = this.context as? Application
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LinovelibMigration").apply { isDaemon = true }
    }
    private val adapter = Api3HostAdapter(this.context, userData, localBooks, bookshelves, books, sourceManager)
    private val gate = Api3StartupGate(this.context)
    private var activity = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var preparation: MigrationPreparation? = null
    private var firstActivitySeen = false
    private var coldSession = false
    @Volatile private var unloaded = false
    @Volatile private var admissionBlocked = false
    @Volatile private var phase = Phase.Detecting
    private var progressMessage = "正在读取和校验本地数据…"
    private var processed = 0
    private var total = 0

    private enum class Phase { Detecting, Confirming, Running, Completed, Failed, Closed }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(target: Activity, savedInstanceState: Bundle?) {
            if (!firstActivitySeen) {
                firstActivitySeen = true
                val intent = target.intent
                coldSession = target.javaClass.name == MAIN_ACTIVITY && savedInstanceState == null &&
                    intent?.data == null && (intent?.action == null || intent.action == Intent.ACTION_MAIN)
                if (!coldSession) closeSession()
            } else if (target.javaClass.name == MAIN_ACTIVITY && savedInstanceState == null) {
                // A new external session cannot borrow the original cold-start admission.
                admissionBlocked = true
            }
        }

        override fun onActivityResumed(target: Activity) {
            if (target.javaClass.name != MAIN_ACTIVITY || unloaded || !coldSession) return
            activity = WeakReference(target)
            when (phase) {
                Phase.Detecting -> {
                    // Display at the first resume, before the user can enter another session.
                    phase = Phase.Confirming
                    render()
                }
                Phase.Confirming, Phase.Running, Phase.Completed, Phase.Failed -> render()
                Phase.Closed -> Unit
            }
        }

        override fun onActivityPaused(target: Activity) {
            if (dialog?.ownerActivity === target) dismissDialog()
        }

        override fun onActivityDestroyed(target: Activity) {
            if (dialog?.ownerActivity === target) dismissDialog()
            if (activity.get() === target) activity.clear()
        }

        override fun onActivityStarted(target: Activity) = Unit
        override fun onActivityStopped(target: Activity) = Unit
        override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
    }

    fun onLoad() {
        if (application == null) return
        // Registration must precede source switches, exports, or source-local clearing.
        val supported = runCatching { adapter.initialize() }.onFailure {
            Log.w(TAG, "Migration adapter unavailable; no migration writes enabled", it)
        }.getOrDefault(false)
        if (!supported || runCatching { adapter.hasCompletedReceipt() }.getOrDefault(true) || !hasCandidate()) {
            phase = Phase.Closed
            worker.shutdown()
            return
        }
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun hasCandidate(): Boolean {
        val selected = userData.intUserData(UserDataPath.Settings.Data.WebDataSourceId.path).get()
        if (selected != TW_ID && selected != CURRENT_ID) return false
        return selected == TW_ID || File(context.dataDir, "local_data/$TW_ID").isFile ||
            File(context.dataDir, "local_data/-1").isFile
    }

    private fun start() {
        if (phase != Phase.Confirming && phase != Phase.Failed) return
        activity.get() ?: return
        phase = Phase.Running
        progressMessage = "正在读取和校验本地数据…"
        processed = 0
        total = 0
        render()
        worker.execute {
            runCatching {
                awaitStartup()
                val input = preparation ?: adapter.prepare()
                if (input == null) {
                    check(adapter.hasCompletedReceipt()) { "没有待迁入的数据，或旧数据已合并到非活动源" }
                    return@runCatching MigrationCommitResult.AlreadyCompleted
                }
                updateProgress("正在合并本地数据…", 0, input.bookCount)
                adapter.commit(input, { done, count ->
                    updateProgress(if (count > 0 && done == count) "正在整理和保存合并结果…" else "正在合并本地数据…", done, count)
                }, {
                    check(!unloaded && !admissionBlocked) { "Migration startup session changed" }
                    gate.assertReady(activity.get() ?: error("Migration activity is being recreated"))
                })
            }.fold(
                onSuccess = { result ->
                    Log.i(TAG, "Migration $result; receipt and data committed together")
                    main.post {
                        phase = Phase.Completed
                        render()
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Migration did not complete", error)
                    val committed = runCatching { adapter.hasCompletedReceipt() }.getOrDefault(false)
                    main.post {
                        preparation = null
                        phase = if (committed) Phase.Completed else Phase.Failed
                        render()
                    }
                },
            )
        }
    }

    private fun awaitStartup() {
        repeat(120) {
            check(!unloaded && !admissionBlocked) { "Migration startup session changed" }
            val target = activity.get()
            if (target != null && gate.isReady(target)) return
            Thread.sleep(250)
        }
        error("Host startup is not ready for migration")
    }

    private fun updateProgress(message: String, done: Int, count: Int) {
        main.post {
            progressMessage = message
            processed = done
            total = count
            if (phase == Phase.Running) updateProgressView()
        }
    }

    private fun render() {
        val target = activity.get() ?: return
        if (target.isFinishing || target.isDestroyed || unloaded) return
        dismissDialog()
        val builder = AlertDialog.Builder(target, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        when (phase) {
            Phase.Confirming -> builder
                .setTitle("合并 TW 数据")
                .setMessage(
                    "检测到 Linovelib TW 的本地数据，是否合并到当前 Linovelib？\n\n" +
                        "书架、阅读记录和阅读时长将一并合并。书籍资料、目录和正文缓存优先保留当前 Linovelib 已有的数据。\n\n" +
                        "完成后请重新打开阅读器。"
                )
                .setPositiveButton("开始同步") { _, _ -> start() }
                .setNegativeButton("暂不同步") { _, _ -> closeSession() }
                .setOnCancelListener { closeSession() }
            Phase.Running -> {
                val density = target.resources.displayMetrics.density
                val padding = (24 * density).toInt()
                val layout = LinearLayout(target).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padding, padding / 2, padding, padding)
                }
                progressText = TextView(target).also { layout.addView(it) }
                progressBar = ProgressBar(target, null, android.R.attr.progressBarStyleHorizontal).also {
                    layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
                }
                builder.setTitle("正在同步").setView(layout).setCancelable(false)
                updateProgressView()
            }
            Phase.Completed -> builder
                .setTitle("同步已完成")
                .setMessage("数据已保存。请重新打开阅读器，加载合并后的数据。")
                .setCancelable(false)
                .setPositiveButton("关闭阅读器") { _, _ ->
                    target.finishAffinity()
                    Process.killProcess(Process.myPid())
                }
            Phase.Failed -> builder
                .setTitle("同步未完成")
                .setMessage("本次同步未完成。可以重试，或关闭后稍后再试；重试会先检查保存状态，避免重复累计。")
                .setPositiveButton("重试") { _, _ -> start() }
                .setNegativeButton("关闭") { _, _ -> closeSession() }
                .setOnCancelListener { closeSession() }
            Phase.Detecting, Phase.Closed -> return
        }
        dialog = builder.create().also {
            it.setOwnerActivity(target)
            it.setCanceledOnTouchOutside(phase == Phase.Confirming || phase == Phase.Failed)
            it.show()
        }
    }

    private fun updateProgressView() {
        progressText?.text = if (total > 0) "$progressMessage\n已处理 $processed / $total 本" else progressMessage
        progressBar?.apply {
            isIndeterminate = total <= 0 || processed >= total
            if (!isIndeterminate) {
                max = total
                progress = processed
            }
        }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
        progressText = null
        progressBar = null
    }

    private fun closeSession() {
        phase = Phase.Closed
        dismissDialog()
        application?.unregisterActivityLifecycleCallbacks(callbacks)
        worker.shutdown()
    }

    fun onUnload() {
        unloaded = true
        application?.unregisterActivityLifecycleCallbacks(callbacks)
        main.post { dismissDialog() }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "LinovelibMigration"
        private const val MAIN_ACTIVITY = "indi.dmzz_yyhyy.lightnovelreader.MainActivity"
        private val TW_ID = "linovelib_tw".hashCode()
        private val CURRENT_ID = "linovelib".hashCode()
    }
}
