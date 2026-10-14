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
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Android page/dialog adapter. The Session owns workflow; the host adapter owns storage truth. */
@Suppress("UNUSED_PARAMETER")
internal class LinovelibMigrationController(
    context: Context,
    userData: UserDataRepositoryApi,
    localBooks: LocalBookDataSourceApi,
    bookshelves: BookshelfRepositoryApi,
    books: BookRepositoryApi,
    sourceManager: WebBookDataSourceManagerApi,
) {
    private val application = context.applicationContext as? Application
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LinovelibMigration").apply { isDaemon = true }
    }
    // Capture the original startup evidence now, never at start/retry or page recreation.
    private val gate = Api3StartupGate(context.applicationContext)
    private var resumedActivity = WeakReference<Activity>(null)
    private var registered = false

    private data class ProgressBinding(val activity: WeakReference<Activity>, val dialogToken: Any, val attemptId: Long)
    private val binding = AtomicReference<ProgressBinding?>(null)
    private class RenderedDialog(
        val owner: Activity,
        val dialog: AlertDialog,
        val key: Any,
        val token: Any,
        val progressText: TextView? = null,
        val progressBar: ProgressBar? = null,
    )
    private var rendered: RenderedDialog? = null

    private val session = LinovelibMigrationSession(
        host = Api3HostAdapter(context.applicationContext, userData),
        runtime = object : MigrationRuntime {
            override fun nowMillis() = SystemClock.elapsedRealtime()
            override fun execute(task: () -> Unit) { worker.execute(task) }
            override fun post(task: () -> Unit) { main.post(task) }
            override fun pause(millis: Long) { Thread.sleep(millis) }
            override fun shutdown() { worker.shutdown() }
        },
        admissionCheck = ::assertAdmission,
        onState = ::render,
    )

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(target: Activity, savedInstanceState: Bundle?) {
            val intent = target.intent
            session.onActivityCreated(
                isMain = target.javaClass.name == MAIN_ACTIVITY,
                restored = savedInstanceState != null,
                ordinaryEntry = intent?.data == null && (intent?.action == null || intent.action == Intent.ACTION_MAIN),
            )
        }

        override fun onActivityResumed(target: Activity) {
            if (target.javaClass.name != MAIN_ACTIVITY || !session.isAlive) return
            if (resumedActivity.get() !== target) dismissDialog()
            resumedActivity = WeakReference(target)
            session.onMainResumed()
            renderSafely(session.state)
        }

        override fun onActivityPaused(target: Activity) {
            revokeActivity(target)
            if (resumedActivity.get() === target) resumedActivity.clear()
            if (rendered?.owner === target) dismissDialog()
        }

        override fun onActivityDestroyed(target: Activity) {
            revokeActivity(target)
            if (resumedActivity.get() === target) resumedActivity.clear()
            if (rendered?.owner === target) dismissDialog()
        }

        override fun onActivityStarted(target: Activity) = Unit
        override fun onActivityStopped(target: Activity) = Unit
        override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
    }

    fun onLoad() {
        if (application == null) { session.onUnload(); return }
        if (session.onLoad()) {
            application.registerActivityLifecycleCallbacks(callbacks)
            registered = true
        }
    }

    private fun assertAdmission(attemptId: Long) {
        val current = binding.get()
        val target = current?.activity?.get()
        if (current == null || target == null || current.attemptId != attemptId) {
            throw MigrationAdmissionException(MigrationReason.NotReady, true)
        }
        // The immutable binding includes the main thread's resumed + actually-shown facts.
        // Never touch Dialog controls or synchronously wait for main while in a transaction.
        gate.assertReady(target)
        if (binding.get() !== current) throw MigrationAdmissionException(MigrationReason.NotReady, true)
    }

    private fun revokeActivity(target: Activity) {
        val current = binding.get() ?: return
        if (current.activity.get() === target) binding.compareAndSet(current, null)
    }

    private fun revokeDialog(token: Any) {
        val current = binding.get() ?: return
        if (current.dialogToken === token) binding.compareAndSet(current, null)
    }

    private fun renderSafely(state: MigrationState) {
        runCatching { render(state) }.onFailure {
            Log.e(TAG, "Migration dialog could not be displayed", it)
            session.onForegroundDisplayFailure()
        }
    }

    private fun render(state: MigrationState) {
        if (state == MigrationState.Closed) {
            binding.set(null)
            dismissDialog()
            if (registered) application?.unregisterActivityLifecycleCallbacks(callbacks)
            registered = false
            return
        }
        if (state == MigrationState.AwaitingEntry) return
        if (!session.isAlive) return
        val target = resumedActivity.get()
        if (target == null || target.isFinishing || target.isDestroyed) {
            check(state != MigrationState.Confirming) { "Initial migration modal has no resumed page" }
            return
        }
        val key = if (state is MigrationState.Running) state.attemptId else state
        val previous = rendered
        if (previous?.owner === target && previous.key == key && previous.dialog.isShowing) {
            if (state is MigrationState.Running) updateProgress(previous, state.progress)
            return
        }
        dismissDialog()
        val builder = AlertDialog.Builder(target, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        var text: TextView? = null
        var bar: ProgressBar? = null
        when (state) {
            MigrationState.Confirming -> builder
                .setTitle("合并 TW 数据")
                .setMessage(
                    "检测到 Linovelib TW 的本地数据，是否合并到当前 Linovelib？\n\n" +
                        "书架、阅读记录和阅读时长将一并合并。书籍资料、目录和正文缓存优先保留当前 Linovelib 已有的数据。\n\n" +
                        "完成后请重新打开阅读器。"
                )
                .setPositiveButton("开始同步") { _, _ -> session.start() }
                .setNegativeButton("暂不同步") { _, _ -> session.dismiss() }
                .setOnCancelListener { session.dismiss() }
            is MigrationState.Running -> {
                val padding = (24 * target.resources.displayMetrics.density).toInt()
                val layout = LinearLayout(target).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padding, padding / 2, padding, padding)
                }
                text = TextView(target).also { layout.addView(it) }
                bar = ProgressBar(target, null, android.R.attr.progressBarStyleHorizontal).also {
                    layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
                }
                builder.setTitle("正在同步").setView(layout).setCancelable(false)
            }
            MigrationState.Completed -> builder
                .setTitle("同步已完成")
                .setMessage("数据已保存。请重新打开阅读器，加载合并后的数据。")
                .setCancelable(false)
                .setPositiveButton("关闭阅读器") { _, _ -> closeReader(target) }
            MigrationState.RestartRequired -> builder
                .setTitle("保存结果待确认")
                .setMessage("暂时无法确认本次保存结果。为避免重复合并，请关闭并重新打开阅读器后再检查。")
                .setCancelable(false)
                .setPositiveButton("关闭阅读器") { _, _ -> closeReader(target) }
            is MigrationState.RetryableFailure -> builder
                .setTitle("同步未完成")
                .setMessage("本次同步未完成，已确认没有保存本次合并结果。${reasonMessage(state.reason)}")
                .setPositiveButton("重试") { _, _ -> session.start() }
                .setNegativeButton("关闭") { _, _ -> session.dismiss() }
                .setOnCancelListener { session.dismiss() }
            is MigrationState.Stopped -> builder
                .setTitle("本次未同步")
                .setMessage(reasonMessage(state.reason))
                .setPositiveButton("关闭") { _, _ -> session.dismiss() }
                .setOnCancelListener { session.dismiss() }
            MigrationState.AwaitingEntry, MigrationState.Closed -> return
        }
        val token = Any()
        val dialog = builder.create()
        val record = RenderedDialog(target, dialog, key, token, text, bar)
        dialog.setOwnerActivity(target)
        dialog.setCanceledOnTouchOutside(state == MigrationState.Confirming || state is MigrationState.RetryableFailure || state is MigrationState.Stopped)
        dialog.setOnDismissListener {
            // Delayed dismissal of an old confirmation cannot revoke a new progress modal.
            revokeDialog(token)
            if (rendered === record) rendered = null
        }
        rendered = record
        try {
            if (state is MigrationState.Running) updateProgress(record, state.progress)
            dialog.show()
            check(dialog.isShowing) { "Migration dialog did not become visible" }
            if (state is MigrationState.Running && session.isAlive && resumedActivity.get() === target) {
                binding.set(ProgressBinding(WeakReference(target), token, state.attemptId))
            }
        } catch (failure: Exception) {
            revokeDialog(token)
            if (rendered === record) rendered = null
            runCatching { dialog.dismiss() }
            Log.e(TAG, "Migration dialog could not be displayed", failure)
            throw failure
        }
    }

    private fun updateProgress(record: RenderedDialog, progress: MigrationProgress) {
        val message = when (progress.stage) {
            MigrationStage.Waiting -> "正在等待应用初始化…"
            MigrationStage.Reading -> "正在读取和校验本地数据…"
            MigrationStage.Merging -> "正在合并本地数据…"
            MigrationStage.Saving -> "正在整理和保存合并结果…"
            MigrationStage.Verifying -> "正在核对保存结果…"
        }
        record.progressText?.text = if (progress.total > 0) "$message\n已处理 ${progress.processed} / ${progress.total} 本" else message
        record.progressBar?.apply {
            isIndeterminate = progress.stage != MigrationStage.Merging || progress.total <= 0 || progress.processed >= progress.total
            if (!isIndeterminate) { max = progress.total; this.progress = progress.processed }
        }
    }

    private fun dismissDialog() {
        val previous = rendered ?: return
        revokeDialog(previous.token)
        rendered = null
        runCatching { previous.dialog.dismiss() }.onFailure { Log.w(TAG, "Migration dialog cleanup failed", it) }
    }

    private fun closeReader(target: Activity) {
        target.finishAffinity()
        Process.killProcess(Process.myPid())
    }

    fun onUnload() {
        binding.set(null)
        session.onUnload()
    }

    private fun reasonMessage(reason: MigrationReason): String = when (reason) {
        MigrationReason.NoData -> "没有可迁入的 TW 数据，本次未保存任何合并结果。"
        MigrationReason.TargetAlreadyMigratedInactive -> "当前 Linovelib 的本地数据中已有迁入记录，本次没有再次合并。"
        MigrationReason.NotReady -> "请返回前台，等待应用初始化完成后重试。"
        MigrationReason.StartupConflict -> "当前启动有后台任务，请关闭并重新打开阅读器后再试。"
        MigrationReason.SessionChanged -> "当前启动会话已变化，请关闭并重新打开阅读器后再试。"
        MigrationReason.DisplayUnavailable -> "迁入窗口未能显示，请返回主页面后重试。"
        MigrationReason.SchedulingFailure -> "本次任务未能启动，可以重试。"
        MigrationReason.SourceChanged -> "当前数据源已变化，本次没有继续合并。请重新打开阅读器后再试。"
        MigrationReason.InputChanged -> "本地快照已变化，重试时将重新读取和校验。"
        MigrationReason.InvalidData -> "本地数据或完成凭据无法通过校验，请检查本地数据后重新打开阅读器。"
        MigrationReason.UnsupportedHost -> "当前宿主状态不符合迁入条件，请重新打开阅读器后再试。"
        MigrationReason.StorageFailure -> "本地数据读取或保存失败，可以重试。"
    }

    companion object {
        private const val TAG = "LinovelibMigration"
        private const val MAIN_ACTIVITY = "indi.dmzz_yyhyy.lightnovelreader.MainActivity"
    }
}
