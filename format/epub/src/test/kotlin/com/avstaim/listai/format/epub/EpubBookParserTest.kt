package com.avstaim.listai.format.epub

import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpubBookParserTest {
    private val parser = EpubBookParser()

    @Test fun `navigation promotes complete paragraph headings while preserving inline text and anchors`() = runBlocking {
        val content = xhtml("""
            <p id='part'><b>Part<br/>One</b></p><p id='chapter'><strong>Chapter I</strong></p>
            <p id='text'>Text 😀 survives.</p><p><b>Unlisted emphasis</b></p>
            <p id='different'>A longer paragraph.</p><p>Before <span id='inside'>Middle</span></p>
        """)
        val nav = xhtml("""<nav epub:type='toc'><ol><li><a href='chapter.xhtml#part'>Part One</a><ol>
            <li><a href='chapter.xhtml#chapter'>Chapter I</a></li></ol></li>
            <li><a href='chapter.xhtml#different'>A longer</a></li><li><a href='chapter.xhtml#inside'>Middle</a></li>
            </ol></nav>""")
        val document = parser.parse(book(chapters = mapOf("chapter" to content),
            extraManifest = "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>",
            extras = mapOf("OPS/nav.xhtml" to nav.toByteArray())).inputStream())
        val plain = parser.parse(book(chapters = mapOf("chapter" to content)).inputStream())
        assertEquals(1, document.parts.size)
        assertEquals(1, document.chapters.size)
        assertEquals("I", document.chapters.single().number)
        assertEquals(plain.locationsBySourceId, document.locationsBySourceId)
        assertEquals(plain.blocks.map { it.id to it.plainText() }, document.blocks.map { it.id to it.plainText() })
        assertTrue((document.blocks[0] as BookBlock.Heading).content.single() is InlineNode.Strong)
        assertTrue(document.blocks.drop(2).all { it is BookBlock.Paragraph })
    }

    @Test fun `publisher grouped CSS title and immediate citation form a complete part without losing source`() = runBlocking {
        val content = xhtml("""
            <link rel="stylesheet" href="titles.css"/>
            <span id='part'><div class='title1'><p>Книга вторая</p><p>Северный берег</p></div>
            <div class='cite'><p id='quote'><em>Синтетический эпиграф.</em></p><p class='text-author'>Автор эпиграфа</p></div>
            <p id='body'>Основной текст 😀.</p><div class='cite'><p>Поздняя обычная цитата.</p></div></span>
            <div style='font-weight:bold'><p>Обычный жирный текст</p></div>
            <div class='title1'><p>Книга третья</p><p>Остров</p></div><p>Основной текст.</p>
            <div class='cite'><p>Отложенная цитата.</p></div>
        """)
        val stylesheet = ".title1 { font-size:1.8em; font-weight:bold; page-break-after:avoid; }"
        fun archive(css: String) = book(chapters = mapOf("chapter" to content),
            extraManifest = "<item id='css' href='titles.css' media-type='text/css'/><item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>",
            spineAttributes = "toc='ncx'",
            extras = mapOf("OPS/titles.css" to css.toByteArray(), "OPS/toc.ncx" to """
                <ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint id='part'>
                <navLabel><text>Книга вторая Северный берег</text></navLabel><content src='chapter.xhtml#part'/>
                </navPoint></navMap></ncx>""".toByteArray()))
        val document = parser.parse(archive(stylesheet).inputStream())
        val part = document.parts.first()
        val blocks = document.blocks.associateBy { it.id }
        assertEquals(listOf("Книга вторая", "Северный берег"), part.headingBlockIds.map { blocks.getValue(it).plainText() })
        assertEquals(listOf("Синтетический эпиграф.", "Автор эпиграфа"), part.epigraphBlockIds.map { blocks.getValue(it).plainText() })
        assertEquals(2, document.parts.size)
        assertTrue(document.parts.last().epigraphBlockIds.isEmpty())
        assertTrue(document.chapters.isEmpty())
        val body = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "body"))
        assertTrue(blocks.getValue(body.blockId) is BookBlock.Paragraph)
        assertEquals(document.blocks.takeWhile { it.id != body.blockId }.sumOf { it.plainText().length + 1L }, body.fallbackGlobalOffset)
        val plain = parser.parse(archive(stylesheet.replace("page-break-after:avoid;", "")).inputStream())
        assertEquals(plain.locationsBySourceId, document.locationsBySourceId)
        assertEquals(plain.blocks.map { it.id to it.plainText() }, document.blocks.map { it.id to it.plainText() })
    }

    @Test fun `publisher title does not absorb nested block prose or citations`() = runBlocking {
        val content = xhtml("""
            <link rel='stylesheet' href='titles.css'/>
            <div class='title1'><p id='label'><span>Книга первая</span></p>
              <div class='cite'><p id='quote'><em>Цитата внутри контейнера.</em></p></div>
              <section><p id='section'>Текст вложенного раздела.</p></section>
              <div><p id='nested'>Текст вложенного блока 😀.</p></div>
            </div><p id='after'>Основной текст.</p>
        """)
        val css = ".title1 { font-size:1.8em; font-weight:bold; page-break-after:avoid; }"
        fun archive(styles: String) = book(chapters = mapOf("chapter" to content),
            extraManifest = "<item id='css' href='titles.css' media-type='text/css'/>",
            extras = mapOf("OPS/titles.css" to styles.toByteArray()))
        val document = parser.parse(archive(css).inputStream())
        fun block(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        assertEquals(listOf(block("label")), document.parts.single().headingBlockIds)
        for (source in listOf("quote", "section", "nested", "after")) {
            assertTrue(source, document.blocks.first { it.id == block(source) } is BookBlock.Paragraph)
        }
        val plain = parser.parse(archive(css.replace("page-break-after:avoid;", "")).inputStream())
        assertEquals(plain.locationsBySourceId, document.locationsBySourceId)
        assertEquals(plain.blocks.map { it.id to it.plainText() }, document.blocks.map { it.id to it.plainText() })
    }

    @Test fun `publisher styled titles retain explicit named division roles`() = runBlocking {
        val content = xhtml("""
            <link rel='stylesheet' href='titles.css'/>
            <section epub:type='part'><div class='title1'><p id='part'>Winter</p><p>The coast</p></div>
              <p id='body'>Body prose.</p><section epub:type='chapter'><div class='title2'><p id='chapter'>Arrival</p></div><p>Chapter prose.</p></section>
            </section>
            <section epub:type='epilogue'><div class='title1'><p id='ending'>Years later</p></div><p>Closing prose.</p></section>
            <section epub:type='part'><p>Unmarked text.</p><section><div class='title2'><p id='nested'>Departure</p></div><p>Nested prose.</p></section></section>
        """)
        val css = ".title1 { font-size:1.8em; font-weight:bold; page-break-after:avoid; } .title2 { font-size:1.5em; font-weight:bold; page-break-after:avoid; }"
        val document = parser.parse(book(chapters = mapOf("chapter" to content),
            extraManifest = "<item id='css' href='titles.css' media-type='text/css'/>",
            extras = mapOf("OPS/titles.css" to css.toByteArray())).inputStream())
        fun block(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        assertEquals(block("part"), document.parts.single().startBlockId)
        assertEquals(2, document.parts.single().headingBlockIds.size)
        assertEquals(listOf(block("chapter"), block("ending"), block("nested")), document.chapters.map { it.startBlockId })
        assertEquals(BookChapterKind.EPILOGUE, document.chapters[1].kind)
        assertTrue(document.blocks.first { it.id == block("body") } is BookBlock.Paragraph)
    }

    @Test fun `loose text before publisher title keeps its preceding source scope`() = runBlocking {
        val content = xhtml("""
            <link rel='stylesheet' href='titles.css'/>
            <span id='loose'>Loose prose 😀.</span><div class='title1'><p id='part'>Книга первая</p><p>Север</p></div>
            <p id='body'>Following prose.</p>
        """)
        val css = ".title1 { font-size:1.8em; font-weight:bold; page-break-after:avoid; }"
        fun archive(styles: String) = book(chapters = mapOf("chapter" to content),
            extraManifest = "<item id='css' href='titles.css' media-type='text/css'/>",
            extras = mapOf("OPS/titles.css" to styles.toByteArray()))
        val document = parser.parse(archive(css).inputStream())
        assertTrue(document.blocks.first() is BookBlock.Paragraph)
        assertEquals("Loose prose 😀.", document.blocks.first().plainText())
        assertEquals(document.blocks[1].id, document.parts.single().startBlockId)
        assertEquals(listOf(document.blocks[1].id, document.blocks[2].id), document.parts.single().headingBlockIds)
        val plain = parser.parse(archive(css.replace("page-break-after:avoid;", "")).inputStream())
        assertEquals(plain.locationsBySourceId, document.locationsBySourceId)
        assertEquals(plain.blocks.map { it.id to it.plainText() }, document.blocks.map { it.id to it.plainText() })
    }

    @Test fun `only leading publisher metadata title is excluded from chapter numbering`() = runBlocking {
        val content = xhtml("""
            <link rel='stylesheet' href='titles.css'/>
            <div class='title'><p id='author'>Author One</p><p id='title'>Quiet book</p></div>
            <div class='title'><p id='part'>Part One</p><p>The Coast</p></div>
            <section epub:type='chapter'><div class='title'><p id='chapter'>Arrival</p></div><p>Story text.</p></section>
            <div class='title'><p id='later'>Quiet book</p></div><p>A later actual chapter.</p>
        """)
        val css = ".title { font-size:2.2em; font-weight:bold; page-break-after:avoid; }"
        fun archive(body: String) = book(chapters = mapOf("chapter" to body),
            extraManifest = "<item id='css' href='titles.css' media-type='text/css'/>",
            extras = mapOf("OPS/titles.css" to css.toByteArray()))
        val document = parser.parse(archive(content).inputStream())
        fun block(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        assertEquals(block("part"), document.parts.single().startBlockId)
        assertEquals(listOf(block("chapter"), block("later")), document.chapters.map { it.startBlockId })
        assertEquals(listOf(1, 2), document.chapters.map { it.ordinal })
        assertTrue(document.titlePageBlockIds.isEmpty())
        assertEquals(listOf("Author One", "Quiet book"), document.blocks.take(2).map { it.plainText() })
        val titleOnly = parser.parse(archive(xhtml("<link rel='stylesheet' href='titles.css'/><div class='title'><p>Quiet book</p></div><p>Text.</p>")).inputStream())
        assertTrue(titleOnly.chapters.isEmpty())
        val afterBody = parser.parse(archive(xhtml("<link rel='stylesheet' href='titles.css'/><p>Earlier prose.</p><div class='title'><p>Quiet book</p></div><p>Text.</p>")).inputStream())
        assertEquals(1, afterBody.chapters.size)
    }

    @Test fun `navigation paragraph openings retain explicit divisions without promoting their prose`() = runBlocking {
        val content = xhtml("""
            <section epub:type='part'><p id='winter'><b>Winter</b></p><p id='body'>Ordinary bold <b>prose</b>.</p>
              <section epub:type='chapter'><p id='arrival'>Arrival</p><p>Chapter text.</p></section>
            </section>
            <section epub:type='epilogue'><p id='later'>Ten years later</p><p>Closing text.</p></section>
            <section epub:type='part'><p id='unlisted'>An unlisted part title</p><p>Unlisted body.</p>
              <section><p id='nested'>Departure</p><p>Nested chapter text.</p></section>
            </section>
        """)
        val nav = xhtml("""<nav epub:type='toc'><ol>
            <li><a href='chapter.xhtml#winter'>Winter</a><ol><li><a href='chapter.xhtml#arrival'>Arrival</a></li>
            <li><a href='chapter.xhtml#nested'>Departure</a></li></ol></li>
            <li><a href='chapter.xhtml#later'>Ten years later</a></li>
            </ol></nav>""")
        val document = parser.parse(book(chapters = mapOf("chapter" to content),
            extraManifest = "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>",
            extras = mapOf("OPS/nav.xhtml" to nav.toByteArray())).inputStream())
        fun block(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        assertEquals(listOf(block("winter")), document.parts.single().headingBlockIds)
        assertEquals(listOf(block("arrival"), block("later"), block("nested")), document.chapters.map { it.startBlockId })
        assertEquals(BookChapterKind.EPILOGUE, document.chapters[1].kind)
        assertEquals(listOf(1, null, 2), document.chapters.map { it.ordinal })
        assertTrue(document.blocks.first { it.id == block("body") } is BookBlock.Paragraph)
        assertTrue(document.blocks.first { it.id == block("unlisted") } is BookBlock.Paragraph)
        val plain = parser.parse(book(chapters = mapOf("chapter" to content)).inputStream())
        assertEquals(plain.locationsBySourceId, document.locationsBySourceId)
        assertEquals(plain.blocks.map { it.id to it.plainText() }, document.blocks.map { it.id to it.plainText() })
    }

    @Test fun `NCX styled paragraph divisions work across spine files`() = runBlocking {
        val ncx = """<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap>
            <navPoint id='part'><navLabel><text>Часть первая</text></navLabel><content src='part.xhtml#part'/>
            <navPoint id='chapter'><navLabel><text>Глава первая</text></navLabel><content src='chapter.xhtml#chapter'/></navPoint>
            </navPoint></navMap></ncx>"""
        val document = parser.parse(book(chapters = linkedMapOf(
            "part" to xhtml("<p style='font-weight:bold' id='part'>Часть первая</p>"),
            "chapter" to xhtml("<p class='title' id='chapter'>Глава первая</p><p>Текст 😀.</p>")),
            extraManifest = "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>",
            spineAttributes = "toc='ncx'", extras = mapOf("OPS/toc.ncx" to ncx.toByteArray())).inputStream())
        assertEquals(document.blocks[0].id, document.parts.single().startBlockId)
        assertEquals(document.blocks[1].id, document.chapters.single().startBlockId)
        assertEquals("первая", document.chapters.single().number)
        assertEquals(1, document.chapters.single().ordinal)
        assertEquals(2, (document.blocks[1] as BookBlock.Heading).level)
    }

    @Test fun `ARIA heading semantics identifies paragraph titles without interpreting bold prose`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml(
            "<p role='heading' aria-level='1' id='heading'><b>Arrival</b></p><p><b>Bold prose</b></p>"
        ))).inputStream())
        assertEquals(document.blocks.first().id, document.chapters.single().startBlockId)
        assertTrue(document.blocks.last() is BookBlock.Paragraph)
    }

    @Test fun `explicit epilogue groups its own header without numbering or moving source anchors`() = runBlocking {
        val content = """
            <section epub:type='part'><h1>Part One</h1><section epub:type='chapter'><h2 id='first'>Arrival</h2><p>Text 😀.</p></section></section>
            <section epub:type='epilogue' id='epilogue'><header><h1 id='label'>Эпилог</h1><h2 id='name'>Дом для света</h2></header>
              <p id='text'>Весной 😀 лёд ушёл.</p><section><header><h2 id='subheading'>An evening</h2></header><p>More text.</p></section>
            </section>
            <section epub:type='chapter'><h2 id='last'>Departure</h2><p>Last text.</p></section>
        """
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml(content))).inputStream())
        fun id(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        assertEquals(listOf(id("first"), id("label"), id("last")), document.chapters.map { it.startBlockId })
        val epilogue = document.chapters[1]
        assertEquals(BookChapterKind.EPILOGUE, epilogue.kind)
        assertEquals(listOf(id("label"), id("name")), epilogue.headingBlockIds)
        assertEquals(listOf(1, null, 2), document.chapters.map { it.ordinal })
        assertNull(epilogue.number)
        assertEquals(0, epilogue.numberPrefixLength)
        val ordinary = parser.parse(book(chapters = mapOf("chapter" to xhtml(content.replace("epub:type='epilogue'", "")))).inputStream())
        assertEquals(ordinary.blocks, document.blocks)
        assertEquals(ordinary.locationsBySourceId, document.locationsBySourceId)
        val text = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "text"))
        assertEquals(document.blocks.takeWhile { it.id != text.blockId }.sumOf { it.plainText().length + 1L }, text.fallbackGlobalOffset)
    }

    @Test fun `doc epilogue named heading and exact lexical fallback stay distinct from nested chapters`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <section><h1 id='chapter'>Arrival</h1><p>Epilogue</p><section><h2 id='nested-label'>Epilogue</h2><p>Nested discussion.</p></section></section>
            <section role='doc-epilogue'><h1 id='named'>Ten years later</h1><p>Text.</p></section>
            <section><header><h1 id='lexical'>Epilogue.</h1><h2 id='subtitle'>Back home</h2></header><p>Text.</p></section>
            <section epub:type='chapter'><header><h1 id='chapter-label'>Эпилог</h1><h2 id='chapter-subtitle'>Свет</h2></header><p>Text.</p></section>
            <section epub:type='epilogue'><h1 id='unpaired'>Эпилог</h1>
                <section epub:type='chapter'><h2 id='nested-chapter'>Chapter II</h2><p>Source chapter.</p></section></section>
            <section><h1>Эпилог путешествия</h1><p>Ordinary heading.</p></section>
        """))).inputStream())
        fun id(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        val epilogues = document.chapters.filter { it.kind == BookChapterKind.EPILOGUE }
        assertEquals(listOf(id("named"), id("lexical"), id("chapter-label"), id("unpaired")), epilogues.map { it.startBlockId })
        assertEquals(listOf(id("lexical"), id("subtitle")), epilogues[1].headingBlockIds)
        assertEquals(listOf(id("chapter-label"), id("chapter-subtitle")), epilogues[2].headingBlockIds)
        assertEquals(listOf(id("unpaired")), epilogues[3].headingBlockIds)
        val ordinary = document.chapters.filter { it.kind == BookChapterKind.CHAPTER }
        assertEquals(listOf(id("chapter"), id("nested-chapter")), ordinary.map { it.startBlockId })
        assertEquals(listOf(1, 2), ordinary.map { it.ordinal })
    }

    @Test fun `epilogue semantics does not promote a foreign nested header when its own heading is absent`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <section epub:type='epilogue'><p>A conclusion without a heading.</p>
              <section><header><h2>Discussion</h2></header><p>Not an epilogue title.</p></section>
            </section>
        """))).inputStream())
        assertTrue(document.chapters.isEmpty())
    }

    @Test fun `unmarked numbered part label and sibling subtitle share an opening before an epigraph`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <header><h1 id='part'>Part One</h1><h2 id='subtitle'>The Coast</h2></header><hr/>
            <blockquote epub:type='epigraph'><p id='quote'>The sea.</p></blockquote>
            <section><h2>Arrival</h2><p>Chapter text.</p></section>
            <section><h1>Part Two</h1><section><h2>Departure</h2><p>Next chapter.</p></section></section>
        """))).inputStream())
        assertEquals(2, document.parts.size)
        assertEquals(2, document.parts[0].headingBlockIds.size)
        assertEquals(2, document.parts[0].epigraphBlockIds.size)
        assertEquals(1, document.parts[1].headingBlockIds.size)
        val blocks = document.blocks.associateBy { it.id }
        assertTrue(blocks.getValue(document.parts[0].epigraphBlockIds.first()) is BookBlock.Spacer)
        assertEquals(listOf("Arrival", "Departure"), document.chapters.map { blocks.getValue(it.startBlockId).plainText() })
    }

    @Test fun `part scopes and explicit epigraphs preserve XHTML source coordinates`() = runBlocking {
        val content = """
            <section epub:type='part'><header><h1 id='part'>Part Two</h1><h2 id='name'>The tide map</h2></header>
              <blockquote epub:type='epigraph'><p id='quote'>Water 😀 remembers.</p><p id='author'>A journal</p></blockquote>
              <section epub:type='chapter'><h2 id='chapter'>Chapter Four</h2><p id='text'>Source text.</p></section>
            </section>
            <section role='doc-part'><h1 id='named'>Winter</h1><blockquote role='doc-epigraph'><p id='other-quote'>Snow.</p></blockquote>
              <section><h2 id='nested'>Unmarked chapter</h2><p>More text.</p></section>
            </section>
            <section><h1>Часть речи</h1><p>Ordinary heading.</p></section>
        """
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml(content))).inputStream())
        fun id(source: String) = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", source)).blockId
        assertEquals(2, document.parts.size)
        assertEquals(listOf(id("part"), id("name")), document.parts[0].headingBlockIds)
        assertEquals(listOf(id("quote"), id("author")), document.parts[0].epigraphBlockIds)
        assertEquals(listOf(id("named")), document.parts[1].headingBlockIds)
        assertEquals(listOf(id("other-quote")), document.parts[1].epigraphBlockIds)
        assertEquals(listOf(id("chapter"), id("nested")), document.chapters.map { it.startBlockId })
        val ordinary = parser.parse(book(chapters = mapOf("chapter" to xhtml(content
            .replace("epub:type='part'", "").replace("epub:type='epigraph'", "")
            .replace("role='doc-part'", "").replace("role='doc-epigraph'", "")))).inputStream())
        assertEquals(ordinary.blocks, document.blocks)
        assertEquals(ordinary.locationsBySourceId, document.locationsBySourceId)
        val text = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "text"))
        assertEquals(document.blocks.takeWhile { it.id != text.blockId }.sumOf { it.plainText().length + 1L }, text.fallbackGlobalOffset)
    }

    @Test fun `a normal quote or delayed epigraph is not absorbed by the part`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <section epub:type='part'><h1>Part I</h1><blockquote><p>An ordinary quote.</p></blockquote>
            <blockquote epub:type='epigraph'><p>A later epigraph.</p></blockquote><h2>Chapter I</h2><p>Text.</p></section>
        """))).inputStream())
        assertTrue(document.parts.single().epigraphBlockIds.isEmpty())
    }

    @Test fun `explicit title page and chapter semantics retain every source block and anchor`() = runBlocking {
        val archive = book(chapters = mapOf("chapter" to xhtml("""
            <section epub:type='titlepage'><h1 id='title'>Quiet book</h1><p>Author One</p></section>
            <section epub:type='part'><h1>Part I</h1></section>
            <section epub:type='chapter'><header><h1 id='number'>Chapter XI.</h1><h2 id='name'>The Pacific</h2></header><p id='text'>Text 😀.</p><h3>A subtitle</h3><p>More text</p></section>
        """)), extraMetadata = "<meta name='calibre:series' content='Quiet stories'/><meta name='calibre:series_index' content='2.0'/>")
        val document = parser.parse(archive.inputStream(), "semantics.epub")
        assertEquals(2, document.titlePageBlockIds.size)
        assertEquals("Quiet book", document.blocks.first().plainText())
        val chapter = document.chapters.single()
        assertEquals("XI", chapter.number)
        assertEquals(2, chapter.headingBlockIds.size)
        assertEquals(document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "number")).blockId, chapter.startBlockId)
        assertEquals(BookSequence("Quiet stories", 2), document.metadata.sequence)
        val body = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "text"))
        val before = document.blocks.takeWhile { it.id != body.blockId }
        assertEquals(before.sumOf { it.plainText().length + 1L }, body.fallbackGlobalOffset)
    }

    @Test fun `matching title text without semantic marker is never removed or promoted`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("<p>Quiet book</p><p>Actual text</p>"))).inputStream(), "plain.epub")
        assertTrue(document.titlePageBlockIds.isEmpty())
        assertEquals("Quiet book", document.blocks.first().plainText())
    }

    @Test fun `EPUB3 metadata spine navigation rich text and deferred image survive archive close`() = runBlocking {
        val image = byteArrayOf(1, 2, 3, 4)
        val archive = book(
            chapters = linkedMapOf(
                "second" to xhtml("<h1 id='two'>Chapter two</h1><p>Last paragraph</p>"),
                "first" to xhtml("<h1 id='one'>Глава первая</h1><p id='body'>Text <em>italic <b>bold</b></em> <s>gone</s>.</p><div><img src='cover.png' alt='Cover'/></div>"),
            ),
            spine = "<itemref idref='first'/><itemref idref='second'/>",
            extraManifest = "<item id='cover' href='cover.png' media-type='image/png' properties='cover-image'/><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>",
            extras = mapOf("OPS/cover.png" to image, "OPS/nav.xhtml" to xhtml("""<nav epub:type='toc'><ol><li><a href='first.xhtml#one'>One</a><ol><li><a href='second.xhtml#two'>Two</a></li></ol></li></ol></nav>""").toByteArray()),
        )
        val document = parser.parse(archive.inputStream(), "sample.epub")
        assertEquals("Quiet book", document.metadata.title)
        assertEquals(listOf("Author One", "Автор Два"), document.metadata.authors)
        assertEquals("ru", document.metadata.language)
        assertEquals(listOf("Fiction"), document.metadata.genres)
        assertEquals("An annotation.", document.metadata.annotation)
        assertEquals("test-book", document.metadata.documentId)
        assertEquals("Глава первая", document.blocks.first().plainText())
        assertEquals("Last paragraph", document.blocks.last().plainText())
        val paragraph = document.blocks.filterIsInstance<BookBlock.Paragraph>().first()
        assertEquals("Text italic bold gone.", paragraph.plainText())
        val emphasis = paragraph.content.filterIsInstance<InlineNode.Emphasis>().single()
        assertTrue(emphasis.children.any { it is InlineNode.Strong })
        assertTrue(paragraph.content.any { it is InlineNode.Strikethrough })
        assertEquals("One", document.tableOfContents.single().title)
        val child = document.tableOfContents.single().children.single()
        assertEquals("Two", child.title)
        assertEquals(document.locationsBySourceId[sourceKey("OPS/second.xhtml", "two")], child.location)
        val cover = document.imagesById.getValue(requireNotNull(document.metadata.coverImageId)).content
        assertArrayEquals(image, cover.load())
        assertSame(cover.load(), cover.load())
        assertTrue(document.id.value.startsWith("sha256:"))
    }

    @Test fun `EPUB2 NCX nesting and legacy cover metadata are supported`() = runBlocking {
        val ncx = """<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint><navLabel><text>Opening</text></navLabel><content src='chapter.xhtml#a'/><navPoint><navLabel><text>Middle</text></navLabel><content src='chapter.xhtml#b'/></navPoint></navPoint></navMap></ncx>"""
        val document = parser.parse(book(
            chapters = mapOf("chapter" to xhtml("<p id='a'>Opening</p><p id='b'>Middle</p>")),
            extraManifest = "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/><item id='cover' href='cover.jpg' media-type='image/jpeg'/>",
            extraMetadata = "<meta name='cover' content='cover'/>",
            spineAttributes = "toc='ncx'",
            extras = mapOf("OPS/toc.ncx" to ncx.toByteArray(), "OPS/cover.jpg" to byteArrayOf(4, 3)),
        ).inputStream())
        assertEquals("Opening", document.tableOfContents.single().title)
        assertEquals("Middle", document.tableOfContents.single().children.single().title)
        assertEquals(ImageId("epub-image:OPS/cover.jpg"), document.metadata.coverImageId)
    }

    @Test fun `cross-file and inline UTF16 anchors resolve after all spine files`() = runBlocking {
        val document = parser.parse(book(chapters = linkedMapOf(
            "a" to xhtml("<p id='a'>A😀 <span id='inline'>target</span> <a href='b.xhtml#b'>next</a></p>"),
            "b" to xhtml("<p id='b'><a href='a.xhtml#inline'>back</a> <a href='https://example.com'>web</a></p>"),
        )).inputStream())
        val inline = document.locationsBySourceId.getValue(sourceKey("OPS/a.xhtml", "inline"))
        assertEquals(4, inline.charOffset)
        assertEquals("target", document.blocks.first().plainText().substring(inline.charOffset, inline.charOffset + 6))
        val firstLink = (document.blocks.first() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single()
        assertEquals(LinkTarget.Internal(document.locationsBySourceId.getValue(sourceKey("OPS/b.xhtml", "b"))), firstLink.target)
        val links = (document.blocks.last() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>()
        assertEquals(LinkTarget.Internal(inline), links.first().target)
        assertEquals(LinkTarget.External("https://example.com"), links.last().target)
    }

    @Test fun `notes remain outside main flow and non linear note document is available`() = runBlocking {
        val document = parser.parse(book(
            chapters = linkedMapOf(
                "main" to xhtml("<p>Main<a epub:type='noteref' href='notes.xhtml#n'>1</a></p><aside epub:type='footnote' id='inline-note'><p>Inline note</p></aside><p>End</p>"),
                "notes" to xhtml("<p>Supplementary frontmatter</p><aside epub:type='footnote' id='n'><h2>1</h2><p>Note body</p></aside>"),
            ), spine = "<itemref idref='main'/><itemref idref='notes' linear='no'/>",
        ).inputStream())
        assertEquals("Main1\nEnd", document.plainText())
        assertEquals(2, document.notes.size)
        assertEquals("1\nNote body", document.notes.last().plainText())
        val target = ((document.blocks.first() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single().target as LinkTarget.Note)
        assertEquals(document.notes.last().id, target.noteId)
        assertFalse(document.tableOfContents.any { it.title == "1" })
    }

    @Test fun `stable identity and anchors survive reimport different filename and archive entry order`() = runBlocking {
        val original = book()
        val first = parser.parse(original.inputStream(), "original.epub")
        val renamed = parser.parse(original.inputStream(), "stored.book")
        assertEquals(first.id, renamed.id)
        assertEquals(first.blocks, renamed.blocks)
        assertEquals(first.locationsBySourceId, renamed.locationsBySourceId)
        val changed = parser.parse(book(chapters = mapOf("chapter" to xhtml("<p>Changed text</p>"))).inputStream())
        assertNotEquals(first.id, changed.id)
    }

    @Test fun `nested encoded resource paths and source IDs do not collide`() = runBlocking {
        val archive = book(chapters = mapOf("part/chapter+one" to xhtml("<p id='a@b'>One<a href='#a%40b'>again</a></p><p id='a%40b'>Two</p>")))
        val document = parser.parse(archive.inputStream())
        assertEquals(3, document.locationsBySourceId.size)
        val target = (document.blocks.first() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single().target
        assertEquals(LinkTarget.Internal(document.locationsBySourceId.getValue(sourceKey("OPS/part/chapter+one.xhtml", "a@b"))), target)
    }

    @Test fun `hidden active content is ignored and inline CSS typography is retained`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <script>malicious text</script><p hidden='hidden'>hidden</p><div style='display: none'>also hidden</div>
            <p><span style='font-style: italic; font-weight: 700; text-decoration: line-through'>Styled</span><br/>next</p>
        """.trimIndent()))).inputStream())
        assertEquals("Styled\nnext", document.plainText())
        val em = (document.blocks.single() as BookBlock.Paragraph).content.first() as InlineNode.Emphasis
        assertTrue(em.children.first() is InlineNode.Strong)
        assertTrue((em.children.first() as InlineNode.Strong).children.first() is InlineNode.Strikethrough)
    }

    @Test fun `linked and embedded styles retain typography without executing CSS imports or media rules`() = runBlocking {
        val chapter = """<html xmlns='http://www.w3.org/1999/xhtml'><head><link rel='stylesheet' href='style.css'/><style>.inline { font-weight: bold; }</style></head><body><p><span class='italic'>Italic</span> <span class='inline'>Bold</span> <span class='italic' style='font-style: normal'>Normal</span></p></body></html>"""
        val document = parser.parse(book(chapters = mapOf("chapter" to chapter),
            extraManifest = "<item id='css' href='style.css' media-type='text/css'/>",
            extras = mapOf("OPS/style.css" to """@import url('https://example.com/remote.css');
                .unused { color: black; }
                .italic { font-style: italic; }
                @media print { p { display: none; } }
            """.toByteArray()),
        ).inputStream())
        val content = (document.blocks.single() as BookBlock.Paragraph).content
        assertEquals("Italic Bold Normal", document.plainText())
        assertEquals("Italic", content.filterIsInstance<InlineNode.Emphasis>().single().plainText())
        assertEquals(" Bold", content.filterIsInstance<InlineNode.Strong>().single().plainText())
        assertTrue(content.last() is InlineNode.Text)
    }

    @Test fun `empty notes and notes preceding the first paragraph do not steal main anchors`() = runBlocking {
        val document = parser.parse(book(chapters = mapOf("chapter" to xhtml("""
            <aside epub:type='footnote' id='empty'/>
            <aside epub:type='footnote' id='note'><p>Note before main</p></aside>
            <p id='main'>Actual main <a href='#empty'>empty</a></p>
        """.trimIndent()))).inputStream())
        assertEquals(1, document.notes.size)
        assertEquals(document.blocks.single().id, document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml")).blockId)
        assertEquals(document.blocks.single().id, document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "main")).blockId)
        assertFalse(document.locationsBySourceId.containsKey(sourceKey("OPS/chapter.xhtml", "empty")))
        assertTrue((document.blocks.single() as BookBlock.Paragraph).content.filterIsInstance<InlineNode.Link>().single().target is LinkTarget.UnresolvedInternal)
    }

    @Test fun `source stream ownership remains with caller`() = runBlocking {
        var closed = false
        val input = object : ByteArrayInputStream(book()) { override fun close() { closed = true; super.close() } }
        parser.parse(input)
        assertFalse(closed)
    }

    @Test fun `container missing package missing title and missing readable body return typed errors`() {
        malformed(zip(mapOf("mimetype" to "application/epub+zip".toByteArray())))
        malformed(book(spine = "<itemref idref='absent'/>"))
        assertThrows(BookParseException.MissingTitle::class.java) {
            runBlocking { parser.parse(book(title = "").inputStream()) }
        }
        assertThrows(BookParseException.MissingReadableBody::class.java) {
            runBlocking { parser.parse(book(chapters = mapOf("chapter" to xhtml(""))).inputStream()) }
        }
    }

    @Test fun `legacy XHTML and NCX doctypes open with decoded entities and stable anchors`() = runBlocking {
        val body = "<h1 id='start'>Chapter</h1><p>A😀&nbsp;<span id='mark'>text</span>&mdash;&ldquo;end&rdquo; <a href='#mark'>back</a></p>"
        val declaration = """<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>"""
        val document = parser.parse(book(
            chapters = mapOf("chapter" to declaration + xhtml(body)),
            spineAttributes = "toc='ncx'",
            extraManifest = "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>",
            extras = mapOf("OPS/toc.ncx" to """<!DOCTYPE ncx PUBLIC '-//NISO//DTD ncx 2005-1//EN' 'http://www.daisy.org/z3986/2005/ncx-2005-1.dtd'><ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint><navLabel><text>Opening</text></navLabel><content src='chapter.xhtml#mark'/></navPoint></navMap></ncx>""".toByteArray()),
        ).inputStream())
        val ordinary = parser.parse(book(chapters = mapOf("chapter" to xhtml(body
            .replace("&nbsp;", "&#160;").replace("&mdash;", "&#8212;")
            .replace("&ldquo;", "&#8220;").replace("&rdquo;", "&#8221;")))).inputStream())
        assertEquals(ordinary.blocks, document.blocks)
        assertEquals(ordinary.locationsBySourceId, document.locationsBySourceId)
        assertEquals("A😀\u00a0text—“end” back", document.blocks.last().plainText())
        val anchor = document.locationsBySourceId.getValue(sourceKey("OPS/chapter.xhtml", "mark"))
        assertEquals(4, anchor.charOffset)
        assertEquals(anchor, document.tableOfContents.single().location)
        assertEquals(LinkTarget.Internal(anchor), (document.blocks.last() as BookBlock.Paragraph)
            .content.filterIsInstance<InlineNode.Link>().single().target)
    }

    @Test fun `HTML5 navigation and literal doctypes inside comments CDATA and instructions open`() = runBlocking {
        val document = parser.parse(book(
            chapters = mapOf("chapter" to "<?publisher test?><!-- literal <!DOCTYPE example> -->" + xhtml("<p id='start'><![CDATA[<!DOCTYPE html> &nbsp;]]></p>")),
            extraManifest = "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>",
            extras = mapOf("OPS/nav.xhtml" to ("<!DOCTYPE html>" + xhtml("<nav epub:type='toc'><ol><li><a href='chapter.xhtml#start'>One&nbsp;page</a></li></ol></nav>")).toByteArray()),
        ).inputStream())
        assertEquals("<!DOCTYPE html> &nbsp;", document.blocks.single().plainText())
        assertEquals("One\u00a0page", document.tableOfContents.single().title)
    }

    @Test fun `unknown entities malformed doctypes and oversized declaration remain typed failures`() {
        malformed(book(chapters = mapOf("chapter" to "<!DOCTYPE html>" + xhtml("<p>&unknown;</p>"))))
        malformed(book(chapters = mapOf("chapter" to "<!DOCTYPE html SYSTEM 'https://example.invalid/custom.dtd'>" + xhtml("<p>Text</p>"))))
        malformed(book(chapters = mapOf("chapter" to "<!DOCTYPE html []>" + xhtml("<p>Text</p>"))))
        assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
            runBlocking { parser.parse(book(chapters = mapOf("chapter" to "<!DOCTYPE html ${" ".repeat(8_192)}>" + xhtml("<p>Text</p>"))).inputStream()) }
        }
    }

    @Test fun `DTD subsets and external entity declarations are rejected including UTF16`() {
        val hostile = """<!DOCTYPE html [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><html xmlns='http://www.w3.org/1999/xhtml'><body><p>&xxe;</p></body></html>"""
        malformed(book(chapters = mapOf("chapter" to hostile)))
        val utf16 = "<?xml version='1.0' encoding='UTF-16'?>$hostile".toByteArray(Charsets.UTF_16)
        malformed(book(extras = mapOf("OPS/chapter.xhtml" to utf16)))
    }

    @Test fun `archive traversal and package traversal are rejected`() {
        malformed(book(extras = mapOf("../outside" to byteArrayOf(1))))
        malformed(book(extraManifest = "<item id='bad' href='../../outside.xhtml' media-type='application/xhtml+xml'/>"))
    }

    @Test fun `actual archive XML image block depth and inflation budgets are enforced`() {
        fun exceeds(limits: EpubParserLimits, archive: ByteArray = book()) {
            assertThrows(BookParseException.ResourceLimitExceeded::class.java) {
                runBlocking { EpubBookParser(limits).parse(archive.inputStream()) }
            }
        }
        exceeds(EpubParserLimits(maxArchiveBytes = 10))
        exceeds(EpubParserLimits(maxArchiveEntries = 1))
        exceeds(EpubParserLimits(maxArchiveEntryBytes = 10))
        exceeds(EpubParserLimits(maxInflatedArchiveBytes = 10))
        exceeds(EpubParserLimits(maxXmlBytes = 10))
        exceeds(EpubParserLimits(maxXmlCharacters = 10))
        exceeds(EpubParserLimits(maxXmlDepth = 2))
        exceeds(EpubParserLimits(maxXmlElements = 2))
        exceeds(EpubParserLimits(maxBlocks = 1), book(chapters = mapOf("chapter" to xhtml("<p>One</p><p>Two</p>"))))
        val withImage = book(extraManifest = "<item id='cover' href='cover.png' media-type='image/png' properties='cover-image'/>",
            extras = mapOf("OPS/cover.png" to ByteArray(20) { it.toByte() }))
        exceeds(EpubParserLimits(maxImageBytes = 10), withImage)
        exceeds(EpubParserLimits(maxTotalImageBytes = 10), withImage)
        exceeds(EpubParserLimits(maxCompressionRatio = 2), book(chapters = mapOf("chapter" to xhtml("<p>${"a".repeat(10_000)}</p>"))))
    }

    @Test fun `truncated and non ZIP documents are malformed`() {
        malformed("not EPUB".toByteArray())
        malformed(book().copyOf(100))
    }

    @Test fun `EPUB extension hints are case insensitive and restricted`() {
        assertTrue(parser.supports(" Book.EPUB "))
        assertFalse(parser.supports("book.fb2"))
        assertFalse(parser.supports("book.zip"))
    }

    private fun malformed(archive: ByteArray) {
        assertThrows(BookParseException.MalformedDocument::class.java) { runBlocking { parser.parse(archive.inputStream()) } }
    }
    private fun xhtml(body: String): String = """<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Ignored title</title></head><body>$body</body></html>"""
    private fun book(
        chapters: Map<String, String> = mapOf("chapter" to xhtml("<p id='start'>Readable text</p>")),
        title: String = "Quiet book",
        spine: String = chapters.keys.joinToString("") { "<itemref idref='$it'/>" },
        extraMetadata: String = "",
        extraManifest: String = "",
        spineAttributes: String = "",
        extras: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>""".toByteArray(),
            "OPS/package.opf" to """<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>$title</dc:title><dc:creator>Author One</dc:creator><dc:creator>Автор Два</dc:creator><dc:language>ru</dc:language><dc:subject>Fiction</dc:subject><dc:description>An annotation.</dc:description><dc:identifier id='uid'>test-book</dc:identifier>$extraMetadata</metadata><manifest>${chapters.keys.joinToString("") { "<item id='$it' href='$it.xhtml' media-type='application/xhtml+xml'/>" }}$extraManifest</manifest><spine $spineAttributes>$spine</spine></package>""".toByteArray(),
        )
        chapters.forEach { (name, content) -> entries["OPS/$name.xhtml"] = content.toByteArray() }
        entries.putAll(extras)
        return zip(entries)
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        } }
    }.toByteArray()
}
