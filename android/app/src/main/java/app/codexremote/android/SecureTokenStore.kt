package app.codexremote.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote_credentials", Context.MODE_PRIVATE)

    fun save(serverUrl: String, token: String) {
        save(serverUrl, DeviceCredential(token))
    }

    fun save(serverUrl: String, credential: DeviceCredential) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(credential.token.toByteArray(Charsets.UTF_8))
        val suffix = keySuffix(serverUrl)
        preferences.edit()
            .putString("token_iv_$suffix", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_ciphertext_$suffix", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("device_id_$suffix", credential.deviceId)
            .putString("credential_expires_at_$suffix", credential.expiresAt)
            .putStringSet("credential_scopes_$suffix", credential.scopes)
            .apply()
    }

    fun load(serverUrl: String): String? {
        val suffix = keySuffix(serverUrl)
        val iv = preferences.getString("token_iv_$suffix", null)
            ?: preferences.getString("token_iv", null).takeIf { preferences.getString("server_url", null) == serverUrl }
            ?: return null
        val ciphertext = preferences.getString("token_ciphertext_$suffix", null)
            ?: preferences.getString("token_ciphertext", null).takeIf { preferences.getString("server_url", null) == serverUrl }
            ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun loadCredential(serverUrl: String): DeviceCredential? {
        val token = load(serverUrl) ?: return null
        val suffix = keySuffix(serverUrl)
        return DeviceCredential(
            token = token,
            deviceId = preferences.getString("device_id_$suffix", null),
            expiresAt = preferences.getString("credential_expires_at_$suffix", null),
            scopes = preferences.getStringSet("credential_scopes_$suffix", emptySet()).orEmpty().toSet(),
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun clear(serverUrl: String) {
        val suffix = keySuffix(serverUrl)
        preferences.edit()
            .remove("token_iv_$suffix")
            .remove("token_ciphertext_$suffix")
            .remove("device_id_$suffix")
            .remove("credential_expires_at_$suffix")
            .remove("credential_scopes_$suffix")
            .apply {
                if (preferences.getString("server_url", null)?.trim()?.trimEnd('/') == serverUrl.trim().trimEnd('/')) {
                    remove("server_url")
                    remove("token_iv")
                    remove("token_ciphertext")
                }
            }
            .apply()
    }

    private fun keySuffix(serverUrl: String): String = MessageDigest.getInstance("SHA-256")
        .digest(serverUrl.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "codex_remote_device_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
