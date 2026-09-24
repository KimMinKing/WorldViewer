package dev.worldviewer.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class UnihanPinyinTableTest {
    @Test
    fun `generated table exposes pinned Unicode readings`() {
        val table = loadTable()

        assertEquals(44_348, table.entryCount)
        assertEquals(listOf("zhōng"), table.readings('中'.code))
        assertEquals(listOf("wàn", "mò"), table.readings('万'.code))
        assertEquals("lè", table.defaultReading('樂'.code))
        assertEquals("hē", table.defaultReading(0x20000))
        assertNull(table.defaultReading(0x20BB7))
    }

    @Test
    fun `adapter preserves UTF-16 anchors literals and unsupported Han`() {
        val source = "A中🙂𠀀𠮷"

        assertEquals(
            listOf(
                PinyinReading(0, 1, "A", null),
                PinyinReading(1, 2, "中", "zhōng"),
                PinyinReading(2, 4, "🙂", null),
                PinyinReading(4, 6, "𠀀", "hē"),
                PinyinReading(6, 8, "𠮷", null),
            ),
            UnihanPinyinAdapter(loadTable()).convert(source),
        )
    }

    @Test
    fun `loader rejects truncated data`() {
        val bytes = assetBytes()

        assertThrows(IllegalArgumentException::class.java) {
            UnihanPinyinTable.load(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
        }
    }

    @Test
    fun `loader rejects an unknown format version`() {
        val bytes = assetBytes().also { it[4] = 99 }

        assertThrows(IllegalArgumentException::class.java) {
            UnihanPinyinTable.load(ByteArrayInputStream(bytes))
        }
    }

    private fun loadTable(): UnihanPinyinTable =
        UnihanPinyinTable.load(ByteArrayInputStream(assetBytes()))

    private fun assetBytes(): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(UnihanPinyinTable.ASSET_PATH)) {
            "Missing ${UnihanPinyinTable.ASSET_PATH} test resource"
        }.use { it.readBytes() }
}
