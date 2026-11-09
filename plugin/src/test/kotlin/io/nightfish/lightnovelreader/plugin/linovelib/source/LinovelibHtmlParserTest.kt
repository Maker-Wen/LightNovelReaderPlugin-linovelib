package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URISyntaxException
import java.time.LocalDate

class LinovelibHtmlParserTest {
    @Test
    fun `detail results retain raw links and do not depend on previous parses`() {
        val html = """
            <h1 class="book-title">Book</h1>
            <span class="authorname"><a href="/author/1.html">Author</a></span>
            <span class="tag-small-group"><em class="tag-small red"><a href="/tag/1.html">Tag</a></em></span>
        """
        val first = parser.parseBookInformation("1", html)
        parser.parseBookInformation("2", html.replace("/1.html", "/2.html"))
        assertEquals(first, parser.parseBookInformation("1", html))
        assertEquals(first, LinovelibHtmlParser().parseBookInformation("1", html))
        assertEquals("Author", first.author)
        assertEquals("${LinovelibUrls.HOST}/author/1.html", first.authorUrl)
        assertEquals(mapOf("Tag" to "${LinovelibUrls.HOST}/tag/1.html"), first.relatedLinks)
    }

    private val parser = LinovelibHtmlParser()

    @Test
    fun expandedRowsUseActualSupportedMoreLinks() {
        fun module(url: String) = """
            <section class="module">
              <header class="module-header"><h2 class="module-title">Books</h2>
                <a class="module-header-btn" href="$url">更多</a>
              </header>
              <a class="book-layout" href="/novel/1.html"><h4 class="book-title">Book</h4></a>
            </section>
        """.trimIndent()

        assertEquals(
            "https://www.bilinovel.net/wenku/postdate_0_0_0_0_0_0_0_1_0.html",
            parser.parseExploreRows(module("/wenku/postdate_0_0_0_0_0_0_0_1_0.html")).single().expandedUrl
        )
        listOf("/sort/", "/gocomic?0", "https://example.com/wenku/", "javascript:alert(1)").forEach { url ->
            assertEquals(null, parser.parseExploreRows(module(url)).single().expandedUrl)
        }
        val ranking = """
            <div class="category-list">
              <a class="fl-header" href="/top/monthvisit/1.html"><div class="fl-header-l">月榜</div></a>
              <ul class="fl-content"><li><a href="/novel/1.html">Book</a></li></ul>
            </div>
        """.trimIndent()
        assertEquals("https://www.bilinovel.net/top/monthvisit/1.html", parser.parseRankingRows(ranking).single().expandedUrl)
    }

    @Test
    fun expandedRowsSelectTheFirstSupportedLinkAfterRejectedCandidates() {
        val html = """
            <section class="module">
              <header class="module-header"><h2 class="module-title">Books</h2>
                <a class="module-header-btn" href="https://example.com/wenku/foreign.html">Foreign host</a>
                <a class="module-header-btn" href="/sort/">Unsupported path</a>
                <a class="module-header-btn" href="/wenku/first.html">First supported</a>
                <a class="module-header-btn" href="/top/monthvisit/1.html">Later supported</a>
              </header>
              ${exploreBook(1)}
            </section>
        """.trimIndent()

        assertEquals("${LinovelibUrls.HOST}/wenku/first.html", parser.parseExploreRows(html).single().expandedUrl)
    }

    @Test
    fun expandedRowsParseTheHostOnlyWhenThereIsALinkCandidate() {
        val invalidHostParser = LinovelibHtmlParser("https://invalid host")
        val html = """
            <section class="module"><header class="module-header"><h2 class="module-title">Books</h2>
              %s
            </header>${exploreBook(1)}</section>
        """.trimIndent()

        assertEquals(null, invalidHostParser.parseExploreRows(html.format("")).single().expandedUrl)
        val failure = runCatching {
            invalidHostParser.parseExploreRows(html.format(
                """<a class="module-header-btn" href="https://example.com/wenku/first.html">More</a>"""
            ))
        }.exceptionOrNull()
        assertTrue(failure is URISyntaxException)
    }

