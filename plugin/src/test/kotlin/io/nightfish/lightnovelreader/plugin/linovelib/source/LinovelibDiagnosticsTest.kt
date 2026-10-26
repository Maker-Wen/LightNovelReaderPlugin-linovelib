package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibDiagnosticsTest {
    @Test
    fun infoFormatsFieldsAndEscapesLineBreaks() {
        val entries = mutableListOf<DiagnosticEntry>()
        val diagnostics = LinovelibDiagnostics { _, tag, message, throwable ->
            entries += DiagnosticEntry(tag, message, throwable)
        }

        diagnostics.info(
            "HTTP_OK",
            linkedMapOf(
                "status" to 200,
                "url" to "https://tw.linovelib.com/a\nb",
                "empty" to null
            )
        )

        val entry = entries.single()
        assertEquals("LinovelibDiagnostic", entry.tag)
        assertEquals("HTTP_OK status=200 url=https://tw.linovelib.com/a\\nb empty=null", entry.message)
        assertEquals(null, entry.throwable)
    }

    @Test
    fun detectsTraditionalAndSimplifiedFailureMarkers() {
        val diagnostics = LinovelibDiagnostics { _, _, _, _ -> }

        assertTrue(diagnostics.hasLoadFailure("前文內容加載失敗後文"))
        assertTrue(diagnostics.hasLoadFailure("前文内容加载失败后文"))
        assertFalse(diagnostics.hasLoadFailure("完整內容"))
    }

    @Test
    fun errorIncludesFieldsAndThrowable() {
        val entries = mutableListOf<DiagnosticEntry>()
        val diagnostics = LinovelibDiagnostics { _, tag, message, throwable ->
            entries += DiagnosticEntry(tag, message, throwable)
        }
        val failure = IllegalStateException("network failed")

        diagnostics.error("HTTP_ERROR", failure, mapOf("url" to "https://tw.linovelib.com"))

        val entry = entries.single()
        assertEquals("HTTP_ERROR url=https://tw.linovelib.com", entry.message)
        assertEquals(failure, entry.throwable)
    }

    @Test
    fun successfulHttpStatusAcceptsOnlyTwoAndThreeHundreds() {
        val diagnostics = LinovelibDiagnostics { _, _, _, _ -> }

        assertTrue(diagnostics.isSuccessfulHttpStatus(200))
        assertTrue(diagnostics.isSuccessfulHttpStatus(302))
        assertFalse(diagnostics.isSuccessfulHttpStatus(403))
        assertFalse(diagnostics.isSuccessfulHttpStatus(500))
    }

    private data class DiagnosticEntry(
        val tag: String,
        val message: String,
        val throwable: Throwable?
    )
}
