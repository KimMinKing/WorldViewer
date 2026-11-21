package dev.worldviewer.lookup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

data class AzureUsageSummary(val month: YearMonth, val characters: Long, val firstRecordedAt: Long?) {
    val f0MaximumRemaining: Long get() = (F0_MONTHLY_CHARACTERS - characters).coerceAtLeast(0)

    companion object { const val F0_MONTHLY_CHARACTERS = 2_000_000L }
}

/** App-local meter. It cannot include Azure requests from other apps or earlier installs. */
class AzureUsageStore(private val directory: File) {
    fun summary(credentials: AzureTranslatorCredentials, atMillis: Long): AzureUsageSummary = synchronized(LOCK) {
        val month = month(atMillis)
        val entry = load().firstOrNull { it.fingerprint == fingerprint(credentials) && it.month == month }
        AzureUsageSummary(month, entry?.characters ?: 0L, entry?.firstRecordedAt)
    }

    fun record(credentials: AzureTranslatorCredentials, characters: Long, atMillis: Long) = synchronized(LOCK) {
        require(characters in 0..MAX_CHARACTERS_PER_REQUEST)
        require(atMillis >= 0)
        val month = month(atMillis)
        val fingerprint = fingerprint(credentials)
        val entries = load().toMutableList()
        val index = entries.indexOfFirst { it.fingerprint == fingerprint && it.month == month }
        if (index >= 0) {
            val old = entries[index]
            val updated = Math.addExact(old.characters, characters)
            require(updated <= MAX_TOTAL_CHARACTERS)
            entries[index] = old.copy(characters = updated)
        } else {
            require(entries.size < MAX_ENTRIES) { "Azure 사용량 기록이 가득 찼습니다." }
            entries += Entry(fingerprint, month, characters, atMillis)
        }
        save(entries)
    }

    fun validate(): Boolean = synchronized(LOCK) { File(directory, FILE_NAME).takeIf { it.isFile }?.let { load(); true } ?: false }

    private fun load(): List<Entry> {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return emptyList()
        return DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Azure 사용량 기록 형식이 올바르지 않습니다." }
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES) { "Azure 사용량 항목 수가 올바르지 않습니다." }
            List(count) {
                val fingerprint = input.readUTF()
                val year = input.readInt()
                val monthNumber = input.readInt()
                val characters = input.readLong()
                val firstRecordedAt = input.readLong()
                require(FINGERPRINT.matches(fingerprint) && year in 2000..3000 && monthNumber in 1..12 &&
                    characters in 0..MAX_TOTAL_CHARACTERS && firstRecordedAt >= 0) {
                    "Azure 사용량 값이 올바르지 않습니다."
                }
                val month = YearMonth.of(year, monthNumber)
                require(month(firstRecordedAt) == month) { "Azure 사용량 기록 시각이 올바르지 않습니다." }
                Entry(fingerprint, month, characters, firstRecordedAt)
            }.also { entries ->
                require(entries.map { it.fingerprint to it.month }.toSet().size == entries.size && input.read() == -1) {
                    "Azure 사용량 기록에 중복 또는 추가 데이터가 있습니다."
                }
            }
        }
    }

    private fun save(entries: List<Entry>) {
        if (!directory.exists()) check(directory.mkdirs()) { "Azure 사용량 저장 폴더를 만들 수 없습니다." }
        val temporary = File.createTempFile("azure-usage-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(entries.size)
                    entries.sortedWith(compareBy<Entry> { it.fingerprint }.thenBy { it.month }).forEach { entry ->
                        output.writeUTF(entry.fingerprint)
                        output.writeInt(entry.month.year)
                        output.writeInt(entry.month.monthValue)
                        output.writeLong(entry.characters)
                        output.writeLong(entry.firstRecordedAt)
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            try {
                Files.move(temporary.toPath(), File(directory, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), File(directory, FILE_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun fingerprint(credentials: AzureTranslatorCredentials): String = MessageDigest.getInstance("SHA-256")
        .digest("${credentials.region.lowercase()}\u0000${credentials.key}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun month(atMillis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.UTC))

    private data class Entry(
        val fingerprint: String,
        val month: YearMonth,
        val characters: Long,
        val firstRecordedAt: Long,
    )

    companion object {
        const val FILE_NAME = "azure-usage.bin"
        private const val MAGIC = 0x57564155
        private const val VERSION = 1
        private const val MAX_ENTRIES = 5_000
        private const val MAX_CHARACTERS_PER_REQUEST = 100_000L
        private const val MAX_TOTAL_CHARACTERS = 1_000_000_000_000L
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val LOCK = Any()
    }
}
