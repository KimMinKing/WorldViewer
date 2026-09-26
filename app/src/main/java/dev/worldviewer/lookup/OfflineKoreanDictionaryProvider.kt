package dev.worldviewer.lookup

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.io.File

/** Exact Chinese headword lookup backed only by the packaged CC-CEDICT asset. */
class CcCedictLookupProvider(context: Context) : DictionaryLookupProvider, Closeable {
    private val applicationContext = context.applicationContext
    private val target = File(applicationContext.noBackupFilesDir, "dictionaries/cc_cedict_20260926.bin")

    @Volatile
    private var dictionary: CcCedictDictionary? = null

    override fun lookup(
        request: DictionaryLookupRequest,
        cancellation: LookupCancellation,
    ): DictionaryLookupResponse {
        cancellation.throwIfCancelled()
        val opened = openDictionary()
        val entries = opened.lookup(request.sourceText, MAXIMUM_ENTRIES).map { value ->
            cancellation.throwIfCancelled()
            DictionaryLookupEntry(
                headword = if (value.traditional == value.simplified) {
                    value.simplified
                } else {
                    "${value.simplified} · ${value.traditional}"
                },
                meanings = value.meanings.take(MAXIMUM_MEANINGS_PER_ENTRY),
                reading = CedictPinyin.toToneMarks(value.pinyin),
            )
        }
        return DictionaryLookupResponse(
            providerId = "cc-cedict",
            providerVersion = opened.providerVersion,
            queryText = request.sourceText,
            entries = entries,
            resultType = LookupResultType.REVERSE_DICTIONARY,
            attributions = listOf(CC_CEDICT_ATTRIBUTION),
            storagePolicy = LookupStoragePolicy(LookupRetention.DO_NOT_STORE),
        )
    }

    @Synchronized
    private fun openDictionary(): CcCedictDictionary = dictionary ?: run {
        val installed = CcCedictDictionary.install(
            openAsset = { applicationContext.assets.open(CcCedictDictionary.ASSET_PATH) },
            target = target,
        )
        CcCedictDictionary.open(installed).also { dictionary = it }
    }

    @Synchronized
    override fun close() {
        dictionary?.close()
        dictionary = null
    }

    companion object {
        private const val MAXIMUM_ENTRIES = 8
        private const val MAXIMUM_MEANINGS_PER_ENTRY = 12
        val CC_CEDICT_ATTRIBUTION = LookupAttribution(
            displayName = "CC-CEDICT",
            sourceUrl = "https://www.mdbg.net/chinese/dictionary?page=cc-cedict",
            licenseName = "CC BY-SA 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        )
    }
}

/**
 * Translates CC-CEDICT's English senses to Korean on the device. The selected
 * Chinese text and the senses are processed by ML Kit on-device; only model
 * downloads and ML Kit utilization metrics can contact Google.
 */
class OfflineKoreanDictionaryProvider(
    private val base: CcCedictLookupProvider,
    wifiOnly: Boolean,
) : DictionaryLookupProvider, Closeable {
    private val downloadConditions = DownloadConditions.Builder().apply {
        if (wifiOnly) requireWifi()
    }.build()
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build(),
    )

    override fun lookup(
        request: DictionaryLookupRequest,
        cancellation: LookupCancellation,
    ): DictionaryLookupResponse {
        val source = base.lookup(request, cancellation)
        if (source.entries.isEmpty()) return source
        cancellation.throwIfCancelled()
        Tasks.await(translator.downloadModelIfNeeded(downloadConditions))
        cancellation.throwIfCancelled()
        val translatedEntries = source.entries.map { entry ->
            val sourceMeanings = entry.meanings
            val translated = sourceMeanings.map { meaning ->
                cancellation.throwIfCancelled()
                Tasks.await(translator.translate(meaning)).trim().ifBlank { meaning }
            }
            entry.copy(meanings = translated, sourceMeanings = sourceMeanings)
        }
        return source.copy(
            providerId = "cc-cedict-google-translate-on-device",
            providerVersion = "${source.providerVersion}+mlkit-translate-17.0.3",
            entries = translatedEntries,
            resultType = LookupResultType.TRANSLATED_DICTIONARY,
            attributions = source.attributions + GOOGLE_TRANSLATE_ATTRIBUTION,
            storagePolicy = LookupStoragePolicy(LookupRetention.DO_NOT_STORE),
        )
    }

    override fun close() {
        translator.close()
        base.close()
    }

    companion object {
        val GOOGLE_TRANSLATE_ATTRIBUTION = LookupAttribution(
            displayName = "Google Translate",
            sourceUrl = "https://translate.google.com/",
        )
    }
}
