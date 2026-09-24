package dev.worldviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionResolverTest {
    private val hash = "a".repeat(64)

    @Test
    fun `corrects block index when regenerated boundaries changed`() {
        val blocks = mapOf(
            0 to TextBlock(0, 0, "abc"),
            1 to TextBlock(1, 3, "def"),
            2 to TextBlock(2, 6, "ghi"),
            3 to TextBlock(3, 9, "j"),
        )
        val saved = ReadingPosition(hash, blockIndex = 8, utf16Offset = 5, quoteBefore = "de", quoteAfter = "fg")

        val resolved = ReadingPositionResolver.resolve(hash, 10, 1, blocks, saved)

        assertEquals(saved.copy(blockIndex = 1), resolved)
    }

    @Test
    fun `rejects a position for another document version`() {
        val saved = ReadingPosition(hash, 0, 1, "a", "b")

        assertNull(
            ReadingPositionResolver.resolve(
                currentDocumentSha256 = "b".repeat(64),
                documentUtf16Length = 2,
                containingBlockPosition = 0,
                availableBlocks = mapOf(0 to TextBlock(0, 0, "ab")),
                saved = saved,
            ),
        )
    }

    @Test
    fun `rejects changed or unavailable quote context`() {
        val saved = ReadingPosition(hash, 0, 3, "bc", "de")
        val changed = mapOf(0 to TextBlock(0, 0, "abcXef"))
        val missingNeighbor = mapOf(1 to TextBlock(1, 2, "cd"))

        assertNull(ReadingPositionResolver.resolve(hash, 6, 0, changed, saved))
        assertNull(ReadingPositionResolver.resolve(hash, 6, 1, missingNeighbor, saved))
    }
}