    @Test
    fun listResultsAreNotTruncatedBeforeSearchOrPagination() {
        val html = (1..40).joinToString(prefix = "<ol class=\"book-ol\">", postfix = "</ol>") { id ->
            "<li class=\"book-li\"><a class=\"book-layout\" href=\"/novel/$id.html\"><h4 class=\"book-title\">Book $id</h4></a></li>"
        }
        val row = requireNotNull(parser.parseListPage(html))
        assertEquals((1..40).map(Int::toString), row.books.map { it.id })
    }

    @Test
    fun exploreAndRankingParseAllValidRowsAndBooksInOriginalOrder() {
        val invalidBooks = """<a class="book-layout" href="/novel/999.html"> </a><a class="book-layout" href="/invalid.html">Invalid</a>"""
        fun books(index: Int) = (1..40).joinToString("") { offset -> exploreBook(index * 100 + offset) } +
            exploreBook(index * 100 + 1, "Duplicate title") + invalidBooks
        fun module(title: String, books: String, index: Int = 0) = """
            <section class="module"><header class="module-header"><h2 class="module-title">$title</h2>
            <a class="module-header-btn" href="/wenku/row$index.html">More</a></header>$books</section>
        """
        fun ranking(title: String, books: String, index: Int = 0) = """
            <div class="category-list"><a class="fl-header" href="/top/row$index/1.html"><span class="fl-header-l">$title</span></a>
            <div class="fl-content">$books</div></div>
        """
        val exploreRows = parser.parseExploreRows(
            module(" ", exploreBook(1)) + module("Empty", invalidBooks) +
                (1..9).joinToString("") { module("Row $it", books(it), it) }
        )
        val rankingRows = parser.parseRankingRows(
            ranking(" ", exploreBook(1)) + ranking("Empty", invalidBooks) +
                (1..9).joinToString("") { ranking("Row $it", books(it), it) }
        )

        listOf(exploreRows, rankingRows).forEach { rows ->
            assertEquals((1..9).map { "Row $it" }, rows.map { it.title })
            rows.forEachIndexed { index, row ->
                val ids = (1..40).map { "${(index + 1) * 100 + it}" }
                assertEquals(ids, row.books.map { it.id })
                assertEquals(ids.map { "Book $it" }, row.books.map { it.title })
            }
        }
        assertEquals((1..9).map { "${LinovelibUrls.HOST}/wenku/row$it.html" }, exploreRows.map { it.expandedUrl })
        assertEquals((1..9).map { "${LinovelibUrls.HOST}/top/row$it/1.html" }, rankingRows.map { it.expandedUrl })
    }

    @Test
    fun exploreRowsIgnorePageLinksWithoutValidModules() {
        val links = (1..40).joinToString("") { exploreBook(it) }
        listOf(
            links,
            """<section class="module"><h2 class="module-title">Empty</h2></section>""" + links,
            """<section class="module">$links</section>"""
        ).forEach { html ->
            assertTrue(parser.parseExploreRows(html).isEmpty())
        }
    }

    private fun exploreBook(id: Int, title: String = "Book $id") =
        """<a class="book-layout" href="/novel/$id.html"><span class="book-title">$title</span></a>"""

    @Test
    fun parseBookInformationRecognizesSimplifiedAndTraditionalCompletionStatus() {
        for ((status, complete) in listOf(
            "完结" to true, "完結" to true,
            "连载中" to false, "連載中" to false, "" to false
        )) {
            val book = parser.parseBookInformation("1", """
                <div class="book-detail-info">
                    <h1 class="book-title">Example</h1>
                    <p class="book-meta book-layout-inline">10万字 | $status</p>
                </div>
            """.trimIndent())
            assertEquals(status, complete, book.isComplete)
        }
    }

