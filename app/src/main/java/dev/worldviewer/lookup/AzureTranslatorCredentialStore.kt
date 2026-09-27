package dev.worldviewer.lookup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the user's Azure key encrypted with an app-local, non-exportable Android Keystore key. */
class AzureTranslatorCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AzureTranslatorCredentials? {
        val encrypted = preferences.getString(ENCRYPTED_CREDENTIALS, null) ?: return null
        val iv = preferences.getString(CREDENTIAL_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .toString(StandardCharsets.UTF_8)
            val separator = plain.indexOf('\n')
            check(separator > 0 && separator < plain.lastIndex)
            AzureTranslatorCredentials(
                key = plain.substring(0, separator),
                region = plain.substring(separator + 1),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(credentials: AzureTranslatorCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plain = "${credentials.key}\n${credentials.region}".toByteArray(StandardCharsets.UTF_8)
        val encrypted = cipher.doFinal(plain)
        preferences.edit()
            .putString(ENCRYPTED_CREDENTIALS, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(CREDENTIAL_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun existingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return checkNotNull(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
    }

    private fun getOrCreateKey(): SecretKey = runCatching { existingKey() }.getOrElse {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generator.generateKey()
    }

    companion object {
        private const val PREFERENCES = "azure_translator_credentials"
        private const val ENCRYPTED_CREDENTIALS = "encrypted"
        private const val CREDENTIAL_IV = "iv"
        private const val KEY_ALIAS = "worldviewer.azure-translator-key.v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
