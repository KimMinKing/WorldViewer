package dev.worldviewer.reader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ReaderTheme {
    LIGHT,
    DARK,
}

data class ReaderStyle(
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val lineSpacingPercent: Int = DEFAULT_LINE_SPACING_PERCENT,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val textColorArgb: Int? = null,
    val backgroundColorArgb: Int? = null,
    val showSavedMeaningMarks: Boolean = true,
) {
    companion object {
        const val DEFAULT_FONT_SIZE_SP = 19
        const val DEFAULT_LINE_SPACING_PERCENT = 115
        val DEFAULT = ReaderStyle()
    }
}

object ReaderStyleColors {
    val TEXT = listOf(0xFF23221F.toInt(), 0xFF4B3420.toInt(), 0xFF173D35.toInt(),
        0xFFE8E6E0.toInt(), 0xFFFFFFFF.toInt(), 0xFFC6DFF2.toInt())
    val BACKGROUND = listOf(0xFFFAF8F3.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6EFE9.toInt(),
        0xFF181918.toInt(), 0xFF102838.toInt(), 0xFF30251F.toInt())

    fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 0xFF) / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val light = maxOf(luminance(first), luminance(second))
        val dark = minOf(luminance(first), luminance(second))
        return (light + 0.05) / (dark + 0.05)
    }
}

fun ReaderStyle.resolvedTextColor(): Int = textColorArgb ?: when (theme) {
    ReaderTheme.LIGHT -> 0xFF23221F.toInt()
    ReaderTheme.DARK -> 0xFFE8E6E0.toInt()
}

fun ReaderStyle.resolvedBackgroundColor(): Int = backgroundColorArgb ?: when (theme) {
    ReaderTheme.LIGHT -> 0xFFFAF8F3.toInt()
    ReaderTheme.DARK -> 0xFF181918.toInt()
}

fun ReaderStyle.hasReadableContrast(): Boolean =
    ReaderStyleColors.contrast(resolvedTextColor(), resolvedBackgroundColor()) >= 7.0

data class ReaderStylePreferences(
    val defaultStyle: ReaderStyle = ReaderStyle.DEFAULT,
    val bookStyles: Map<String, ReaderStyle> = emptyMap(),
) {
    fun resolved(bookId: String?): ReaderStyle = bookId?.let(bookStyles::get) ?: defaultStyle
}

class ReaderStyleStore(private val directory: File) {
    fun load(): ReaderStylePreferences? {
        val target = File(directory, FILE_NAME)
        if (!target.isFile) return null
        return DataInputStream(BufferedInputStream(target.inputStream())).use { input ->
            if (input.readInt() != MAGIC) throw TxtImportException("독서 스타일 정보가 손상됐습니다.")
            val version = input.readInt()
            if (version !in 1..VERSION) throw TxtImportException("지원하지 않는 독서 스타일 정보입니다.")
            val defaultStyle = input.readStyle(version)
            val count = input.readInt()
            if (count !in 0..MAX_BOOKS) throw TxtImportException("책별 스타일 개수가 올바르지 않습니다.")
            val entries = List(count) { input.readUTF() to input.readStyle(version) }
            if (entries.map(Pair<String, ReaderStyle>::first).toSet().size != entries.size) {
                throw TxtImportException("책별 스타일에 중복 식별자가 있습니다.")
            }
            val preferences = ReaderStylePreferences(defaultStyle, entries.toMap()).also(::validate)
            input.requirePersistentRecordEnd("독서 스타일 정보")
            preferences
        }
    }

    fun save(preferences: ReaderStylePreferences) {
        saveInternal(preferences) { temporary ->
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    write(output, preferences)
                    output.flush()
                    raw.fd.sync()
                }
            }
        }
    }

    internal fun saveWithOutput(
        preferences: ReaderStylePreferences,
        openOutput: (File) -> OutputStream,
    ) {
        saveInternal(preferences) { temporary ->
            openOutput(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output -> write(output, preferences) }
            }
        }
    }

    fun cleanStalePartials(): Int {
        if (!directory.isDirectory) return 0
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("style-") && it.name.endsWith(".partial") }
            .count { it.delete() }
    }

    private fun saveInternal(preferences: ReaderStylePreferences, writeTemporary: (File) -> Unit) {
        validate(preferences)
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("독서 스타일 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("style-", ".partial", directory)
        try {
            writeTemporary(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: TxtImportException) {
            throw error
        } catch (error: Exception) {
            throw TxtImportException("독서 스타일을 저장하지 못했습니다.", error)
        } finally {
            temporary.delete()
        }
    }

    private fun write(output: DataOutputStream, preferences: ReaderStylePreferences) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeStyle(preferences.defaultStyle)
        output.writeInt(preferences.bookStyles.size)
        preferences.bookStyles.toSortedMap().forEach { (bookId, style) ->
            output.writeUTF(bookId)
            output.writeStyle(style)
        }
    }

    private fun DataInputStream.readStyle(version: Int): ReaderStyle = ReaderStyle(
        fontSizeSp = readInt(),
        lineSpacingPercent = readInt(),
        theme = readUTF().let { saved ->
            ReaderTheme.entries.firstOrNull { it.name == saved }
                ?: throw TxtImportException("독서 테마 정보가 올바르지 않습니다.")
        },
        textColorArgb = if (version >= 2) readInt().takeUnless { it == 0 } else null,
        backgroundColorArgb = if (version >= 2) readInt().takeUnless { it == 0 } else null,
        showSavedMeaningMarks = if (version >= 3) readBoolean() else true,
    )

    private fun DataOutputStream.writeStyle(style: ReaderStyle) {
        writeInt(style.fontSizeSp)
        writeInt(style.lineSpacingPercent)
        writeUTF(style.theme.name)
        writeInt(style.textColorArgb ?: 0)
        writeInt(style.backgroundColorArgb ?: 0)
        writeBoolean(style.showSavedMeaningMarks)
    }

    private fun validate(preferences: ReaderStylePreferences) {
        if (preferences.bookStyles.size > MAX_BOOKS) {
            throw TxtImportException("책별 스타일이 너무 많습니다.")
        }
        validateStyle(preferences.defaultStyle)
        preferences.bookStyles.forEach { (bookId, style) ->
            if (!SHA256.matches(bookId)) throw TxtImportException("책별 스타일 식별자가 올바르지 않습니다.")
            validateStyle(style)
        }
    }

    private fun validateStyle(style: ReaderStyle) {
        if (style.fontSizeSp !in 14..36 || style.lineSpacingPercent !in 100..200) {
            throw TxtImportException("독서 스타일 값이 올바르지 않습니다.")
        }
        if (listOfNotNull(style.textColorArgb, style.backgroundColorArgb).any { it ushr 24 != 0xFF } ||
            !style.hasReadableContrast()
        ) {
            throw TxtImportException("글자색과 배경색의 대비가 부족하거나 색상 값이 올바르지 않습니다.")
        }
    }

    companion object {
        const val FILE_NAME = "reader-styles.bin"
        private const val MAGIC = 0x57565354
        private const val VERSION = 3
        private const val MAX_BOOKS = 10_000
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
