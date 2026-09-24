package dev.worldviewer.reader

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object StrictTextReader {
    fun open(original: File, encoding: String): InputStreamReader {
        val input = BufferedInputStream(original.inputStream())
        val (charset, bom) = when (encoding) {
            "UTF-8" -> StandardCharsets.UTF_8 to byteArrayOf()
            "UTF-8 BOM" -> StandardCharsets.UTF_8 to UTF8_BOM
            "UTF-16LE BOM" -> StandardCharsets.UTF_16LE to UTF16_LE_BOM
            "UTF-16BE BOM" -> StandardCharsets.UTF_16BE to UTF16_BE_BOM
            "UTF-16LE" -> StandardCharsets.UTF_16LE to byteArrayOf()
            "UTF-16BE" -> StandardCharsets.UTF_16BE to byteArrayOf()
            "GB18030" -> Charset.forName("GB18030") to byteArrayOf()
            "Big5" -> Charset.forName("Big5") to byteArrayOf()
            else -> {
                input.close()
                throw TxtImportException("지원하지 않는 본문 인코딩입니다.")
            }
        }
        if (bom.isNotEmpty()) {
            val actual = ByteArray(bom.size)
            var offset = 0
            while (offset < actual.size) {
                val read = input.read(actual, offset, actual.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset != actual.size || !actual.contentEquals(bom)) {
                input.close()
                throw TxtImportException("원본의 BOM과 저장된 인코딩 정보가 다릅니다.")
            }
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(input, decoder)
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
