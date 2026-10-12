package com.avstaim.listai.format.epub

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubXmlInputSourceTest {
    @Test fun `standard XHTML declarations are inert and named entities become numeric references`() {
        val declarations = listOf(
            "<!DOCTYPE html>",
            "<!DOCTYPE html SYSTEM 'about:legacy-compat'>",
            "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Strict//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd'>",
            "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Transitional//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd'>",
            "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>",
            "<!DOCTYPE html SYSTEM 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd'>",
        )
        declarations.forEach { declaration ->
            val output = decode("$declaration<html><body><p title='&copy;'>&nbsp;&mdash;&euro;&eacute;</p></body></html>")
            assertFalse(output.contains("<!DOCTYPE"))
            assertTrue(output.contains("title='&#169;'"))
            assertTrue(output.contains("&#160;&#8212;&#8364;&#233;"))
        }
    }

    @Test fun `standard NCX external declaration is stripped without consulting its URL`() {
        val output = decode("""<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx><navMap/></ncx>""")
        assertFalse(output.contains("<!DOCTYPE"))
        assertTrue(output.contains("<ncx><navMap/></ncx>"))
    }

    @Test fun `XML builtins numeric references and escaped entity text are never recursively expanded`() {
        val xml = "<html><body><p>&amp;nbsp; &lt; &gt; &apos; &quot; &#160; &#xA0;</p></body></html>"
        assertEquals(xml, decode("<!DOCTYPE html>$xml"))
    }

    @Test fun `comments CDATA and processing instructions retain literal declaration and entity tokens`() {
        val xml = """<?before <!DOCTYPE html> &nbsp;?><!-- literal <!DOCTYPE html> &nbsp; --><!DOCTYPE html><html><body><![CDATA[<!DOCTYPE html> &nbsp;]]><?inside <!DOCTYPE html> &nbsp;?><p>&nbsp;</p></body></html>"""
        val output = decode(xml)
        assertTrue(output.contains("<?before <!DOCTYPE html> &nbsp;?>"))
        assertTrue(output.contains("<!-- literal <!DOCTYPE html> &nbsp; -->"))
        assertTrue(output.contains("<![CDATA[<!DOCTYPE html> &nbsp;]]>"))
        assertTrue(output.contains("<?inside <!DOCTYPE html> &nbsp;?>"))
        assertTrue(output.contains("<p>&#160;</p>"))
    }

    @Test fun `DOCTYPE after XML declaration comments and instructions is supported`() {
        val xml = """<?xml version='1.0'?><!-- publisher --><?book metadata?><!DOCTYPE html><html><body>Text</body></html>"""
        val output = decode(xml)
        assertFalse(output.contains("<!DOCTYPE"))
        assertTrue(output.contains("<?xml version='1.0'?><!-- publisher --><?book metadata?>"))
        assertTrue(output.contains("<html><body>Text</body></html>"))
    }

    @Test fun `internal subsets and arbitrary external declarations fail closed`() {
        val declarations = listOf(
            "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>",
            "<!DOCTYPE html [<!ENTITY secret 'replacement'>]>",
            "<!DOCTYPE html [<!ENTITY % parameter SYSTEM 'https://example.invalid/remote.dtd'> %parameter;]>",
            "<!DOCTYPE html []>",
            "<!DOCTYPE html SYSTEM 'https://example.invalid/custom.dtd'>",
            "<!DOCTYPE html PUBLIC '-//Untrusted//DTD custom//EN' 'https://example.invalid/custom.dtd'>",
            "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'file:///etc/passwd'>",
            "<!DOCTYPE html SYSTEM 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd' 'extra'>",
            "<!DOCTYPE html SYSTEM 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd' ''>",
        )
        declarations.forEach { declaration ->
            assertThrows(IOException::class.java) { decode("$declaration<html><body>Text</body></html>") }
        }
    }

    @Test fun `real declaration within content remains forbidden`() {
        assertThrows(IOException::class.java) { decode("<html><body><!DOCTYPE html><p>Text</p></body></html>") }
    }

    @Test fun `single byte source and single character reads preserve tokenizer state`() {
        val xml = """<?xml version='1.0'?><!-- &nbsp; --><!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'><html><body><p>&nbsp;&mdash;</p><![CDATA[&nbsp;]]></body></html>"""
        val reader = epubXmlInputSource(SingleByteInputStream(xml.toByteArray())).characterStream
        val output = reader.use {
            buildString {
                while (true) {
                    val character = it.read()
                    if (character < 0) break
                    append(character.toChar())
                }
            }
        }
        assertFalse(output.contains("<!DOCTYPE"))
        assertTrue(output.contains("<!-- &nbsp; -->"))
        assertTrue(output.contains("<p>&#160;&#8212;</p>"))
        assertTrue(output.contains("<![CDATA[&nbsp;]]>"))
    }

    @Test fun `short offset buffer reads preserve numeric replacements and untouched surrounding data`() {
        val xml = "<!DOCTYPE html><html><body><p title='&nbsp;'>&mdash;</p></body></html>"
        val output = epubXmlInputSource(xml.byteInputStream()).characterStream.use { reader ->
            val buffer = CharArray(4) { '!' }
            buildString {
                while (true) {
                    val count = reader.read(buffer, 2, 1)
                    if (count < 0) break
                    assertEquals('!', buffer[0])
                    assertEquals('!', buffer[1])
                    assertEquals('!', buffer[3])
                    append(buffer[2])
                }
            }
        }
        assertTrue(output.contains("<p title='&#160;'>&#8212;</p>"))
    }

    @Test fun `skip cannot bypass forbidden declaration inspection`() {
        val xml = "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><html/>"
        epubXmlInputSource(xml.byteInputStream()).characterStream.use { reader ->
            assertThrows(IOException::class.java) {
                while (reader.skip(1) > 0) Unit
            }
        }
    }

    @Test fun `decoding precedes safe declaration and entity handling across byte encodings`() {
        val cases = listOf(
            Triple("UTF-8", Charsets.UTF_8, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())),
            Triple("UTF-16", Charsets.UTF_16LE, byteArrayOf(0xff.toByte(), 0xfe.toByte())),
            Triple("UTF-16", Charsets.UTF_16BE, byteArrayOf(0xfe.toByte(), 0xff.toByte())),
            Triple("windows-1251", Charset.forName("windows-1251"), byteArrayOf()),
        )
        cases.forEach { (declared, charset, bom) ->
            val xml = "<?xml version='1.0' encoding='$declared'?><!DOCTYPE html><html><body>Книга &nbsp;</body></html>"
            val output = decode(ByteArrayInputStream(bom + xml.toByteArray(charset)))
            assertFalse(output.contains("<!DOCTYPE"))
            assertTrue(output.contains("Книга &#160;"))
        }
    }

    private fun decode(xml: String): String = decode(xml.byteInputStream())
    private fun decode(input: InputStream): String = epubXmlInputSource(input).characterStream.use(Reader::readText)

    private class SingleByteInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length.coerceAtMost(1))
    }
}
