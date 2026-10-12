package com.avstaim.listai.format.epub

import java.io.InputStream
import java.net.URLDecoder
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal open class EpubHandler : DefaultHandler2() {
    override fun error(error: SAXParseException): Nothing = throw error
    override fun fatalError(error: SAXParseException): Nothing = throw error
    override fun startDTD(name: String?, publicId: String?, systemId: String?): Nothing = malformed()
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = malformed()
    override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = malformed()
}

/** A single budget spans the package, all spine documents, and the navigation document. */
internal class EpubXml(private val limits: EpubParserLimits) {
    private var elements = 0L
    private var characters = 0L
    fun parse(input: InputStream, handler: EpubHandler) {
        var depth = 0
        val reader = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }.newSAXParser().xmlReader
        listOf(
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (name, enabled) ->
            try { reader.setFeature(name, enabled) } catch (_: SAXException) {
                // Android Expat differs from the JVM. The decoded guard removes only known inert
                // doctypes and rejects DTDs; the resolver blocks entities regardless of feature support.
            }
        }
        reader.entityResolver = handler
        reader.errorHandler = handler
        try { reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler) } catch (_: SAXException) { }
        reader.contentHandler = object : EpubHandler() {
            override fun startElement(uri: String, local: String, name: String, attrs: Attributes) {
                checkEpubCancellation()
                if (++depth > limits.maxXmlDepth) limit("EPUB XML depth", limits.maxXmlDepth.toLong())
                elements = checkedSize(elements, 1, limits.maxXmlElements, "EPUB XML elements")
                for (index in 0 until attrs.length) {
                    characters = checkedSize(characters, attrs.getValue(index).length.toLong(), limits.maxXmlCharacters, "EPUB XML characters")
                }
                handler.startElement(uri, local, name, attrs)
            }
            override fun endElement(uri: String, local: String, name: String) {
                handler.endElement(uri, local, name)
                depth--
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                checkEpubCancellation()
                characters = checkedSize(characters, length.toLong(), limits.maxXmlCharacters, "EPUB XML characters")
                handler.characters(chars, start, length)
            }
            override fun endDocument() = handler.endDocument()
        }
        reader.parse(epubXmlInputSource(input))
    }
}

internal fun elementName(local: String, qualified: String): String = local.ifEmpty { qualified.substringAfter(':') }.lowercase()
internal fun Attributes.value(name: String): String? = getValue(name)?.trim()?.takeIf(String::isNotEmpty)
internal fun Attributes.epubType(): Set<String> =
    (getValue("http://www.idpf.org/2007/ops", "type") ?: getValue("epub:type")).orEmpty().split(Regex("\\s+")).toSet() +
        value("role").orEmpty().split(Regex("\\s+")).mapNotNull {
            when (it) { "doc-part" -> "part"; "doc-chapter" -> "chapter"; "doc-epigraph" -> "epigraph"; "doc-epilogue" -> "epilogue"; else -> null }
        }
internal fun String.normalizedText(): String = replace(Regex("[\\t\\r\\n ]+"), " ").trim()

internal fun normalizeArchivePath(path: String): String {
    if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path || path.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"))) malformed()
    val segments = mutableListOf<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) malformed() else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/").ifEmpty { malformed() }
}

private fun decodeUrlPart(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
internal fun isExternalReference(value: String): Boolean = value.startsWith("//") || value.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"))
internal data class EpubReference(val path: String, val fragment: String?) {
    val sourceId: String get() = sourceKey(path, fragment)
}
internal fun resolveReference(base: String, reference: String): EpubReference? {
    if (isExternalReference(reference)) return null
    val pathPart = reference.substringBefore('#').substringBefore('?')
    val fragment = reference.substringAfter('#', "").takeIf(String::isNotBlank)?.let(::decodeUrlPart)
    val path = if (pathPart.isEmpty()) base else {
        val decoded = decodeUrlPart(pathPart)
        if (decoded.startsWith('/') || '\\' in decoded) malformed()
        normalizeArchivePath(base.substringBeforeLast('/', "").let { if (it.isEmpty()) decoded else "$it/$decoded" })
    }
    return EpubReference(path, fragment)
}
/** '#' is forbidden by BookLocation's source map; escape separators for a collision-free key. */
internal fun sourceKey(path: String, fragment: String? = null): String {
    fun escape(value: String) = value.replace("%", "%25").replace("#", "%23").replace("@", "%40")
    return escape(path) + fragment?.let { "@" + escape(it) }.orEmpty()
}

internal class ContainerHandler : EpubHandler() {
    var packagePath: String? = null
    override fun startElement(uri: String, local: String, name: String, attrs: Attributes) {
        if (elementName(local, name) == "rootfile" && packagePath == null &&
            attrs.value("media-type") == "application/oebps-package+xml") {
            packagePath = attrs.value("full-path")
        }
    }
}

internal data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: Set<String>, val fallback: String?)
internal data class SpineReference(val id: String, val linear: Boolean)
internal class PackageHandler(private val path: String) : EpubHandler() {
    val items = linkedMapOf<String, ManifestItem>()
    val spine = mutableListOf<SpineReference>()
    val authors = mutableListOf<String>()
    val subjects = mutableListOf<String>()
    var title: String? = null
    var language: String? = null
    var description: String? = null
    var identifier: String? = null
    var coverId: String? = null
    var coverPage: String? = null
    var titlePage: String? = null
    var seriesName: String? = null
    var seriesNumber: Int? = null
    var ncxId: String? = null
    private val stack = mutableListOf<String>()
    private var capture: String? = null
    private var captureDepth = 0
    private val text = StringBuilder()
    private var packageSeen = false

