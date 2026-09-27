package dev.worldviewer.lookup

import android.content.Context
import dev.worldviewer.BuildConfig
import java.io.InputStream
import java.util.Properties

/** Imports a debug-only ADB handoff file, encrypts its values, then removes the plaintext. */
object DebugAzureCredentialImport {
    const val FILE_NAME = "debug-azure-translator.properties"

    fun importIfPresent(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val source = context.filesDir.resolve(FILE_NAME)
        if (!source.isFile) return false
        return try {
            val credentials = source.inputStream().use(::parse)
            AzureTranslatorCredentialStore(context).save(credentials)
            true
        } finally {
            source.delete()
        }
    }

    internal fun parse(input: InputStream): AzureTranslatorCredentials {
        val properties = Properties().apply { load(input) }
        return AzureTranslatorCredentials(
            key = properties.getProperty("Key1").orEmpty().trim(),
            region = properties.getProperty("Region").orEmpty().trim(),
        )
    }
}