    @Test
    fun parseBookInformationReadsCapturedCompletedNovelPage() {
        val book = parser.parseBookInformation("1", capturedBookPage("1"))

        assertEquals("1", book.id)
        assertEquals("恶魔高校DxD", book.title)
        assertEquals("High School DxD", book.subtitle)
        assertEquals("https://www.bilinovel.net/files/article/image/0/1/1s.jpg", book.coverUrl)
        assertEquals("石踏一荣", book.author)
        assertEquals(6, book.description.lines().size)
        assertEquals(listOf("校园", "奇幻", "战斗", "后宫", "青梅竹马", "人外"), book.tags)
        assertEquals("富士见文库", book.publishingHouse)
        assertEquals(3_982_000, book.wordCount)
        assertEquals(LocalDate.of(2024, 2, 25), book.lastUpdated)
        assertTrue(book.isComplete)
        assertEquals("https://www.bilinovel.net/authorarticle/石踏一荣.html", book.authorUrl)
        assertEquals(
            "https://www.bilinovel.net/wenku/lastupdate_63_0_0_0_0_0_0_1_0.html",
            book.relatedLinks["校园"]
        )
        assertEquals("https://www.bilinovel.net/wenku/fujimibunko/1.html", book.relatedLinks["富士见文库"])
    }

    @Test
    fun parserResolvesRelativeUrlsFromDefaultSite() {
        val book = parser.parseBookInformation(
            "1804",
            """
                <h1 class="book-title">World Series</h1>
                <meta property="og:image" content="/files/article/image/1/1804/1804s.jpg" />
            """.trimIndent()
        )

        assertEquals(
            "https://www.bilinovel.net/files/article/image/1/1804/1804s.jpg",
            book.coverUrl
        )
    }

    @Test
    fun parseCatalogReadsVolumesAndSkipsJavascriptChapterLinks() {
        val html = """
            <div id="volumes">
              <div class="catalog-volume"><ul class="volume-chapters">
                <li class="chapter-bar chapter-li"><a href="/novel/1/vol_1.html"><h3>Volume 1</h3></a></li>
                <li class="chapter-li jsChapter"><a href="/novel/1/108523.html"><span class="chapter-index">Illustrations</span></a></li>
                <li class="chapter-li jsChapter"><a href="javascript:cid(1)"><span class="chapter-index">Life.0</span></a></li>
              </ul></div>
              <div class="catalog-volume"><ul class="volume-chapters">
                <li class="chapter-bar chapter-li"><a href="/novel/1/vol_9.html"><h3>Volume 2</h3></a></li>
                <li class="chapter-li jsChapter"><a href="/novel/1/10.html"><span class="chapter-index">Life.0</span></a></li>
              </ul></div>
            </div>
        """.trimIndent()

        val catalog = parser.parseCatalog("1", html)

        assertEquals("1", catalog.bookId)
        assertEquals(2, catalog.volumes.size)
        assertEquals("Volume 1", catalog.volumes[0].title)
        assertEquals(listOf(ParsedChapter("108523", "Illustrations")), catalog.volumes[0].chapters)
        assertEquals("Volume 2", catalog.volumes[1].title)
        assertEquals(listOf(ParsedChapter("10", "Life.0")), catalog.volumes[1].chapters)
    }

    @Test
    fun parseChapterContentReadsTextImagesAndNavigation() {
        val html = """
            <script>
              var ReadParams={url_previous:'/novel/1/225507.html',url_next:'/novel/1/115455.html',chapterid:'225508'}
            </script>
            <h1 id="atitle">Afterword</h1>
            <div id="acontent">
              <p>Hello again.</p>
              <br>
              <p><img src="/files/image.jpg" /></p>
              <div id="hidden-images">
                <img data-src="/files/hidden-1.jpg" />
                <img data-src="/files/hidden-2.jpg" />
              </div>
              <p>Short notes follow.</p>
              <center style="color:red;">${"\u624b\u6a5f\u7248\u9801\u9762"} warning</center>
              <div class="cgo">ad</div>
            </div>
        """.trimIndent()

        val chapter = parser.parseChapterContent("225508", html, LinovelibUrls.chapter(LinovelibUrls.HOST, "1", "225508"))

        assertEquals("225508", chapter.id)
        assertEquals("Afterword", chapter.title)
        assertEquals("225507", chapter.previousChapterId)
        assertEquals("115455", chapter.nextChapterId)
        assertEquals("/novel/1/115455.html", chapter.nextPageUrl)
        assertEquals(
            listOf(
                ParsedContentBlock.Text("Hello again."),
                ParsedContentBlock.Image("https://www.bilinovel.net/files/image.jpg"),
                ParsedContentBlock.Image("https://www.bilinovel.net/files/hidden-1.jpg"),
                ParsedContentBlock.Image("https://www.bilinovel.net/files/hidden-2.jpg"),
                ParsedContentBlock.Text("Short notes follow.")
            ),
            chapter.blocks
        )
    }

