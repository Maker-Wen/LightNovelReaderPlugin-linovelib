package io.nightfish.lightnovelreader.plugin.linovelib.migration

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException

class LinovelibMigrationSessionTest {
    private class Runtime : MigrationRuntime {
        var now = 0L
        var shutDown = false
        var rejectExecution = false
        var onPause: () -> Unit = {}
        val workers = ArrayDeque<() -> Unit>()
        val posts = ArrayDeque<() -> Unit>()
        override fun nowMillis() = now
        override fun execute(task: () -> Unit) {
            if (rejectExecution || shutDown) throw RejectedExecutionException()
            workers.add(task)
        }
        override fun post(task: () -> Unit) { posts.add(task) }
        override fun pause(millis: Long) { now += millis; onPause() }
        override fun shutdown() { shutDown = true }
        fun work() { workers.removeFirst().invoke() }
        fun deliver() { while (posts.isNotEmpty()) posts.removeFirst().invoke() }
    }

    private class Host : MigrationHost {
        var load: MigrationLoadResult = MigrationLoadResult.Candidate
        var calls = 0
        val progressCallbacks = mutableListOf<(MigrationProgress) -> Unit>()
        var execute: (() -> Unit, (MigrationProgress) -> Unit) -> MigrationAttemptResult = { admission, _ ->
            admission()
            MigrationAttemptResult.Completed
        }
        override fun inspectOnLoad() = load
        override fun executeAttempt(admissionCheck: () -> Unit, onProgress: (MigrationProgress) -> Unit): MigrationAttemptResult {
            calls++
            progressCallbacks += onProgress
            return execute(admissionCheck, onProgress)
        }
    }

    private class Harness {
        val runtime = Runtime()
        val host = Host()
        val seen = mutableListOf<MigrationState>()
        var ready = true
        var permanentReason: MigrationReason? = null
        var renderingFails = false
        val session = LinovelibMigrationSession(host, runtime, { _ ->
            permanentReason?.let { throw MigrationAdmissionException(it, false) }
            if (!ready) throw MigrationAdmissionException(MigrationReason.NotReady, true)
        }, {
            seen += it
            if (renderingFails && it is MigrationState.Running) throw IllegalStateException("No window")
        })
        fun open() {
            assertTrue(session.onLoad())
            session.onActivityCreated(true, false, true)
            session.onMainResumed()
            assertEquals(MigrationState.Confirming, session.state)
        }
        fun settle() { runtime.work(); runtime.deliver() }
    }

    @Test fun rendersProgressSynchronouslyAndSchedulesOnlyOneAttempt() {
        val h = Harness().apply { open() }
        h.session.start()
        assertTrue(h.seen.last() is MigrationState.Running)
        h.session.start()
        h.session.dismiss()
        assertEquals(1, h.runtime.workers.size)
        h.settle()
        assertEquals(1, h.host.calls)
        assertEquals(MigrationState.Completed, h.session.state)
    }

    @Test fun restoredExternalOrNonMainEntryCannotBorrowColdStartup() {
        listOf(Triple(true, true, true), Triple(true, false, false), Triple(false, false, true)).forEach { entry ->
            val h = Harness()
            h.session.onLoad()
            h.session.onActivityCreated(entry.first, entry.second, entry.third)
            h.session.onMainResumed()
            h.session.start()
            assertEquals(MigrationState.Closed, h.session.state)
            assertTrue(h.runtime.workers.isEmpty())
        }
    }

    @Test fun disabledLoadNeverShowsConfirmationOrStartsWork() {
        val h = Harness()
        h.host.load = MigrationLoadResult.Disabled
        assertFalse(h.session.onLoad())
        h.session.onActivityCreated(true, false, true)
        h.session.onMainResumed()
        h.session.start()
        assertEquals(MigrationState.Closed, h.session.state)
        assertEquals(0, h.host.calls)
    }

