package io.nightfish.lightnovelreader.plugin.linovelib.migration

/** The host owns storage truth; a session never sees a snapshot, database or raw receipt. */
internal interface MigrationHost {
    fun inspectOnLoad(): MigrationLoadResult
    fun executeAttempt(admissionCheck: () -> Unit, onProgress: (MigrationProgress) -> Unit): MigrationAttemptResult
}

internal sealed interface MigrationLoadResult {
    data object Candidate : MigrationLoadResult
    data object Disabled : MigrationLoadResult
}

internal enum class MigrationReason {
    NotReady, StartupConflict, SessionChanged, DisplayUnavailable, SchedulingFailure,
    NoData, SourceChanged, InputChanged, TargetAlreadyMigratedInactive, InvalidData, UnsupportedHost, StorageFailure,
}

internal class MigrationAdmissionException(val reason: MigrationReason, val retryable: Boolean) :
    IllegalStateException(reason.name)

internal sealed interface MigrationAttemptResult {
    data object Completed : MigrationAttemptResult
    data class NoWork(val reason: MigrationReason) : MigrationAttemptResult
    // Produced only after proving that no write started, or that the transaction rolled back.
    data class NotCommitted(val reason: MigrationReason, val retryable: Boolean) : MigrationAttemptResult
    data object RestartRequired : MigrationAttemptResult
}

internal enum class MigrationStage { Waiting, Reading, Merging, Saving, Verifying }

internal data class MigrationProgress(
    val stage: MigrationStage,
    val processed: Int = 0,
    val total: Int = 0,
)

internal sealed interface MigrationState {
    data object AwaitingEntry : MigrationState
    data object Confirming : MigrationState
    data class Running(val attemptId: Long, val progress: MigrationProgress) : MigrationState
    data class RetryableFailure(val reason: MigrationReason) : MigrationState
    data class Stopped(val reason: MigrationReason) : MigrationState
    data object Completed : MigrationState
    data object RestartRequired : MigrationState
    data object Closed : MigrationState
}

/** One serial worker, main-thread delivery and a monotonic clock. Tests control this schedule. */
internal interface MigrationRuntime {
    fun nowMillis(): Long
    fun execute(task: () -> Unit)
    fun post(task: () -> Unit)
    fun pause(millis: Long)
    fun shutdown()
}

/**
 * Owns one cold-start session, independently of Activity recreation.
 * Public events and state delivery are main-thread serialized, except unload's immediate revocation.
 */
