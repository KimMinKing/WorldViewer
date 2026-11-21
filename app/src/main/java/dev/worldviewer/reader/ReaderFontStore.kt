package dev.worldviewer.reader

import android.graphics.Typeface
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One user-supplied reading font, kept with other backed-up reader data. */
class ReaderFontStore(private val directory: File) {
    fun load(): Typeface? = file().takeIf(File::isFile)?.let { Typeface.createFromFile(it) }

    fun import(input: InputStream): Typeface {
        if (!directory.exists() && !directory.mkdirs()) error("폰트 저장 공간을 만들 수 없습니다.")
        val temporary = File.createTempFile("font-", ".partial", directory)
        try {
            var size = 0L
            input.use { source -> temporary.outputStream().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > MAX_BYTES) error("폰트 파일은 20MiB 이하여야 합니다.")
                    target.write(buffer, 0, read)
                }
            } }
            if (size < 12 || !hasSupportedHeader(temporary)) error("TTF 또는 OTF 폰트 파일을 선택하세요.")
            val typeface = Typeface.createFromFile(temporary)
            try {
                Files.move(temporary.toPath(), file().toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return typeface
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        if (file().exists() && !file().delete()) error("기존 폰트를 제거하지 못했습니다.")
    }

    private fun file() = File(directory, FILE_NAME)

    companion object {
        const val FILE_NAME = "reading.font"
        const val MAX_BYTES = 20L * 1024 * 1024

        fun hasSupportedHeader(file: File): Boolean = file.inputStream().use { input ->
            val bytes = ByteArray(4)
            if (input.read(bytes) != 4) return@use false
            val signature = String(bytes, Charsets.US_ASCII)
            bytes.contentEquals(byteArrayOf(0, 1, 0, 0)) || signature == "OTTO" || signature == "ttcf"
        }
    }
}
