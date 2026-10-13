package io.nightfish.lightnovelreader.plugin.linovelib.migration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

class Api3HostAdapterTest {
    @Test fun admissionUsesHostApiContractInsteadOfReleaseNames() {
        assertTrue(Api3HostAdapter.supportsHost("indi.dmzz_yyhyy.lightnovelreader", 3))
    }

    @Test fun unrelatedPackagesAndDifferentApiContractsCannotEnableWrites() {
        assertFalse(Api3HostAdapter.supportsHost("another.reader", 3))
        assertFalse(Api3HostAdapter.supportsHost("indi.dmzz_yyhyy.lightnovelreader", 2))
        assertFalse(Api3HostAdapter.supportsHost("indi.dmzz_yyhyy.lightnovelreader", 4))
        assertFalse(Api3HostAdapter.supportsHost("indi.dmzz_yyhyy.lightnovelreader", -1))
    }

    @Test fun omittedOfficialCborDefaultsRemainFalseOnDatabaseWrite() {
        listOf("isFinished", "isFavorited", "sortReversed").forEach { field ->
            assertEquals(JsonPrimitive(false), Api3HostAdapter.missingFieldValue(field))
        }
        assertThrows(IllegalStateException::class.java) { Api3HostAdapter.missingFieldValue("totalReadTime") }
    }

    @Test fun sqlDatesAndTimesMatchHostWireWithoutAdditionalDesugaredTimeMethods() {
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
