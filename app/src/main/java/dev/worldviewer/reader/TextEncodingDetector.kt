package dev.worldviewer.reader

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class EncodingCandidate(
    val encoding: String,
    val preview: String,
    val automatic: Boolean,
)

internal object TextEncodingDetector {
    const val SAMPLE_BYTES = 64 * 1024

    fun candidates(sample: ByteArray): List<EncodingCandidate> {
        bomCandidate(sample)?.let { return listOf(it) }
        decodeSample(sample, StandardCharsets.UTF_8)?.takeIf(::looksLikeText)?.let { text ->
            return listOf(EncodingCandidate("UTF-8", preview(text), automatic = true))
        }

        val candidates = mutableListOf<EncodingCandidate>()
        addCandidate(candidates, "UTF-16LE", StandardCharsets.UTF_16LE, sample, alignment = 2)
        addCandidate(candidates, "UTF-16BE", StandardCharsets.UTF_16BE, sample, alignment = 2)
        addCandidate(candidates, "GB18030", Charset.forName("GB18030"), sample, alignment = 1)
        addCandidate(candidates, "Big5", Charset.forName("Big5"), sample, alignment = 1)
        return candidates.distinctBy { it.encoding }
    }

    private fun bomCandidate(sample: ByteArray): EncodingCandidate? {
        val definition = when {
            sample.startsWith(UTF8_BOM) -> Triple("UTF-8 BOM", StandardCharsets.UTF_8, UTF8_BOM.size)
            sample.startsWith(UTF16_LE_BOM) -> Triple("UTF-16LE BOM", StandardCharsets.UTF_16LE, UTF16_LE_BOM.size)
            sample.startsWith(UTF16_BE_BOM) -> Triple("UTF-16BE BOM", StandardCharsets.UTF_16BE, UTF16_BE_BOM.size)
            else -> return null
        }
        val body = sample.copyOfRange(definition.third, sample.size)
        val decoded = decodeSample(body, definition.second) ?: return null
        return EncodingCandidate(definition.first, preview(decoded), automatic = true)
    }

    private fun addCandidate(
        target: MutableList<EncodingCandidate>,
        name: String,
        charset: Charset,
        sample: ByteArray,
        alignment: Int,
    ) {
        val decoded = decodeSample(sample, charset, alignment) ?: return
        if (looksLikeText(decoded)) {
            target += EncodingCandidate(name, preview(decoded), automatic = false)
        }
    }

    private fun decodeSample(
        bytes: ByteArray,
        charset: Charset,
        alignment: Int = 1,
    ): String? {
        if (bytes.isEmpty()) return ""
        val alignedSize = bytes.size - bytes.size % alignment
        val minimum = maxOf(0, alignedSize - 4 * alignment)
        var size = alignedSize
        while (size >= minimum) {
            try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes, 0, size)).toString()
            } catch (_: CharacterCodingException) {
                size -= alignment
            }
        }
        return null
    }

    private fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return true
        val controls = text.count { character ->
            Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t'
        }
        return controls * 50 <= text.length
    }

    private fun preview(text: String): String {
        val normalized = buildString(minOf(text.length, PREVIEW_CHARS)) {
            text.take(PREVIEW_CHARS).forEach { character ->
                append(
                    when (character) {
                        '\r' -> ' '
                        '\n' -> '↵'
                        '\t' -> ' '
                        else -> character
                    },
                )
            }
        }
        return normalized.trim().ifEmpty { "(빈 문서)" }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val PREVIEW_CHARS = 160
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
