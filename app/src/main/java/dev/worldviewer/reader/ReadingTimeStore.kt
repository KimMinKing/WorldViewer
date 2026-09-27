package dev.worldviewer.reader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId

data class ReadingTimeKey(val bookId: String, val epochDay: Long)

/** Only completed, foreground reading intervals are kept here. */
class ReadingTimeLedger(initial: Map<ReadingTimeKey, Long> = emptyMap()) {
    private val totals = initial.toMutableMap()
    private var activeBook: String? = null
    private var lastElapsedMillis = 0L

    fun snapshot(): Map<ReadingTimeKey, Long> = totals.toMap()

    fun start(bookId: String, elapsedMillis: Long, wallMillis: Long, zone: ZoneId) {
        checkpoint(elapsedMillis, wallMillis, zone)
        activeBook = bookId
        lastElapsedMillis = elapsedMillis
    }

    fun stop(elapsedMillis: Long, wallMillis: Long, zone: ZoneId): Boolean {
        val changed = checkpoint(elapsedMillis, wallMillis, zone)
        activeBook = null
        return changed
    }

    fun checkpoint(elapsedMillis: Long, wallMillis: Long, zone: ZoneId): Boolean {
        val book = activeBook ?: return false
        val duration = (elapsedMillis - lastElapsedMillis).coerceAtLeast(0)
        lastElapsedMillis = elapsedMillis
        if (duration == 0L) return false
        // A monotonic duration avoids double counting when the wall clock is adjusted.
        // The current wall time only decides which local day receives each part.
        var cursor = wallMillis - duration
        while (cursor < wallMillis) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(wallMillis, nextMidnight)
            val key = ReadingTimeKey(book, day.toEpochDay())
            totals[key] = Math.addExact(totals[key] ?: 0L, end - cursor)
            cursor = end
        }
        return true
    }
}

class ReadingTimeStore(private val directory: File) {
    fun load(): Map<ReadingTimeKey, Long>? {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return null
        return DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) throw TxtImportException("독서 시간 정보가 손상됐습니다.")
            if (input.readInt() != VERSION) throw TxtImportException("지원하지 않는 독서 시간 정보입니다.")
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) throw TxtImportException("독서 시간 항목 수가 올바르지 않습니다.")
            val entries = List(count) {
                val key = ReadingTimeKey(input.readUTF(), input.readLong())
                val duration = input.readLong()
                validate(key, duration)
                key to duration
            }
            if (entries.map { it.first }.toSet().size != entries.size || input.read() != -1) {
                throw TxtImportException("독서 시간 정보에 중복 또는 추가 데이터가 있습니다.")
            }
            entries.toMap()
        }
    }

    fun save(entries: Map<ReadingTimeKey, Long>) {
        if (entries.size > MAX_ENTRIES) throw TxtImportException("독서 시간 항목이 너무 많습니다.")
        entries.forEach { (key, duration) -> validate(key, duration) }
        if (!directory.exists() && !directory.mkdirs()) {
            throw TxtImportException("독서 시간 저장 공간을 만들 수 없습니다.")
        }
        val temporary = File.createTempFile("reading-time-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(entries.size)
                    entries.toSortedMap(compareBy<ReadingTimeKey> { it.bookId }.thenBy { it.epochDay })
                        .forEach { (key, duration) ->
                            output.writeUTF(key.bookId)
                            output.writeLong(key.epochDay)
                            output.writeLong(duration)
                        }
                    output.flush()
                    raw.fd.sync()
                }
            }
            try {
                Files.move(temporary.toPath(), File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun cleanStalePartials(): Int = directory.listFiles().orEmpty()
        .count { it.isFile && it.name.startsWith("reading-time-") && it.name.endsWith(".partial") && it.delete() }

    private fun validate(key: ReadingTimeKey, duration: Long) {
        if (!SHA256.matches(key.bookId) || key.epochDay !in -365_000L..365_000L ||
            duration !in 0..MAX_DURATION_MILLIS) {
            throw TxtImportException("독서 시간 값이 올바르지 않습니다.")
        }
    }

    companion object {
        const val FILE_NAME = "reading-time.bin"
        private const val MAGIC = 0x57565254
        private const val VERSION = 1
        private const val MAX_ENTRIES = 100_000
        private const val MAX_DURATION_MILLIS = 365_000L * 24 * 60 * 60 * 1000
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