    override fun startElement(uri: String, local: String, name: String, attrs: Attributes) {
        val tag = elementName(local, name)
        if (stack.isEmpty()) {
            if (tag != "package" || (uri.isNotEmpty() && uri != "http://www.idpf.org/2007/opf")) malformed()
            packageSeen = true
        }
        val parent = stack.lastOrNull()
        stack += tag
        if (parent == "metadata" && (uri == "http://purl.org/dc/elements/1.1/" || uri.isEmpty()) &&
            tag in setOf("title", "creator", "language", "description", "subject", "identifier")) {
            capture = tag
            captureDepth = stack.size
            text.setLength(0)
        }
        when {
            tag == "item" && parent == "manifest" -> {
                val id = attrs.value("id") ?: malformed()
                val reference = resolveReference(path, attrs.value("href") ?: malformed()) ?: return
                val item = ManifestItem(id, reference.path, attrs.value("media-type") ?: malformed(),
                    attrs.value("properties").orEmpty().split(Regex("\\s+")).toSet(), attrs.value("fallback"))
                if (items.put(id, item) != null) malformed()
            }
            tag == "spine" -> ncxId = attrs.value("toc")
            tag == "itemref" && parent == "spine" -> spine += SpineReference(attrs.value("idref") ?: malformed(), attrs.value("linear") != "no")
            tag == "meta" && parent == "metadata" && attrs.value("name") == "cover" -> coverId = attrs.value("content")
            tag == "meta" && parent == "metadata" && attrs.value("name") == "calibre:series" -> seriesName = attrs.value("content")
            tag == "meta" && parent == "metadata" && attrs.value("name") == "calibre:series_index" ->
                seriesNumber = attrs.value("content")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
            tag == "reference" && parent == "guide" && attrs.value("type") == "cover" ->
                coverPage = attrs.value("href")?.let { resolveReference(path, it)?.path }
            tag == "reference" && parent == "guide" && attrs.value("type") == "title-page" ->
                titlePage = attrs.value("href")?.let { resolveReference(path, it)?.path }
        }
    }
    override fun characters(chars: CharArray, start: Int, length: Int) {
        if (capture != null) text.append(chars, start, length)
    }
    override fun endElement(uri: String, local: String, name: String) {
        if (capture != null && stack.size == captureDepth) {
            val value = text.toString().normalizedText().takeIf(String::isNotBlank)
            if (value != null) when (capture) {
                "title" -> if (title == null) title = value
                "creator" -> authors += value
                "language" -> if (language == null) language = value
                "description" -> if (description == null) description = value
                "subject" -> subjects += value
                "identifier" -> if (identifier == null) identifier = value
            }
            capture = null
        }
        stack.removeAt(stack.lastIndex)
    }
    override fun endDocument() { if (!packageSeen || spine.isEmpty()) malformed() }
}

internal data class PendingToc(var title: String = "", var target: String? = null, val children: MutableList<PendingToc> = mutableListOf())
internal class NavHandler(private val path: String) : EpubHandler() {
    val entries = mutableListOf<PendingToc>()
    private var depth = 0
    private var navDepth = -1
    private val stack = mutableListOf<PendingToc>()
    private var labelDepth = -1
    private val label = StringBuilder()
    override fun startElement(uri: String, local: String, name: String, attrs: Attributes) {
        depth++
        when (elementName(local, name)) {
            "nav" -> if ("toc" in attrs.epubType() && navDepth < 0) navDepth = depth
            "li" -> if (navDepth >= 0) {
                val entry = PendingToc()
                (stack.lastOrNull()?.children ?: entries) += entry
                stack += entry
            }
            "a", "span" -> if (navDepth >= 0 && stack.isNotEmpty() && labelDepth < 0) {
                labelDepth = depth
                label.setLength(0)
                attrs.value("href")?.let { stack.last().target = resolveReference(path, it)?.sourceId }
            }
        }
    }
    override fun characters(chars: CharArray, start: Int, length: Int) {
        if (labelDepth >= 0) label.append(chars, start, length)
    }
    override fun endElement(uri: String, local: String, name: String) {
        if (depth == labelDepth) {
            stack.lastOrNull()?.title = label.toString().normalizedText()
            labelDepth = -1
        }
        if (elementName(local, name) == "li" && navDepth >= 0 && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        if (depth == navDepth) navDepth = -1
        depth--
    }
}
internal class NcxHandler(private val path: String) : EpubHandler() {
    val entries = mutableListOf<PendingToc>()
    private val stack = mutableListOf<PendingToc>()
    private var inLabel = false
    private val label = StringBuilder()
    override fun startElement(uri: String, local: String, name: String, attrs: Attributes) {
        when (elementName(local, name)) {
            "navpoint" -> {
                val entry = PendingToc()
                (stack.lastOrNull()?.children ?: entries) += entry
                stack += entry
            }
            "navlabel" -> { inLabel = true; label.setLength(0) }
            "content" -> attrs.value("src")?.let { stack.lastOrNull()?.target = resolveReference(path, it)?.sourceId }
        }
    }
    override fun characters(chars: CharArray, start: Int, length: Int) {
        if (inLabel) label.append(chars, start, length)
    }
    override fun endElement(uri: String, local: String, name: String) {
        when (elementName(local, name)) {
            "navlabel" -> { stack.lastOrNull()?.title = label.toString().normalizedText(); inLabel = false }
            "navpoint" -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
    }
}
