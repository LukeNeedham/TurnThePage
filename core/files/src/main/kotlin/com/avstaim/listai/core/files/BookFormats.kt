package com.avstaim.listai.core.files

import com.avstaim.listai.format.fb2.BookParseException
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** The private copy is identified by bytes; provider names and MIME types are only hints. */
enum class BookFormat(val extension: String, val mimeType: String) {
    FB2("fb2", "application/x-fictionbook+xml"),
    FB2_ZIP("fb2.zip", "application/zip"),
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf");

    companion object {
        fun detect(file: File): BookFormat {
            val prefix = file.inputStream().use { input ->
                val bytes = ByteArray(1024)
                val count = input.read(bytes)
                bytes.copyOf(count.coerceAtLeast(0))
            }
            if (prefix.toString(Charsets.ISO_8859_1).startsWith("%PDF-")) return PDF
            if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
                ZipFile(file).use { zip ->
                    if (zip.size() > 10_000) throw BookParseException.ResourceLimitExceeded("ZIP entries", 10_000)
                    return if (zip.getEntry("META-INF/container.xml") != null) EPUB else FB2_ZIP
                }
            }
            return FB2
        }

        fun accepts(name: String?, mimeType: String?): Boolean {
            val lower = name.orEmpty().lowercase(Locale.ROOT)
            return listOf(".fb2", ".fb2.zip", ".zip", ".epub", ".pdf").any(lower::endsWith) ||
                mimeType in setOf("application/x-fictionbook+xml", "application/fb2",
                    "application/x-fictionbook+zip", "application/epub+zip", "application/pdf")
        }
    }
}