    @Test
    fun parseChapterContentKeepsTextAroundInlineImage() {
        val html = """
            <h1 id="atitle">Mixed content</h1>
            <div id="acontent">
              <p>Before image.<img src="/files/inline.jpg" />After image.</p>
            </div>
        """.trimIndent()

        val chapter = parser.parseChapterContent("12", html, LinovelibUrls.chapter(LinovelibUrls.HOST, "1", "12"))

        assertEquals(
            listOf(
                ParsedContentBlock.Text("Before image."),
                ParsedContentBlock.Image("https://www.bilinovel.net/files/inline.jpg"),
                ParsedContentBlock.Text("After image.")
            ),
            chapter.blocks
        )
    }

    @Test
    fun parseChapterContentReadsPagedNextUrlAsSameChapter() {
        val html = """
            <script>
              var ReadParams={url_previous:"/novel/1/108523.html",url_next:"/novel/1/2_2.html",chapterid:'2',page:'1'}
            </script>
            <h1 id="atitle">Life.0</h1>
            <div id="acontent"><p>First page.</p></div>
        """.trimIndent()

        val chapter = parser.parseChapterContent("2", html, LinovelibUrls.chapter(LinovelibUrls.HOST, "1", "2"))

        assertEquals("2", chapter.nextChapterId)
        assertEquals("/novel/1/2_2.html", chapter.nextPageUrl)
    }

    @Test
    fun parseChapterContentKeepsCapturedTextAfterPunctuationBeforeLoadFailureMarker() {
        // Exact paragraph extracts and raw-response provenance are in html/load-failure-fixtures.txt.
        val cases = listOf(
            "342689_3" to "接着笛音突然停止，空良也停下脚步。他来到每天都会经过的昏暗高架桥下。那里摆着一台自助证件照机，而高架桥旁还有一栋二楼是居酒屋的小楼房。明明是每天都会见到的风景，但从照",
            "342690_3" to "「我和爱海妹妹，其实是通过友梨介绍才变成朋友的。大约是两年前的事吧。」叶月用平静的声音说道。「当时友梨不小心说溜了嘴，告诉了她我在当偶像。那时我还不红，但她却像自己的事一样开心，对我说『小薰姐姐好厉害』。我以恐怖电影主题曲的演唱者出道的时候，她一直重复听着那",
            "342690_6" to "「浦岛女」用颤抖的双手，做"
        )
        cases.forEach { (pageId, expectedText) ->
            val html = javaClass.getResource("/html/chapter-$pageId-load-failure.html")!!.readText()
            val chapter = parser.parseChapterContent(
                pageId.substringBefore('_'), html, "${LinovelibUrls.HOST}/novel/5473/$pageId.html"
            )
            assertEquals(pageId, listOf(ParsedContentBlock.Text(expectedText)), chapter.blocks)
        }
    }

    @Test
    fun parseChapterContentRemovesCapturedLoadFailureTemplateTail() {
        val html = javaClass.getResource("/html/chapter-342690-load-failure.html")!!.readText()
        val chapter = parser.parseChapterContent(
            "342690", html, "${LinovelibUrls.HOST}/novel/5473/342690.html"
        )
        assertEquals(
            listOf(ParsedContentBlock.Text("而那津美并未责怪空良，只是将爱海昨日的状况一一记在笔记本上")),
            chapter.blocks
        )
    }

    @Test
    fun parseChapterContentRestoresBilinovelParagraphOrder() {
        val shuffled = (0..19).toList() + listOf(22, 24, 25, 21, 29, 27, 20, 28, 23, 26)
        val html = shuffled.joinToString(
            prefix = "<div id=\"acontent\">",
            postfix = "</div>"
        ) { "<p>Paragraph $it</p>" }

        val chapter = parser.parseChapterContent(
            chapterId = "61960",
            html = html,
            baseUrl = LinovelibUrls.chapter(LinovelibUrls.HOST, "1", "61960")
        )

        assertEquals(
            (0..29).map { ParsedContentBlock.Text("Paragraph $it") },
            chapter.blocks
        )
    }