    @Test fun recreationKeepsTheAttemptAndDoesNotExtendItsDeadline() {
        val h = Harness().apply { open(); ready = false }
        h.session.start()
        val attempt = h.session.state as MigrationState.Running
        h.runtime.onPause = {
            h.session.onActivityCreated(true, true, true)
            h.session.onMainResumed()
            assertEquals(attempt.attemptId, (h.session.state as MigrationState.Running).attemptId)
        }
        h.settle()
        assertEquals(30_000L, h.runtime.now)
        assertEquals(0, h.host.calls)
        assertEquals(MigrationState.RetryableFailure(MigrationReason.NotReady), h.session.state)
    }

    @Test fun aReboundPageMustPassReadinessBeforeExecuting() {
        val h = Harness().apply { open(); ready = false }
        h.session.start()
        h.runtime.onPause = {
            if (h.runtime.now == 250L) h.session.onActivityCreated(true, true, true)
            if (h.runtime.now == 750L) h.ready = true
            assertEquals(0, h.host.calls)
        }
        h.settle()
        assertEquals(750L, h.runtime.now)
        assertEquals(MigrationState.Completed, h.session.state)
    }

    @Test fun permanentStartupConflictDoesNotWaitOrOfferRetry() {
        val h = Harness().apply { open(); permanentReason = MigrationReason.StartupConflict }
        h.session.start()
        h.settle()
        assertEquals(0L, h.runtime.now)
        assertEquals(0, h.host.calls)
        assertEquals(MigrationState.Stopped(MigrationReason.StartupConflict), h.session.state)
    }

    @Test fun lateProgressFromAttemptACannotChangeAttemptBOrItsTerminalState() {
        val h = Harness().apply { open() }
        h.host.execute = { _, _ -> MigrationAttemptResult.NotCommitted(MigrationReason.StorageFailure, true) }
        h.session.start()
        val a = h.session.state as MigrationState.Running
        h.settle()
        h.host.execute = { _, _ -> MigrationAttemptResult.Completed }
        h.session.start()
        val b = h.session.state
        assertNotEquals(a.attemptId, (b as MigrationState.Running).attemptId)
        h.host.progressCallbacks[0](MigrationProgress(MigrationStage.Merging, 9, 10))
        h.runtime.deliver()
        assertEquals(b, h.session.state)
        h.settle()
        h.host.progressCallbacks[1](MigrationProgress(MigrationStage.Saving, 10, 10))
        h.runtime.deliver()
        assertEquals(MigrationState.Completed, h.session.state)
        assertEquals(2, h.host.calls)
    }

    @Test fun finishingBookEnumerationIsStillRunningUntilStorageReturns() {
        val h = Harness().apply { open() }
        h.host.execute = { _, progress ->
            progress(MigrationProgress(MigrationStage.Saving, 3, 3))
            MigrationAttemptResult.Completed
        }
        h.session.start()
        h.runtime.work()
        h.runtime.posts.removeFirst().invoke()
        assertEquals(MigrationStage.Saving, (h.session.state as MigrationState.Running).progress.stage)
        h.runtime.deliver()
        assertEquals(MigrationState.Completed, h.session.state)
    }

    @Test fun newEntryCannotDiscardAnInFlightCommit() {
        val h = Harness().apply { open() }
        h.host.execute = { _, _ ->
            h.session.onActivityCreated(true, false, false)
            assertTrue(h.session.state is MigrationState.Running)
            MigrationAttemptResult.Completed
        }
        h.session.start()
        h.settle()
        assertEquals(MigrationState.Completed, h.session.state)
        h.session.onMainResumed()
        assertEquals(MigrationState.Completed, h.session.state)
    }

