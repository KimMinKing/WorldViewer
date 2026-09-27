package dev.worldviewer.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAzureCredentialImportTest {
    @Test
    fun `local properties parser trims key and region`() {
        val credentials = "Key1= abc123 \nRegion= koreacentral \n"
            .byteInputStream()
            .use(DebugAzureCredentialImport::parse)

        assertEquals("abc123", credentials.key)
        assertEquals("koreacentral", credentials.region)
    }

    @Test
    fun `missing key is rejected`() {
        val failure = runCatching {
            "Region=koreacentral\n".byteInputStream().use(DebugAzureCredentialImport::parse)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
