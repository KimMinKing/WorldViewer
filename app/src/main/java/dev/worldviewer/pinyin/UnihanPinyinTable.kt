package dev.worldviewer.pinyin

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Immutable lookup table generated from Unicode 17.0 Unihan kMandarin data. */
class UnihanPinyinTable private constructor(
    private val codePoints: IntArray,
    private val readingIds: ShortArray,
    private val readingValues: Array<String>,
) {
    fun readings(codePoint: Int): List<String> {
        return readingValue(codePoint)?.split(' ').orEmpty()
    }

    fun defaultReading(codePoint: Int): String? = readingValue(codePoint)?.substringBefore(' ')

    val entryCount: Int
        get() = codePoints.size

    private fun readingValue(codePoint: Int): String? {
        val index = codePoints.binarySearch(codePoint)
        if (index < 0) return null
        return readingValues[readingIds[index].toInt() and 0xffff]
    }

    companion object {
        const val UNICODE_VERSION = "17.0.0"
        const val ASSET_PATH = "pinyin/unihan_kmandarin_17.bin"
        private const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 12

        fun load(input: InputStream): UnihanPinyinTable = input.use { stream ->
            val bytes = stream.readBytes()
            require(bytes.size >= HEADER_SIZE) { "Truncated Unihan pinyin table" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.get().toInt() == 'W'.code)
            require(buffer.get().toInt() == 'V'.code)
            require(buffer.get().toInt() == 'K'.code)
            require(buffer.get().toInt() == 'M'.code) { "Invalid Unihan pinyin table magic" }
            require(buffer.get().toInt() == FORMAT_VERSION) { "Unsupported Unihan pinyin table version" }
            require(buffer.get().toInt() == 0) { "Unsupported Unihan pinyin table flags" }

            val valueCount = buffer.short.toInt() and 0xffff
            val entryCount = buffer.int
            require(valueCount > 0 && entryCount > 0) { "Empty Unihan pinyin table" }

            val values = Array(valueCount) {
                val length = readUnsignedShort(buffer, "reading length")
                require(length > 0 && buffer.remaining() >= length) { "Truncated Unihan reading table" }
                val valueBytes = ByteArray(length)
                buffer.get(valueBytes)
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(valueBytes))
                    .toString()
            }

            require(buffer.remaining().toLong() == entryCount.toLong() * 6L) {
                "Invalid Unihan entry table size"
            }
            val codePoints = IntArray(entryCount)
            val readingIds = ShortArray(entryCount)
            var previousCodePoint = -1
            repeat(entryCount) { index ->
                val codePoint = buffer.int
                val readingId = buffer.short.toInt() and 0xffff
                require(codePoint > previousCodePoint && Character.isValidCodePoint(codePoint)) {
                    "Unsorted or invalid Unihan code point"
                }
                require(readingId < values.size) { "Invalid Unihan reading id" }
                codePoints[index] = codePoint
                readingIds[index] = readingId.toShort()
                previousCodePoint = codePoint
            }
            UnihanPinyinTable(codePoints, readingIds, values)
        }

        private fun readUnsignedShort(buffer: ByteBuffer, field: String): Int {
            require(buffer.remaining() >= 2) { "Truncated Unihan $field" }
            return buffer.short.toInt() and 0xffff
        }
    }
}