    @Test
    fun parserPreservesOriginalTextInChapterAndSearchResults() {
        val chapter = parser.parseChapterContent(
            "10",
            """
                <h1 id="atitle">章節标题</h1>
                <div id="acontent"><p>正文內容与原文</p></div>
            """.trimIndent(),
            LinovelibUrls.chapter(LinovelibUrls.HOST, "1", "10")
        )
        val searchRow = requireNotNull(parser.parseListPage(
            """
                <ol class="book-ol"><li class="book-li">
                  <a class="book-layout" href="/novel/10.html">
                    <h4 class="book-title">原始書名</h4>
                    <p class="book-author">Author 作家名字</p>
                  </a>
                </li></ol>
            """.trimIndent()
        ))

        assertEquals("章節标题", chapter.title)
        assertEquals(
            listOf(ParsedContentBlock.Text("正文內容与原文")),
            chapter.blocks
        )
        assertEquals("原始書名", searchRow.books.single().title)
        assertEquals("Author 作家名字", searchRow.books.single().author)
    }

    @Test
    fun listBookMetadataPreservesEnglishAuthorTextWithoutGuessingMissingAuthors() {
        val row = requireNotNull(parser.parseListPage(
            """
                <ol class="book-ol">
                  <li class="book-li"><a class="book-layout" href="/novel/1.html">The Author Returns</a></li>
                  <li class="book-li"><a class="book-layout" href="/novel/2.html">
                    <h4 class="book-title">Second Book</h4><p class="book-author">Author: Writer</p>
                  </a></li>
                  <li class="book-li"><a class="book-layout" href="/novel/3.html">
                    <h4 class="book-title">Third Book</h4><p class="book-author"></p><p>Author: Unrelated text</p>
                  </a></li>
                </ol>
            """.trimIndent()
        ))

        assertEquals(listOf("The Author Returns", "Second Book", "Third Book"), row.books.map { it.title })
        assertEquals(listOf("", "Author: Writer", ""), row.books.map { it.author })
    }

    @Test
    fun authorNodesPreserveLiteralPrefixesAndPunctuation() {
        listOf("作者: 作家名字", "作者：作家名字", "Author: Writer", ": 作家名字", "：作家名字", "作家名字")
            .forEach { author ->
                val listRow = requireNotNull(parser.parseListPage(
                    """<a class="book-layout" href="/novel/1.html">
                      <h4 class="book-title">書名</h4><p class="book-author">$author</p>
                    </a>"""
                ))
                val exploreRow = parser.parseExploreRows(
                    """<section class="module"><h2 class="module-title">Books</h2>
                      <a class="module-slide-a" href="/novel/1.html"><h4 class="module-slide-caption">書名</h4>
                        <p class="module-slide-author"><span class="clip">作者：</span><span class="gray">$author</span></p>
                      </a>
                    </section>"""
                ).single()
                assertEquals(author, listRow.books.single().author)
                assertEquals(author, exploreRow.books.single().author)
            }
    }

    @Test
    fun authorsKeepWhitespaceNotNormalizedByTheirDomTextAccessors() {
        // Synthetic boundary case: these spaces survive Jsoup text extraction but Kotlin trim removes them.
        val author = "\u2003作家名字\u2003"
        val row = requireNotNull(parser.parseListPage(
            """<a class="book-layout" href="/novel/1.html">
              <h4 class="book-title">書名</h4><p class="book-author">$author</p>
            </a>"""
        ))
        assertEquals(author, row.books.single().author)
    }

    @Test
    fun missingAuthorNodesDoNotUseModuleLabelsAsNames() {
        val row = parser.parseExploreRows(
            """<section class="module"><h2 class="module-title">Books</h2>
              <a class="module-slide-a" href="/novel/1.html"><h4 class="module-slide-caption">書名</h4>
                <p class="module-slide-author"><span class="clip">作者：Unrelated label</span></p>
              </a>
            </section>"""
        ).single()
        assertEquals("", row.books.single().author)
    }

