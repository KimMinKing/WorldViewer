package dev.worldviewer.lookup

import android.content.Context
import java.io.File

/** Removes only regenerable files left by the discontinued CC-CEDICT/ML Kit path. */
object LegacyDictionaryCleanup {
    fun clean(context: Context): Int {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(MODEL_DOWNLOAD_POLICY)
            .apply()
        return cleanFiles(context.noBackupFilesDir)
    }

    internal fun cleanFiles(noBackupRoot: File): Int = LEGACY_DIRECTORIES.count { name ->
        val target = noBackupRoot.resolve(name)
        target.exists() && target.deleteRecursively()
    }

    private const val PREFERENCES = "dictionary"
    private const val MODEL_DOWNLOAD_POLICY = "model_download_policy"
    private val LEGACY_DIRECTORIES = listOf(
        "dictionaries",
        "com.google.mlkit.translate.models",
    )
}
