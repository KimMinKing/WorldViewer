package dev.worldviewer.reader

import java.nio.file.Files
import java.io.DataOutputStream
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTimeStoreTest {
    private val bookA = "a".repeat(64)
    private val bookB = "b".repeat(64)
    private val zone = ZoneId.of("Asia/Seoul")

    @Test fun `foreground intervals split at local midnight without duplicate time`() {
        val day = LocalDate.of(2026, 9, 28)
        val beforeMidnight = day.atTime(23, 59, 55).atZone(zone).toInstant().toEpochMilli()
        val ledger = ReadingTimeLedger()

        ledger.start(bookA, 100_000, beforeMidnight, zone)
        ledger.checkpoint(120_000, beforeMidnight + 20_000, zone)
        ledger.stop(120_000, beforeMidnight + 20_000, zone)
        ledger.start(bookB, 130_000, beforeMidnight + 30_000, zone)
        ledger.stop(133_000, beforeMidnight + 33_000, zone)

        assertEquals(5_000L, ledger.snapshot()[ReadingTimeKey(bookA, day.toEpochDay())])
        assertEquals(15_000L, ledger.snapshot()[ReadingTimeKey(bookA, day.plusDays(1).toEpochDay())])
        assertEquals(3_000L, ledger.snapshot()[ReadingTimeKey(bookB, day.plusDays(1).toEpochDay())])
        assertEquals(23_000L, ledger.snapshot().values.sum())
    }

    @Test fun `clock rollback and stopped intervals do not duplicate duration`() {
        val ledger = ReadingTimeLedger()
        val wall = LocalDate.of(2026, 9, 28).atStartOfDay(zone).toInstant().toEpochMilli()
        ledger.start(bookA, 10_000, wall, zone)
        ledger.stop(15_000, wall - 3_600_000, zone)
        ledger.checkpoint(100_000, wall + 100_000, zone)
        assertEquals(5_000L, ledger.snapshot().values.sum())
    }

    @Test fun `saved totals round trip and invalid update leaves prior file intact`() {
        val directory = Files.createTempDirectory("worldviewer-reading-time").toFile()
        val store = ReadingTimeStore(directory)
        assertNull(store.load())
        val totals = mapOf(ReadingTimeKey(bookA, 20_000) to 12_345L)
        store.save(totals)
        val original = directory.resolve(ReadingTimeStore.FILE_NAME).readBytes()

        assertEquals(totals, store.load())
        assertThrows(TxtImportException::class.java) {
            store.save(mapOf(ReadingTimeKey("not-a-book", 20_000) to 1L))
        }
        assertTrue(original.contentEquals(directory.resolve(ReadingTimeStore.FILE_NAME).readBytes()))
    }

    @Test fun `unknown future version remains untouched`() {
        val directory = Files.createTempDirectory("worldviewer-reading-time-future").toFile()
        val file = directory.resolve(ReadingTimeStore.FILE_NAME)
        DataOutputStream(file.outputStream()).use {
            it.writeInt(0x57565254)
            it.writeInt(2)
            it.writeInt(0)
        }
        val original = file.readBytes()
        assertThrows(TxtImportException::class.java) { ReadingTimeStore(directory).load() }
        assertTrue(original.contentEquals(file.readBytes()))
    }
}
