package io.nightfish.lightnovelreader.plugin.linovelib.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationMessagesTest {
    @Test fun completionDescribesRecoverableDataWithoutClaimingEverythingWasRecovered() {
        assertEquals("已迁入可恢复的数据，请重新打开阅读器", MigrationMessages.completed)
    }

    @Test fun knownNotCommittedAndSkippedInputsNeverRequireManualDataRepairOrRestart() {
        MigrationReason.entries.forEach { reason ->
            val message = MigrationMessages.reason(reason)
            listOf("重新打开", "重启", "检查本地数据").forEach { instruction ->
                assertFalse("$reason must let the user continue: $message", instruction in message)
            }
        }
    }

    @Test fun untrustedCompletionExplainsDuplicateProtectionAndAllowsNormalReaderUse() {
        val message = MigrationMessages.reason(MigrationReason.UntrustedCompletion)
        assertTrue(message, "重复" in message)
        assertTrue(message, "继续使用" in message)
    }

    @Test fun storageFailureDoesNotImplyThatMergedDataWasSaved() {
        val message = MigrationMessages.reason(MigrationReason.StorageFailure)
        assertTrue(message, "未保存" in message)
        assertTrue(message, "继续使用" in message)
    }

    @Test fun onlyAnUnknownCommitAsksToReopenForVerification() {
        assertTrue("重新打开" in MigrationMessages.restartRequired)
        assertTrue("无法确认" in MigrationMessages.restartRequired)
        assertTrue("重复" in MigrationMessages.restartRequired)
    }
}