internal class LinovelibMigrationSession(
    private val host: MigrationHost,
    private val runtime: MigrationRuntime,
    private val admissionCheck: (Long) -> Unit,
    private val onState: (MigrationState) -> Unit,
) {
    var state: MigrationState = MigrationState.AwaitingEntry
        private set
    @Volatile private var alive = true
    val isAlive: Boolean get() = alive
    @Volatile private var coldSessionValid = false
    private var firstActivitySeen = false
    private var loaded = false
    private var nextAttemptId = 0L

    fun onLoad(): Boolean {
        if (loaded || !alive) return false
        loaded = true
        if (runCatching { host.inspectOnLoad() }.getOrDefault(MigrationLoadResult.Disabled) != MigrationLoadResult.Candidate) {
            close()
            return false
        }
        return true
    }

    fun onActivityCreated(isMain: Boolean, restored: Boolean, ordinaryEntry: Boolean) {
        if (!alive || !loaded) return
        if (!firstActivitySeen) {
            firstActivitySeen = true
            coldSessionValid = isMain && !restored && ordinaryEntry
            if (!coldSessionValid) close()
        } else if (isMain && !restored) {
            coldSessionValid = false
            // Do not abandon a transaction whose result is still in flight.
            if (state !is MigrationState.Running && state != MigrationState.Completed && state != MigrationState.RestartRequired) {
                publish(MigrationState.Stopped(MigrationReason.SessionChanged))
            }
        }
    }

    fun onMainResumed() {
        if (alive && coldSessionValid && state == MigrationState.AwaitingEntry) {
            // The initial modal is what reserves the ordinary startup window. If it cannot
            // be displayed, a later resume cannot prove no host interaction happened meanwhile.
            if (!publish(MigrationState.Confirming)) close()
        }
    }

    fun start() {
        if (!alive || !coldSessionValid || (state != MigrationState.Confirming && state !is MigrationState.RetryableFailure)) return
        val attemptId = ++nextAttemptId
        val deadline = runtime.nowMillis() + STARTUP_TIMEOUT_MS
        // Rendering is synchronous: the progress modal must exist before scheduling the worker.
        if (!publish(MigrationState.Running(attemptId, MigrationProgress(MigrationStage.Waiting)))) {
            finish(attemptId, MigrationAttemptResult.NotCommitted(MigrationReason.DisplayUnavailable, true))
            return
        }
        try {
            runtime.execute {
                val startupFailure = try {
                    awaitStartup(attemptId, deadline)
                    null
                } catch (failure: MigrationAdmissionException) {
                    MigrationAttemptResult.NotCommitted(failure.reason, failure.retryable)
                } catch (_: Throwable) {
                    MigrationAttemptResult.NotCommitted(MigrationReason.UnsupportedHost, false)
                }
                val result = startupFailure ?: try {
                    host.executeAttempt(
                        admissionCheck = { assertAdmission(attemptId) },
                        onProgress = { update ->
                            // UI delivery must never throw through a storage transaction.
                            runCatching { runtime.post { progress(attemptId, update) } }
                        },
                    )
                } catch (_: Throwable) {
                    // An adapter that breaks its result contract may have committed already.
                    MigrationAttemptResult.RestartRequired
                }
                runtime.post { finish(attemptId, result) }
            }
        } catch (_: Exception) {
            finish(attemptId, MigrationAttemptResult.NotCommitted(MigrationReason.SchedulingFailure, true))
        }
    }

    private fun awaitStartup(attemptId: Long, deadline: Long) {
        while (true) {
            assertSession()
            if (runtime.nowMillis() >= deadline) throw MigrationAdmissionException(MigrationReason.NotReady, true)
            try {
                assertAdmission(attemptId)
                return
            } catch (failure: MigrationAdmissionException) {
                if (!failure.retryable) throw failure
            }
            runtime.pause(minOf(STARTUP_POLL_MS, (deadline - runtime.nowMillis()).coerceAtLeast(0)))
        }
    }

    private fun assertSession() {
        if (!alive || !coldSessionValid) throw MigrationAdmissionException(MigrationReason.SessionChanged, false)
    }

    private fun assertAdmission(attemptId: Long) {
        assertSession()
        admissionCheck(attemptId)
        assertSession()
    }

    private fun progress(attemptId: Long, progress: MigrationProgress) {
        val running = state as? MigrationState.Running ?: return
        if (alive && running.attemptId == attemptId && !publish(running.copy(progress = progress))) {
            onForegroundDisplayFailure()
        }
    }

    private fun finish(attemptId: Long, result: MigrationAttemptResult) {
        val running = state as? MigrationState.Running ?: return
        if (!alive || running.attemptId != attemptId) return
        val next = when (result) {
            MigrationAttemptResult.Completed -> MigrationState.Completed
            MigrationAttemptResult.RestartRequired -> MigrationState.RestartRequired
            is MigrationAttemptResult.NoWork -> MigrationState.Stopped(result.reason)
            is MigrationAttemptResult.NotCommitted -> when {
                !coldSessionValid -> MigrationState.Stopped(MigrationReason.SessionChanged)
                result.retryable -> MigrationState.RetryableFailure(result.reason)
                else -> MigrationState.Stopped(result.reason)
            }
        }
        if (!publish(next) && (next is MigrationState.RetryableFailure || next is MigrationState.Stopped)) close()
    }

    fun onForegroundDisplayFailure() {
        if (!alive || state == MigrationState.Completed || state == MigrationState.RestartRequired) return
        // A foreground page whose modal failed may accept ordinary host operations. It must
        // not later borrow the old cold-start admission, even if a subsequent show succeeds.
        coldSessionValid = false
        if (state !is MigrationState.Running) close()
    }

    fun dismiss() {
        if (state == MigrationState.Confirming || state is MigrationState.RetryableFailure || state is MigrationState.Stopped) close()
    }

    private fun close() {
        alive = false
        coldSessionValid = false
        runtime.shutdown()
        publish(MigrationState.Closed)
    }

    fun onUnload() {
        // May be called off the main thread. Revoke before enqueuing any UI cleanup.
        alive = false
        coldSessionValid = false
        runtime.shutdown() // Existing work settles naturally; never shutdownNow().
        runtime.post { publish(MigrationState.Closed) }
    }

    private fun publish(next: MigrationState): Boolean {
        state = next
        // Rendering errors cannot change storage truth or launch another attempt.
        return runCatching { onState(next) }.isSuccess
    }

    companion object {
        private const val STARTUP_TIMEOUT_MS = 30_000L
        private const val STARTUP_POLL_MS = 250L
    }
}
