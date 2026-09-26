package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CcCedictDictionaryTest {
    @Test
    fun `exact simplified and traditional lookups preserve wuxia senses`() {
        copiedDictionary().use { dictionary ->
            val jianghu = dictionary.lookup("江湖").single()
            assertEquals("jiang1 hu2", jianghu.pinyin)
            assertTrue(jianghu.meanings.any { "wuxia" in it })

            val simplified = dictionary.lookup("掌门").single()
            val traditional = dictionary.lookup("掌門").single()
            assertEquals(simplified, traditional)
            assertTrue(simplified.meanings.any { "sect leader" in it })
        }
    }

    @Test
    fun `multiple senses and missing terms are explicit`() {
        copiedDictionary().use { dictionary ->
            assertTrue(dictionary.lookup("大家").single().meanings.size >= 3)
            assertTrue(dictionary.lookup("不存在的自造词").isEmpty())
        }
    }

    @Test
    fun `packaged index metadata is pinned`() {
        copiedDictionary().use { dictionary ->
            assertEquals("2026-09-26T00:33:45Z", dictionary.providerVersion)
            assertEquals(
                "12d2b21566e98a19bf1a40a30cdab90b5ff5c687bad33a42823b7a5e6c4448d7",
                dictionary.sourceSha256,
            )
        }
    }

    private fun copiedDictionary(): CcCedictDictionary {
        val target = Files.createTempFile("cc-cedict-test", ".bin").toFile()
        checkNotNull(javaClass.classLoader?.getResourceAsStream(CcCedictDictionary.ASSET_PATH)).use { input ->
            target.outputStream().use(input::copyTo)
        }
        return CcCedictDictionary.open(target).also { target.deleteOnExit() }
    }
}
