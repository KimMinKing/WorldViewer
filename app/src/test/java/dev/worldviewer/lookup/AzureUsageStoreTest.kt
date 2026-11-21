package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.YearMonth

class AzureUsageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = AzureTranslatorCredentials("first-key", "koreacentral")
    private val second = AzureTranslatorCredentials("second-key", "koreacentral")

    @Test fun `meter survives restart and separates UTC month and credential`() {
        val directory = temporary.newFolder("azure-usage")
        val store = AzureUsageStore(directory)
        val october = Instant.parse("2026-10-31T23:59:59Z").toEpochMilli()
        val november = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        store.record(first, 3, october)
        store.record(first, 5, october)
        store.record(second, 7, october)
        store.record(first, 11, november)

        val reloaded = AzureUsageStore(directory)
        assertEquals(YearMonth.of(2026, 10), reloaded.summary(first, october).month)
        assertEquals(8L, reloaded.summary(first, october).characters)
        assertEquals(october, reloaded.summary(first, october).firstRecordedAt)
        assertEquals(1_999_992L, reloaded.summary(first, october).f0MaximumRemaining)
        assertEquals(7L, reloaded.summary(second, october).characters)
        assertEquals(11L, reloaded.summary(first, november).characters)
        assertTrue(reloaded.validate())
        assertTrue(!File(directory, AzureUsageStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1).contains("first-key"))
    }

    @Test fun `invalid input and corrupt file do not overwrite previous record`() {
        val directory = temporary.newFolder("azure-invalid")
        val store = AzureUsageStore(directory)
        val at = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
        store.record(first, 12, at)
        val file = File(directory, AzureUsageStore.FILE_NAME)
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.record(first, -1, at) }
        assertEquals(original.toList(), file.readBytes().toList())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(Exception::class.java) { store.record(first, 1, at) }
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
    }
}
