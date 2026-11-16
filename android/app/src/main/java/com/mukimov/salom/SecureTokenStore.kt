package com.mukimov.salom

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the API bearer token with an app-scoped Android Keystore AES key. */
internal class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("salom_directory", Context.MODE_PRIVATE)

    fun readOrCreate(): String {
        preferences.getString(ENCRYPTED_TOKEN, null)?.let { return decrypt(it) }

        // Migrate the old plaintext token only after durable encryption succeeds.
        val existing = preferences.getString(LEGACY_TOKEN, null)
        val token = existing ?: ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
        val encrypted = encrypt(token)
        val editor = preferences.edit().putString(ENCRYPTED_TOKEN, encrypted)
        if (existing != null) editor.remove(LEGACY_TOKEN)
        check(editor.commit()) { "Не удалось защитить ключ Салом на этом устройстве" }
        return token
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        require(packed.size > NONCE_BYTES) { "Повреждён ключ Салом" }
        val iv = packed.copyOfRange(0, NONCE_BYTES)
        val ciphertext = packed.copyOfRange(NONCE_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "salom_api_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENCRYPTED_TOKEN = "token_encrypted_v1"
        private const val LEGACY_TOKEN = "token"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun encryptedValue(context: Context, token: String): String =
            SecureTokenStore(context).encrypt(token)
    }
}
