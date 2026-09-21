package io.nightfish.lightnovelreader.plugin.linovelib.source

import android.annotation.TargetApi
import android.icu.text.Transliterator
import android.os.Build

internal object LinovelibChineseConverter {
    private val simplifiedToTraditional: Any? by lazy {
        createTransliterator("Simplified-Traditional")
    }
    private val traditionalToSimplified: Any? by lazy {
        createTransliterator("Traditional-Simplified")
    }

    fun toTraditional(text: String): String = transliterate(simplifiedToTraditional, text)

    fun toSimplified(text: String): String = transliterate(traditionalToSimplified, text)

    private fun createTransliterator(id: String): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { Api29.create(id) }.getOrNull()
    }

    private fun transliterate(transliterator: Any?, text: String): String {
        if (transliterator == null || text.isEmpty()) return text
        return synchronized(transliterator) {
            Api29.transliterate(transliterator, text)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun create(id: String): Any = Transliterator.getInstance(id)

        fun transliterate(transliterator: Any, text: String): String =
            (transliterator as Transliterator).transliterate(text)
    }
}
