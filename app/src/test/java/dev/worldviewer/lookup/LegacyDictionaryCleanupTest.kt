package dev.worldviewer.lookup

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDictionaryCleanupTest {
    @Test
    fun `only discontinued dictionary and translation model files are removed`() {
        val root = Files.createTempDirectory("worldviewer-legacy-dictionary").toFile()
        root.resolve("dictionaries/cc_cedict.bin").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("dictionary")
        }
        root.resolve("com.google.mlkit.translate.models/en_ko/model.bin").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("model")
        }
        val unrelated = root.resolve("pinyin/user-data.bin").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("keep")
        }

        assertEquals(2, LegacyDictionaryCleanup.cleanFiles(root))
        assertFalse(root.resolve("dictionaries").exists())
        assertFalse(root.resolve("com.google.mlkit.translate.models").exists())
        assertTrue(unrelated.isFile)
    }
}