    @Test
    fun parseExploreRowsReadsHomePageModules() {
        val html = """
            <section class="module">
              <header class="module-header">
                <h2 class="module-title">Hot Books</h2>
              </header>
              <a class="module-slide-a" href="/novel/1.html">
                <img data-src="/files/article/image/0/1/1s.jpg" alt="High School DxD" />
                <div class="module-slide-caption">High School DxD</div>
                <div class="module-slide-author"><span class="clip">作者：</span><span class="gray">Ichiei Ishibumi</span></div>
              </a>
              <a class="book-layout" href="/novel/2.html">
                <img src="/files/article/image/0/2/2s.jpg" />
                <div class="book-title">Another Novel</div>
                <div class="book-author"><svg class="icon icon-human" role="img" title="Tester"><use xlink:href="#icon-human"></use></svg>Tester</div>
              </a>
            </section>
        """.trimIndent()

        val rows = parser.parseExploreRows(html)

        assertEquals(1, rows.size)
        assertEquals("Hot Books", rows[0].title)
        assertEquals(
            listOf(
                ParsedExploreBook("1", "High School DxD", "Ichiei Ishibumi", "https://www.bilinovel.net/files/article/image/0/1/1s.jpg"),
                ParsedExploreBook("2", "Another Novel", "Tester", "https://www.bilinovel.net/files/article/image/0/2/2s.jpg")
            ),
            rows[0].books
        )
    }

    @Test
    fun parseBookInformationReadsCapturedOngoingNovelPage() {
        val book = parser.parseBookInformation("3095", capturedBookPage("3095"))

        assertEquals("3095", book.id)
        assertEquals("败北女角太多了！", book.title)
        assertEquals("败犬女主太多了！", book.subtitle)
        assertEquals("https://www.bilinovel.net/files/article/image/3/3095/3095s.jpg", book.coverUrl)
        assertEquals("雨森焚火", book.author)
        assertEquals(8, book.description.lines().size)
        assertEquals(listOf("校园", "欢乐向", "青春", "恋爱", "后宫", "妹妹"), book.tags)
        assertEquals("小学馆", book.publishingHouse)
        assertEquals(1_095_000, book.wordCount)
        assertEquals(LocalDate.of(2026, 10, 2), book.lastUpdated)
        assertFalse(book.isComplete)
        assertEquals("https://www.bilinovel.net/authorarticle/雨森焚火.html", book.authorUrl)
        assertEquals("https://www.bilinovel.net/wenku/shogakukan/1.html", book.relatedLinks["小学馆"])
    }

    private fun capturedBookPage(id: String): String =
        javaClass.getResource("/html/book-$id.html")!!.readText()

    @Test
    fun parseRankingRowsReadsLinkTitlesWithCssRankingNumbers() {
        val html = """
            <style>.fl-content-li::before { content: counter(bookrank) "."; }</style>
            <div class="category-list">
              <a class="fl-header"><div class="fl-header-l">Monthly</div></a>
              <ul class="fl-content">
                <li class="fl-content-li"><a href="/novel/10.html">Ranked Book</a></li>
                <li class="fl-content-li"><a href="/novel/11.html">Second Book</a></li>
              </ul>
            </div>
        """.trimIndent()

        val rows = parser.parseRankingRows(html)

        assertEquals(1, rows.size)
        assertEquals("Monthly", rows[0].title)
        assertEquals(
            listOf(
                ParsedExploreBook("10", "Ranked Book", "", "https://www.bilinovel.net/files/article/image/0/10/10s.jpg"),
                ParsedExploreBook("11", "Second Book", "", "https://www.bilinovel.net/files/article/image/0/11/11s.jpg")
            ),
            rows[0].books
        )
    }

    @Test
    fun parseRankingRowsBuildsCoverPathForFourDigitBookId() {
        val html = """
            <div class="category-list">
              <a class="fl-header">
                <div class="fl-header-l">Monthly</div>
                <img src="/files/article/image/3/3768/3768s.jpg" />
              </a>
              <ul class="fl-content">
                <li><a href="/novel/3768.html">Ranked Book</a></li>
              </ul>
            </div>
        """.trimIndent()

        val book = parser.parseRankingRows(html).single().books.single()

        assertEquals("https://www.bilinovel.net/files/article/image/3/3768/3768s.jpg", book.coverUrl)
    }

