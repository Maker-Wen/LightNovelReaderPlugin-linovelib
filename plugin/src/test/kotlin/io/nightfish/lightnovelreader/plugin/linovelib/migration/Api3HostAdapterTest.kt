package io.nightfish.lightnovelreader.plugin.linovelib.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

class Api3HostAdapterTest {
    @Test fun missingOrUnreadableReceiptCannotAuthorizeRetryAfterUnknownTransactionExit() {
        val failure = MigrationAttemptResult.NotCommitted(MigrationReason.StorageFailure, true)
        assertEquals(MigrationAttemptResult.RestartRequired,
            migrationResultAfterFailure(MigrationTransactionExit.Unknown, MigrationCompletion.Unknown, failure))
        assertEquals(MigrationAttemptResult.Completed,
            migrationResultAfterFailure(MigrationTransactionExit.Unknown, MigrationCompletion.Present, failure))
    }

    @Test fun confirmedTransactionFactsTakePrecedenceOverReceiptLookupFailures() {
        val failure = MigrationAttemptResult.NotCommitted(MigrationReason.NotReady, true)
        listOf(MigrationTransactionExit.NotStarted, MigrationTransactionExit.RolledBack).forEach { exit ->
            assertEquals(failure, migrationResultAfterFailure(exit, MigrationCompletion.Unknown, failure))
        }
    }

    @Test fun missingFieldHelperReturnsOfficialFalseDefaults() {
        // This helper contract does not execute SQLite writes; device migration checks cover those.
        listOf("isFinished", "isFavorited", "sortReversed").forEach { field ->
            assertEquals(JsonPrimitive(false), Api3HostAdapter.missingFieldValue(field))
        }
        assertThrows(IllegalStateException::class.java) { Api3HostAdapter.missingFieldValue("totalReadTime") }
    }

    @Test fun dateAndTimeHelpersConvertSqlIntegerEncoding() {
        assertEquals("2026-10-12", Api3HostAdapter.dateFromSql(20261012))
        assertEquals(20261012, Api3HostAdapter.dateToSql("2026-10-12"))
        assertEquals("00:00:00", Api3HostAdapter.timeFromSql(0))
        assertEquals("09:05:01", Api3HostAdapter.timeFromSql(90501))
        assertEquals(90500, Api3HostAdapter.timeToSql("09:05"))
        assertEquals(90501, Api3HostAdapter.timeToSql("09:05:01.999"))
        assertThrows(IllegalArgumentException::class.java) { Api3HostAdapter.timeFromSql(126000) }
        assertThrows(IllegalArgumentException::class.java) { Api3HostAdapter.timeToSql("24:00") }
    }
}
