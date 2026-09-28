package dev.worldviewer.reader

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubChapter(val title: String, val utf16Offset: Int)

data class ImportedEpub(
    val text: ImportedText,
    val sourceFile: File,
    val sourceSha256: String,
    val title: String?,
    val chapters: List<EpubChapter>,
)

/** Imports a DRM-free EPUB while keeping both the source archive and derived UTF-8 text. */
object EpubImporter {
    const val MAX_SOURCE_BYTES = 200L * 1024L * 1024L
    const val MAX_EXPANDED_BYTES = 300L * 1024L * 1024L
    private const val MAX_ENTRIES = 20_000
    private const val MAX_XML_BYTES = 4L * 1024L * 1024L

    fun import(
        input: InputStream,
        importDirectory: File,
        sourceDirectory: File,
        maxSourceBytes: Long = MAX_SOURCE_BYTES,
    ): ImportedEpub {
        sourceDirectory.mkdirsOrThrow("EPUB 원본 저장 공간을 만들 수 없습니다.")
        importDirectory.mkdirsOrThrow("가져오기 저장 공간을 만들 수 없습니다.")
        val temporarySource = File.createTempFile("incoming-", ".epub.partial", sourceDirectory)
        val temporaryText = File.createTempFile("epub-text-", ".partial", importDirectory)
        var finalSource: File? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { source ->
                temporarySource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > maxSourceBytes - copied) {
                            throw TxtImportException("EPUB이 현재 허용 크기 200MiB를 초과했습니다.")
                        }
                        copied += read
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val sourceSha = digest.digest().toHex()
            val parsed = parse(temporarySource, temporaryText)
            val staged = TxtImporter.stage(
                temporaryText.inputStream(),
                importDirectory,
                maxBytes = MAX_EXPANDED_BYTES,
            )
            val importedText = TxtImporter.commit(staged, "UTF-8")
            finalSource = File(sourceDirectory, "$sourceSha.epub")
            if (finalSource.exists()) temporarySource.delete()
            else if (!temporarySource.renameTo(finalSource)) {
                throw TxtImportException("EPUB 원본을 안전하게 보존하지 못했습니다.")
            }
            return ImportedEpub(importedText, finalSource, sourceSha, parsed.title, parsed.chapters)
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("EPUB을 가져오지 못했습니다.", error)
        } finally {
            if (finalSource == null) temporarySource.delete()
            temporaryText.delete()
        }
    }

    private data class Parsed(val title: String?, val chapters: List<EpubChapter>)

    private fun parse(file: File, outputFile: File): Parsed = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        if (entries.size > MAX_ENTRIES) throw TxtImportException("EPUB 내부 파일이 너무 많습니다.")
        var expanded = 0L
        entries.forEach { entry ->
            validateEntryName(entry.name)
            if (entry.size > 0) {
                expanded += entry.size
                if (expanded > MAX_EXPANDED_BYTES) throw TxtImportException("EPUB 압축 해제 크기가 너무 큽니다.")
            }
        }
        val mimetype = zip.getEntry("mimetype")?.let { readUtf8(zip, it, 100) }?.trim()
        if (mimetype != "application/epub+zip") throw TxtImportException("올바른 EPUB 파일이 아닙니다.")
        if (zip.getEntry("META-INF/encryption.xml") != null) {
            throw TxtImportException("암호화되거나 DRM이 적용된 EPUB은 지원하지 않습니다.")
        }
        val container = xml(readUtf8(zip, required(zip, "META-INF/container.xml"), MAX_XML_BYTES))
        val rootPath = (container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element)
            ?.getAttribute("full-path")?.takeIf(String::isNotBlank)
            ?: throw TxtImportException("EPUB 패키지 문서를 찾을 수 없습니다.")
        validateEntryName(rootPath)
        val opf = xml(readUtf8(zip, required(zip, rootPath), MAX_XML_BYTES))
        val title = opf.getElementsByTagNameNS("*", "title").item(0)?.textContent?.trim()?.take(200)
        val manifest = buildMap {
            val nodes = opf.getElementsByTagNameNS("*", "item")
            for (index in 0 until nodes.length) {
                val item = nodes.item(index) as? Element ?: continue
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotBlank() && href.isNotBlank()) put(id, resolve(rootPath, href))
            }
        }
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        val chapters = ArrayList<EpubChapter>()
        var utf16Offset = 0
        var extractedBytes = 0L
        outputFile.writer(StandardCharsets.UTF_8).buffered().use { output ->
            for (index in 0 until spine.length) {
                val idref = (spine.item(index) as? Element)?.getAttribute("idref") ?: continue
                val path = manifest[idref] ?: continue
                val entry = required(zip, path)
                val chapterText = xhtmlText(readUtf8(zip, entry, MAX_XML_BYTES * 8)).trim()
                if (chapterText.isBlank()) continue
                if (chapters.isNotEmpty()) {
                    output.write("\n\n")
                    utf16Offset += 2
                    extractedBytes += 2
                }
                extractedBytes += chapterText.toByteArray(StandardCharsets.UTF_8).size
                if (extractedBytes > MAX_EXPANDED_BYTES) throw TxtImportException("EPUB 본문 크기가 너무 큽니다.")
                val offset = utf16Offset
                output.write(chapterText)
                utf16Offset += chapterText.length
                val heading = chapterText.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.trim()?.take(ManualChapterStore.MAX_TITLE_UTF16)
                chapters += EpubChapter(heading ?: "장 ${chapters.size + 1}", offset)
            }
        }
        if (chapters.isEmpty()) throw TxtImportException("EPUB 읽기 순서에서 본문을 찾지 못했습니다.")
        Parsed(title, chapters)
    }

    private fun xhtmlText(source: String): String {
        val document = xml(sanitizeXhtml(source))
        val output = StringBuilder()
        fun visit(element: org.w3c.dom.Node) {
            val name = element.localName?.lowercase() ?: element.nodeName.substringAfter(':').lowercase()
            if (name in setOf("script", "style", "svg", "math")) return
            if (name in BLOCK_TAGS && output.isNotEmpty() && output.last() != '\n') output.append('\n')
            if (element.nodeType == org.w3c.dom.Node.TEXT_NODE) {
                val normalized = element.nodeValue.orEmpty().replace(Regex("\\s+"), " ")
                if (normalized.isNotBlank()) {
                    if (output.isNotEmpty() && output.last() !in charArrayOf(' ', '\n')) output.append(' ')
                    output.append(normalized.trim())
                }
            } else {
                val children = element.childNodes
                for (index in 0 until children.length) visit(children.item(index))
            }
            if (name in BLOCK_TAGS && output.isNotEmpty() && output.last() != '\n') output.append('\n')
        }
        val bodies = document.getElementsByTagNameNS("*", "body")
        visit(bodies.item(0) ?: document.documentElement)
        return output.toString().lineSequence().map(String::trim).joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }

    private fun sanitizeXhtml(source: String): String {
        var result = source.replace(Regex("<!DOCTYPE[^>]*(?:\\[[\\s\\S]*?]]\\s*)?>", RegexOption.IGNORE_CASE), "")
        HTML_ENTITIES.forEach { (name, replacement) -> result = result.replace("&$name;", replacement) }
        return result
    }

    private fun xml(source: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(withoutDoctype(source))))

    private fun withoutDoctype(source: String): String {
        if (source.contains("<!ENTITY", ignoreCase = true)) {
            throw TxtImportException("외부 엔티티가 포함된 EPUB XML은 지원하지 않습니다.")
        }
        return source.replace(Regex("<!DOCTYPE[^>]*(?:\\[[\\s\\S]*?]]\\s*)?>", RegexOption.IGNORE_CASE), "")
    }

    private fun readUtf8(zip: ZipFile, entry: ZipEntry, maxBytes: Long): String {
        if (entry.size > maxBytes) throw TxtImportException("EPUB 내부 문서가 너무 큽니다: ${entry.name}")
        val bytes = zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > maxBytes) throw TxtImportException("EPUB 내부 문서가 너무 큽니다: ${entry.name}")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun required(zip: ZipFile, path: String): ZipEntry =
        zip.getEntry(path) ?: throw TxtImportException("EPUB 내부 파일을 찾을 수 없습니다: $path")

    private fun resolve(baseFile: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        val resolved = URI(null, null, baseFile, null).resolve(cleanHref).normalize().path.removePrefix("/")
        validateEntryName(resolved)
        return resolved
    }

    private fun validateEntryName(name: String) {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.split('/').any { it == ".." }) {
            throw TxtImportException("안전하지 않은 EPUB 내부 경로입니다.")
        }
    }

    private fun File.mkdirsOrThrow(message: String) {
        if (!exists() && !mkdirs()) throw TxtImportException(message)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "br", "div", "footer", "h1", "h2", "h3",
        "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "title", "ul",
    )
    private val HTML_ENTITIES = mapOf(
        "nbsp" to "&#160;", "copy" to "&#169;", "reg" to "&#174;", "hellip" to "&#8230;",
        "mdash" to "&#8212;", "ndash" to "&#8211;", "lsquo" to "&#8216;", "rsquo" to "&#8217;",
        "ldquo" to "&#8220;", "rdquo" to "&#8221;", "middot" to "&#183;", "times" to "&#215;",
    )
}