    @Test
    fun parseListPageReadsCompleteBookList() {
        val html = """
            <ol class="book-ol">
              <li class="book-li">
                <a href="/novel/20.html" class="book-layout">
                  <img data-src="/files/article/image/0/20/20s.jpg" />
                  <h4 class="book-title">Complete Book</h4>
                  <p class="book-author"><svg class="icon icon-human"><title>作者</title><use xlink:href="#icon-human"></use></svg>Writer</p>
                </a>
              </li>
            </ol>
        """.trimIndent()

        val row = requireNotNull(parser.parseListPage(html))

        assertEquals(
            listOf(ParsedExploreBook("20", "Complete Book", "Writer", "https://www.bilinovel.net/files/article/image/0/20/20s.jpg")),
            row.books
        )
    }

    @Test
    fun parseListPagePreservesCapturedRankingTitleAndSynopsis() {
        val html = requireNotNull(javaClass.getResource("/html/list-title-3343.html")).readText()

        val book = requireNotNull(parser.parseListPage(html)).books.single()

        assertEquals("3343", book.id)
        assertEquals("妹妹是不能当女友的，可是……", book.title)
        assertEquals("这是一段无论发生什么最终都“绝对”会与世界上最可爱的妹妹结为连理的恋爱喜剧！ 樱羽雪季是是世界上......", book.description)
        assertEquals(0, book.wordCount)
        assertFalse(book.isComplete)
    }

    @Test
    fun parseListPageReadsSearchAndLibraryMetadataFromTheirOwnBadges() {
        val html = """
            <ol class="book-ol">
              <li class="book-li"><a class="book-layout" href="/novel/1.html">
                <h4 class="book-title">Search <span class="hot">Book</span></h4>
                <p class="book-desc">Search synopsis<br>Second line</p>
                <div class="book-meta-r"><span class="tag-small-group">
                  <em class="tag-small red">奇幻 冒险</em><em class="tag-small yellow">角川文库</em>
                  <em class="tag-small gray">完结</em>
                </span></div>
              </a></li>
              <li class="book-li"><a class="book-layout" href="/novel/2.html">
                <h4 class="book-title">Library Book</h4>
                <p class="book-desc">Library synopsis</p>
                <div class="book-meta-r"><span class="tag-small-group">
                  <em class="tag-small yellow">科幻  战斗</em><em class="tag-small red">连载</em>
                  <em class="tag-small blue">38.5万</em>
                </span></div>
              </a></li>
            </ol>
        """.trimIndent()

        val (search, library) = requireNotNull(parser.parseListPage(html)).books

        assertEquals("Search Book", search.title)
        assertEquals("Search synopsis\nSecond line", search.description)
        assertEquals(listOf("奇幻", "冒险"), search.tags)
        assertEquals("角川文库", search.publishingHouse)
        assertEquals(0, search.wordCount)
        assertTrue(search.isComplete)
        assertEquals("Library synopsis", library.description)
        assertEquals(listOf("科幻", "战斗"), library.tags)
        assertEquals("", library.publishingHouse)
        assertEquals(385_000, library.wordCount)
        assertFalse(library.isComplete)
    }

    @Test
    fun parseRankingRowsPreservesOriginalTraditionalTitle() {
        val rankingHtml = """
            <div class="category-list">
              <a class="fl-header"><div class="fl-header-l">Monthly</div></a>
              <ul class="fl-content">
                <li><a href="/novel/8.html">${"\u6b61\u8fce\u4f86\u5230\u5be6\u529b\u81f3\u4e0a\u4e3b\u7fa9\u7684\u6559\u5ba4"}</a></li>
              </ul>
            </div>
        """.trimIndent()
        val parsedRows = parser.parseRankingRows(rankingHtml)
        val book = parsedRows.single().books.single()
        assertEquals("8", book.id)
        assertEquals("歡迎來到實力至上主義的教室", book.title)
    }
}
