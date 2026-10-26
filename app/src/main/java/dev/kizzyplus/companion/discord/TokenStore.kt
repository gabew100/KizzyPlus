package dev.kizzyplus.companion.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the Discord token encrypted with a key that lives in the Android keystore. */
object TokenStore {
    private const val PREFS = "auth"
    private const val KEY_ALIAS = "kizzyplus.discord.token"
    private const val GCM_IV_BYTES = 12

    fun save(context: Context, token: String, displayName: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray())
        prefs(context).edit()
            .putString("token", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString("name", displayName)
            .apply()
    }

    fun load(context: Context): String? {
        val stored = prefs(context).getString("token", null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, GCM_IV_BYTES))
            String(cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES))
        } catch (e: Exception) {
            null
        }
    }

    fun hasToken(context: Context): Boolean = prefs(context).contains("token")

    fun displayName(context: Context): String? = prefs(context).getString("name", null)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