    @Test fun invalidatedSessionWaitsForRollbackAndDoesNotOfferRetry() {
        val h = Harness().apply { open() }
        h.host.execute = { admission, _ ->
            h.session.onActivityCreated(true, false, false)
            try { admission(); fail("Should reject this entry") } catch (_: MigrationAdmissionException) { }
            assertTrue(h.session.state is MigrationState.Running)
            MigrationAttemptResult.NotCommitted(MigrationReason.NotReady, true)
        }
        h.session.start()
        h.settle()
        assertEquals(MigrationState.Stopped(MigrationReason.SessionChanged), h.session.state)
        h.session.start()
        assertEquals(1, h.host.calls)
    }

    @Test fun unloadRevokesAdmissionImmediatelyAndIgnoresPendingUiCallbacks() {
        val h = Harness().apply { open() }
        h.host.execute = { admission, progress ->
            h.session.onUnload()
            assertThrows(MigrationAdmissionException::class.java) { admission() }
            progress(MigrationProgress(MigrationStage.Saving, 3, 3))
            MigrationAttemptResult.Completed
        }
        h.session.start()
        h.settle()
        assertTrue(h.runtime.shutDown)
        assertEquals(MigrationState.Closed, h.session.state)
        h.session.start()
        assertEquals(1, h.host.calls)
    }

    @Test fun unloadBeforeScheduledWorkDoesNotEnterTheHost() {
        val h = Harness().apply { open(); ready = false }
        h.session.start()
        h.session.onUnload()
        h.settle()
        assertEquals(MigrationState.Closed, h.session.state)
        assertEquals(0, h.host.calls)
    }

    @Test fun ambiguousResultCannotRetryOrUseSuccessCopy() {
        val h = Harness().apply { open() }
        h.host.execute = { _, _ -> MigrationAttemptResult.RestartRequired }
        h.session.start()
        h.settle()
        assertEquals(MigrationState.RestartRequired, h.session.state)
        h.session.dismiss()
        h.session.start()
        assertEquals(MigrationState.RestartRequired, h.session.state)
        assertEquals(1, h.host.calls)
    }

    @Test fun unexpectedAdapterExceptionMustNotBeMisclassifiedAsSafeToRetry() {
        val h = Harness().apply { open() }
        h.host.execute = { _, _ -> throw MigrationAdmissionException(MigrationReason.NotReady, true) }
        h.session.start()
        h.settle()
        assertEquals(MigrationState.RestartRequired, h.session.state)
    }

    @Test fun noDataAndInactiveTargetReceiptAreDistinctFromCompletion() {
        listOf(MigrationReason.NoData, MigrationReason.TargetAlreadyMigratedInactive).forEach { reason ->
            val h = Harness().apply { open() }
            h.host.execute = { _, _ -> MigrationAttemptResult.NoWork(reason) }
            h.session.start()
            h.settle()
            assertEquals(MigrationState.Stopped(reason), h.session.state)
        }
    }

    @Test fun untrustedCompletionAndNoRecoverableDataCanBeDismissedWithoutRestartOrRetry() {
        listOf(MigrationReason.NoData, MigrationReason.UntrustedCompletion).forEach { reason ->
            val h = Harness().apply { open() }
            h.host.execute = { _, _ -> MigrationAttemptResult.NoWork(reason) }
            h.session.start()
            h.settle()
            assertEquals(MigrationState.Stopped(reason), h.session.state)
            h.session.start()
            assertEquals(1, h.host.calls)
            h.session.dismiss()
            assertEquals(MigrationState.Closed, h.session.state)
            assertFalse(h.session.isAlive)
            assertTrue(h.runtime.shutDown)
            h.session.onMainResumed()
            h.session.start()
            assertEquals(MigrationState.Closed, h.session.state)
            assertEquals(1, h.host.calls)
        }
    }

    @Test fun confirmedFailedWritesCanBeClosedToContinueUsingTheReader() {
        listOf(
            MigrationAttemptResult.NotCommitted(MigrationReason.StorageFailure, true),
            MigrationAttemptResult.NotCommitted(MigrationReason.InvalidData, false),
        ).forEach { outcome ->
            val h = Harness().apply { open() }
            h.host.execute = { _, _ -> outcome }
            h.session.start()
            h.settle()
            assertNotEquals(MigrationState.RestartRequired, h.session.state)
            h.session.dismiss()
            assertEquals(MigrationState.Closed, h.session.state)
            h.session.onMainResumed()
            h.session.start()
            assertEquals(1, h.host.calls)
        }
    }

