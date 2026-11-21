package dev.worldviewer.reader

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.BreakIterator
import java.util.Locale

data class TextIndexSummary(
    val indexFile: File,
    val dataFile: File,
    val blockCount: Int,
    val utf16Length: Int,
    val dataByteCount: Long,
)

object StreamingTextIndex {
    const val DEFAULT_BLOCK_UTF16 = 768

    fun build(
        original: File,
        destinationDirectory: File,
        contentId: String,
        encoding: String,
        maxBlockUtf16: Int = DEFAULT_BLOCK_UTF16,
    ): TextIndexSummary {
        require(maxBlockUtf16 > 0)
        require(CONTENT_ID.matches(contentId))
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw TxtImportException("본문 인덱스 저장 공간을 만들 수 없습니다.")
        }

        val indexTarget = File(destinationDirectory, "$contentId.index")
        val dataTarget = File(destinationDirectory, "$contentId.blocks")
        val indexTemp = File.createTempFile("$contentId-", ".index.partial", destinationDirectory)
        val dataTemp = File.createTempFile("$contentId-", ".blocks.partial", destinationDirectory)
        try {
            var blockCount = 0
            var totalUtf16 = 0
            var dataOffset = 0L
            RandomAccessFile(indexTemp, "rw").use { index ->
                index.writeInt(MAGIC)
                index.writeInt(VERSION)
                index.writeInt(0)
                index.writeInt(0)
                FileOutputStream(dataTemp).use { rawData ->
                    BufferedOutputStream(rawData).use { data ->
                        StrictTextReader.open(original, encoding).use { reader ->
                            val readBuffer = CharArray(4096)
                            val carry = StringBuilder(maxBlockUtf16 + readBuffer.size)

                            fun emit(length: Int) {
                                if (Thread.currentThread().isInterrupted) {
                                    throw InterruptedException("본문 인덱싱이 취소됐습니다.")
                                }
                                val text = carry.substring(0, length)
                                val encoded = text.toByteArray(StandardCharsets.UTF_8)
                                index.writeLong(dataOffset)
                                index.writeInt(encoded.size)
                                index.writeInt(totalUtf16)
                                index.writeInt(text.length)
                                data.write(encoded)
                                dataOffset += encoded.size
                                totalUtf16 = Math.addExact(totalUtf16, text.length)
                                blockCount = Math.addExact(blockCount, 1)
                                carry.delete(0, length)
                            }

                            while (true) {
                                val count = reader.read(readBuffer)
                                if (count < 0) break
                                carry.append(readBuffer, 0, count)
                                while (carry.length > maxBlockUtf16) {
                                    emit(safeBoundary(carry, maxBlockUtf16))
                                }
                            }
                            while (carry.length > maxBlockUtf16) {
                                emit(safeBoundary(carry, maxBlockUtf16))
                            }
                            if (carry.isNotEmpty()) emit(carry.length)
                        }
                        data.flush()
                        rawData.fd.sync()
                    }
                }
                index.seek(8)
                index.writeInt(blockCount)
                index.writeInt(totalUtf16)
                index.fd.sync()
            }
            replace(indexTemp, indexTarget)
            try {
                replace(dataTemp, dataTarget)
            } catch (error: Exception) {
                indexTarget.delete()
                throw error
            }
            return TextIndexSummary(indexTarget, dataTarget, blockCount, totalUtf16, dataOffset)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TxtImportException("본문 인덱싱이 취소됐습니다.", error)
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("본문 인덱스를 만들지 못했습니다.", error)
        } finally {
            indexTemp.delete()
            dataTemp.delete()
        }
    }

    private fun safeBoundary(text: StringBuilder, maximum: Int): Int {
        val sample = text.toString()
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(sample) }
        val hardBoundary = boundaries.preceding(maximum + 1)
        if (hardBoundary <= 0) return boundaries.following(0)
        val preferredFrom = maximum / 2
        var paragraphBoundary = 0
        var sentenceBoundary = 0
        var boundary = boundaries.first()
        while (boundary != BreakIterator.DONE && boundary <= hardBoundary) {
            if (boundary >= preferredFrom && boundary > 0) {
                when (sample[boundary - 1]) {
                    '\n', '\r' -> paragraphBoundary = boundary
                    '。', '！', '？', '.', '!', '?' -> sentenceBoundary = boundary
                }
            }
            boundary = boundaries.next()
        }
        return paragraphBoundary.takeIf { it > 0 }
            ?: sentenceBoundary.takeIf { it > 0 }
            ?: hardBoundary
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private const val MAGIC = 0x57564958
    private const val VERSION = 1
    private const val HEADER_BYTES = 16L
    private const val ENTRY_BYTES = 20L
    private val CONTENT_ID = Regex("[0-9a-f]{64}")
    class Reader(
        indexFile: File,
        dataFile: File,
    ) : Closeable {
        private val index = RandomAccessFile(indexFile, "r")
        private val data = RandomAccessFile(dataFile, "r")
        val blockCount: Int
        val utf16Length: Int

        init {
            if (index.readInt() != MAGIC || index.readInt() != VERSION) {
                close()
                throw TxtImportException("지원하지 않는 본문 인덱스입니다.")
            }
            blockCount = index.readInt()
            utf16Length = index.readInt()
            if (blockCount < 0 || utf16Length < 0 || index.length() != HEADER_BYTES + blockCount * ENTRY_BYTES) {
                close()
                throw TxtImportException("본문 인덱스가 손상됐습니다.")
            }
        }

        fun blockAt(position: Int): TextBlock {
            require(position in 0 until blockCount)
            index.seek(HEADER_BYTES + position * ENTRY_BYTES)
            val dataOffset = index.readLong()
            val dataLength = index.readInt()
            val startUtf16 = index.readInt()
            val utf16Length = index.readInt()
            if (dataOffset < 0 || dataLength < 0 || dataOffset + dataLength > data.length()) {
                throw TxtImportException("본문 블록 위치가 손상됐습니다.")
            }
            val bytes = ByteArray(dataLength)
            data.seek(dataOffset)
            data.readFully(bytes)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (text.length != utf16Length) throw TxtImportException("본문 블록 길이가 손상됐습니다.")
            return TextBlock(position.toLong(), startUtf16, text)
        }

        fun positionForUtf16(globalUtf16: Int): Int {
            require(globalUtf16 in 0 until utf16Length)
            var low = 0
            var high = blockCount - 1
            while (low <= high) {
                val middle = (low + high).ushr(1)
                index.seek(HEADER_BYTES + middle * ENTRY_BYTES + START_UTF16_ENTRY_OFFSET)
                val startUtf16 = index.readInt()
                val length = index.readInt()
                when {
                    globalUtf16 < startUtf16 -> high = middle - 1
                    globalUtf16 >= startUtf16 + length -> low = middle + 1
                    else -> return middle
                }
            }
            throw TxtImportException("본문의 UTF-16 위치를 찾지 못했습니다.")
        }

        override fun close() {
            index.close()
            data.close()
        }
    }

    private const val START_UTF16_ENTRY_OFFSET = 12L
}
