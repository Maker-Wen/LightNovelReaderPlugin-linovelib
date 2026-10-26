package io.nightfish.lightnovelreader.plugin.linovelib.source

internal object LinovelibSearchGuard {
    private val JS_COOKIE = Regex("""(?:^|[\"'])jieqiSearchJs=([^;\"']+)""")

    fun extractJsToken(body: String): String =
        JS_COOKIE.find(body)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    fun guardCookies(jsToken: String, cssToken: String): Map<String, String> = mapOf(
        "night" to "0",
        "jieqiSearchJs" to jsToken,
        "jieqiSearchCss" to cssToken
    )
}