    @Test fun dialogShowFailureDoesNotScheduleOrGetStuckRunning() {
        val h = Harness().apply { open(); renderingFails = true }
        h.session.start()
        assertEquals(MigrationState.RetryableFailure(MigrationReason.DisplayUnavailable), h.session.state)
        assertTrue(h.runtime.workers.isEmpty())
        assertEquals(0, h.host.calls)
    }

    @Test fun initialConfirmationShowFailurePermanentlyClosesThisStartupWindow() {
        val h = Harness()
        var failConfirmation = true
        val session = LinovelibMigrationSession(h.host, h.runtime, {}, {
            if (failConfirmation && it == MigrationState.Confirming) throw IllegalStateException("No initial window")
        })
        session.onLoad()
        session.onActivityCreated(true, false, true)
        session.onMainResumed()
        assertEquals(MigrationState.Closed, session.state)
        failConfirmation = false
        session.onMainResumed()
        session.start()
        assertEquals(MigrationState.Closed, session.state)
        assertTrue(h.runtime.workers.isEmpty())
        assertEquals(0, h.host.calls)
    }

    @Test fun failedProgressAndFailureModalCannotLeaveAnUnprotectedRetryWindow() {
        val h = Harness()
        val session = LinovelibMigrationSession(h.host, h.runtime, {}, {
            if (it is MigrationState.Running || it is MigrationState.RetryableFailure) throw IllegalStateException("No window")
        })
        session.onLoad()
        session.onActivityCreated(true, false, true)
        session.onMainResumed()
        session.start()
        assertEquals(MigrationState.Closed, session.state)
        assertTrue(h.runtime.workers.isEmpty())
        session.onMainResumed()
        session.start()
        assertEquals(0, h.host.calls)
    }

    @Test fun foregroundRebindDisplayFailureRevokesAdmissionButPreservesInFlightStorageTruth() {
        listOf(MigrationAttemptResult.Completed, MigrationAttemptResult.NotCommitted(MigrationReason.NotReady, true)).forEach { outcome ->
            val h = Harness().apply { open() }
            h.host.execute = { admission, _ ->
                h.session.onForegroundDisplayFailure()
                assertTrue(h.session.state is MigrationState.Running)
                assertThrows(MigrationAdmissionException::class.java) { admission() }
                outcome
            }
            h.session.start()
            h.settle()
            assertEquals(if (outcome == MigrationAttemptResult.Completed) MigrationState.Completed
                         else MigrationState.Stopped(MigrationReason.SessionChanged), h.session.state)
            h.session.start()
            assertEquals(1, h.host.calls)
        }
    }

    @Test fun rejectedSchedulingIsKnownNotStartedAndCanRetryFreshly() {
        val h = Harness().apply { open() }
        h.runtime.rejectExecution = true
        h.session.start()
        assertEquals(MigrationState.RetryableFailure(MigrationReason.SchedulingFailure), h.session.state)
        h.runtime.rejectExecution = false
        h.session.start()
        h.settle()
        assertEquals(1, h.host.calls)
        assertEquals(MigrationState.Completed, h.session.state)
    }

    @Test fun renderingFailureAfterCommitCannotTurnCompletionIntoRetry() {
        val h = Harness()
        val session = LinovelibMigrationSession(h.host, h.runtime, {}, {
            if (it == MigrationState.Completed) throw IllegalStateException("Page gone")
        })
        session.onLoad()
        session.onActivityCreated(true, false, true)
        session.onMainResumed()
        session.start()
        h.runtime.work()
        h.runtime.deliver()
        assertEquals(MigrationState.Completed, session.state)
        session.start()
        assertEquals(1, h.host.calls)
    }
}
