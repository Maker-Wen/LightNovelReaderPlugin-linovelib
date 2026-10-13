package io.nightfish.lightnovelreader.plugin.linovelib.migration

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Checks the finite, ordinary startup window used by the migration controller.
 *
 * The controller must show its native modal synchronously on the first ordinary MainActivity
 * resume, before allowing a user interaction, and keep it until the process restarts. This class
 * does not pause, cancel, resume, or write the host's WorkManager jobs.
 */
internal class Api3StartupGate(private val context: Context) {
    private val startupWasCold = isApplicationBinding()
    // Capture before Application.onCreate cancels work: CANCELLED is not evidence that the
    // worker's code has stopped, so a conflict at this point excludes the whole startup session.
    private val initialWorkCheck = runCatching { noConflictingWorkSpecs() }.getOrDefault(false)
    private var lastInitializerDiagnostic: String? = null
    private var lastWorkerDiagnostic: String? = null
    private var lastReadinessFailure: String? = null

    init {
        Log.i(TAG, "STARTUP_GATE_CAPTURE cold=$startupWasCold initialWorkClear=$initialWorkCheck")
    }

    /** Called on an IO thread while the controller owns the native startup modal. */
    fun isReady(activity: Activity): Boolean = runCatching {
        assertReady(activity)
        true
    }.onFailure { failure ->
        val diagnostic = "${failure.javaClass.name}: ${failure.message}"
        if (diagnostic != lastReadinessFailure) {
            lastReadinessFailure = diagnostic
            Log.w(TAG, "STARTUP_GATE_WAIT $diagnostic", failure)
        }
    }.getOrDefault(false)

    fun assertReady(activity: Activity) {
        check(startupWasCold && initialWorkCheck) { "当前启动有后台任务，请稍后重新打开应用后同步" }
        check(activity.javaClass.name == MAIN_ACTIVITY && !activity.isFinishing && !activity.isDestroyed) {
            "当前页面不适合开始同步，请重新打开应用"
        }
        check(defaultBookshelfInitializationFinished(activity)) { "正在等待应用初始化，请稍后重试" }
        check(noConflictingWorkSpecs()) { "应用后台任务正在运行，请稍后重新打开应用后同步" }
        check(noExecutingWorkers(activity)) { "应用后台任务尚未结束，请稍后重新打开应用后同步" }
    }

    private fun defaultBookshelfInitializationFinished(activity: Activity): Boolean {
        // MainActivity starts one finite bookshelf initializer, six display observers (five on
        // pre-S devices), and four formatting observers in its private IO scope. Observers never
        // complete normally; their failure cancels the parent Job. Once only those active children
        // remain, the initializer has completed, including its possible default-shelf write.
        val scopeField = activity.javaClass.declaredFields.single {
            !Modifier.isStatic(it.modifiers) && CoroutineScope::class.java.isAssignableFrom(it.type)
        }
        val scope = scopeField.read(activity) as CoroutineScope
        val parent = scope.coroutineContext[Job]
        val diagnostic = "active=${parent?.isActive == true} children=${parent?.children?.count()} " +
            "expected=${if (Build.VERSION.SDK_INT >= 31) 10 else 9}"
        if (diagnostic != lastInitializerDiagnostic) {
            lastInitializerDiagnostic = diagnostic
            Log.i(TAG, "STARTUP_GATE_INITIALIZER $diagnostic")
        }
        return api3DefaultInitializationFinished(parent, Build.VERSION.SDK_INT)
    }

