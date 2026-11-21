package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubImporterTest {
    @Test
    fun importsSpineOrderAndPreservesSource() {
        val root = Files.createTempDirectory("worldviewer-epub").toFile()
        val bytes = epub(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "OPS/book.opf" to opf,
            "OPS/text/one.xhtml" to xhtml("第一章", "你好，世界。"),
            "OPS/text/two.xhtml" to xhtml("第二章", "再见。"),
        )

        val imported = EpubImporter.import(
            ByteArrayInputStream(bytes), root.resolve("imports"), root.resolve("epub-sources"),
        )

        assertTrue(imported.sourceFile.readBytes().contentEquals(bytes))
        assertEquals("测试书", imported.title)
        assertEquals(listOf("第一章", "第二章"), imported.chapters.map(EpubChapter::title))
        assertTrue(imported.chapters[1].utf16Offset > imported.chapters[0].utf16Offset)
        assertEquals("第一章\n你好，世界。\n\n第二章\n再见。", imported.text.storedFile.readText())
        assertEquals("UTF-8", imported.text.encoding)
    }

    @Test
    fun followsSpineWhenManifestOrderDiffers() {
        val root = Files.createTempDirectory("worldviewer-epub-spine").toFile()
        val reversedSpine = opf.replace(
            "<spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine>",
            "<spine><itemref idref=\"two\"/><itemref idref=\"one\"/></spine>",
        )
        val bytes = epub(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "OPS/book.opf" to reversedSpine,
            "OPS/text/one.xhtml" to xhtml("第一章", "你好。"),
            "OPS/text/two.xhtml" to xhtml("第二章", "再见。"),
        )

        val imported = EpubImporter.import(
            ByteArrayInputStream(bytes), root.resolve("imports"), root.resolve("sources"),
        )

        assertEquals(listOf("第二章", "第一章"), imported.chapters.map(EpubChapter::title))
        assertEquals("第二章\n再见。\n\n第一章\n你好。", imported.text.storedFile.readText())
    }

    @Test(expected = TxtImportException::class)
    fun rejectsEncryptedPublication() {
        val root = Files.createTempDirectory("worldviewer-epub-drm").toFile()
        val bytes = epub(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "META-INF/encryption.xml" to "<encryption/>",
            "OPS/book.opf" to opf,
            "OPS/text/one.xhtml" to xhtml("第一章", "内容"),
            "OPS/text/two.xhtml" to xhtml("第二章", "内容"),
        )
        EpubImporter.import(ByteArrayInputStream(bytes), root.resolve("imports"), root.resolve("sources"))
    }

    @Test
    fun publicationMetadataRoundTrips() {
        val root = Files.createTempDirectory("worldviewer-epub-meta").toFile()
        val document = "a".repeat(64)
        val source = "b".repeat(64)
        val expected = EpubPublication(
            document, source, "$source.epub", listOf(EpubChapter("第一章", 0), EpubChapter("第二章", 42)),
        )
        val store = EpubPublicationStore(root)
        store.save(expected)
        assertEquals(expected, store.load(document))
    }

    @Test
    fun acceptsCommonEpub2DoctypeAndNbsp() {
        val root = Files.createTempDirectory("worldviewer-epub2").toFile()
        val page = """<?xml version="1.0"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章</h1><p>你&nbsp;好</p></body></html>"""
        val bytes = epub(
            "mimetype" to "application/epub+zip", "META-INF/container.xml" to container,
            "OPS/book.opf" to opf, "OPS/text/one.xhtml" to page,
            "OPS/text/two.xhtml" to xhtml("第二章", "内容"),
        )
        val imported = EpubImporter.import(ByteArrayInputStream(bytes), root.resolve("imports"), root.resolve("sources"))
        assertTrue(imported.text.storedFile.readText().contains("你 好"))
    }

    private fun epub(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun xhtml(title: String, body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
        <body><h1>$title</h1><p>$body</p></body></html>
    """.trimIndent()

    private val container = """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private val opf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试书</dc:title></metadata>
          <manifest>
            <item id="one" href="text/one.xhtml" media-type="application/xhtml+xml"/>
            <item id="two" href="text/two.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine><itemref idref="one"/><itemref idref="two"/></spine>
        </package>
    """.trimIndent()
}