    private fun noConflictingWorkSpecs(): Boolean {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        check(file.isFile) { "后台任务数据库尚未初始化" }
        SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            check(database.isReadOnly)
            return database.rawQuery(
                "SELECT count(*) FROM WorkSpec WHERE state IN (0, 1, 4) " +
                    "AND NOT ($IDLE_PERIODIC_PREDICATE)", arrayOf(CHECK_UPDATE_WORKER, CHECK_UPDATE_NAME)
            ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 0L }
        }
    }

    private fun idlePeriodicWorkIds(): Set<String> {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        check(file.isFile) { "后台任务数据库尚未初始化" }
        SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            check(database.isReadOnly)
            return database.rawQuery(
                "SELECT id FROM WorkSpec WHERE $IDLE_PERIODIC_PREDICATE",
                arrayOf(CHECK_UPDATE_WORKER, CHECK_UPDATE_NAME)
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
    }

    private fun noExecutingWorkers(activity: Activity): Boolean {
        // WorkManager and Processor are obfuscated in official 1.2.x. Resolve their verified
        // structure instead of linking their R8 names or relying only on cancelled WorkSpec rows.
        val workManager = activity.javaClass.declaredFields.asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }
            .mapNotNull { it.read(activity) }
            .single { candidate ->
                candidate.javaClass.declaredFields.any {
                    it.type.name == "androidx.work.impl.WorkDatabase"
                } && candidate.javaClass.declaredFields.any {
                    CoroutineScope::class.java.isAssignableFrom(it.type)
                }
            }
        val processor = workManager.javaClass.declaredFields.asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }
            .mapNotNull { it.read(workManager) }
            .single { candidate ->
                val fields = candidate.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                fields.count { Map::class.java.isAssignableFrom(it.type) } == 3 &&
                    fields.any { it.type.name == "androidx.work.impl.WorkDatabase" } &&
                    fields.count { it.type == Any::class.java } == 1
            }
        val fields = processor.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val lock = requireNotNull(fields.single { it.type == Any::class.java }.read(processor))
        val idleIds = idlePeriodicWorkIds()
        return synchronized(lock) {
            val maps = fields.filter { Map::class.java.isAssignableFrom(it.type) }
                .map { it.read(processor) as Map<*, *> }
            val diagnostic = "mapSizes=${maps.map { it.size }} idlePeriodicIds=${idleIds.size}"
            if (diagnostic != lastWorkerDiagnostic) {
                lastWorkerDiagnostic = diagnostic
                Log.i(TAG, "STARTUP_GATE_PROCESSOR $diagnostic")
            }
            api3ProcessorContainsOnlyIdlePeriodicWork(maps.map { it.keys }, idleIds)
        }
    }

    private fun Field.read(instance: Any): Any? {
        isAccessible = true
        return get(instance)
    }

    companion object {
        private const val TAG = "LinovelibMigration"
        private const val MAIN_ACTIVITY = "indi.dmzz_yyhyy.lightnovelreader.MainActivity"
        private const val CHECK_UPDATE_WORKER = "indi.dmzz_yyhyy.lightnovelreader.data.work.CheckUpdateWork"
        private const val CHECK_UPDATE_NAME = "checkUpdate"
        // In the current official API 3 APKs, CheckUpdateWork waits before requesting or writing
        // book data. Their release DEX folds isAppStopped to false: the wait is an unconditional
        // 5-second delay loop, including when the UI is backgrounded.
        // Identify that single idle worker by class, periodic interval, and its unique work name;
        // no other periodic or cancelled worker receives this exception.
        private const val IDLE_PERIODIC_PREDICATE =
            "worker_class_name = ? AND interval_duration > 0 AND EXISTS " +
                "(SELECT 1 FROM WorkName WHERE WorkName.work_spec_id = WorkSpec.id AND WorkName.name = ?)"

        private fun isApplicationBinding(): Boolean =
            Thread.currentThread() === Looper.getMainLooper().thread &&
                Thread.currentThread().stackTrace.any {
                    it.className == "android.app.ActivityThread" && it.methodName == "handleBindApplication"
                }
    }
}

internal fun api3DefaultInitializationFinished(parent: Job?, apiLevel: Int): Boolean {
    if (parent?.isActive != true) return false
    val children = parent.children.toList()
    val observerCount = if (apiLevel >= 31) 10 else 9
    return children.size == observerCount && children.all { it.isActive }
}

internal fun api3ProcessorContainsOnlyIdlePeriodicWork(
    processorKeys: List<Set<*>>,
    idlePeriodicIds: Set<String>,
): Boolean = processorKeys.all { keys -> keys.all { it is String && it in idlePeriodicIds } }
