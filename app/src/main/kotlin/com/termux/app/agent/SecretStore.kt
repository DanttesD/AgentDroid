package com.termux.app.agent

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores API keys encrypted with an Android Keystore AES key (the key never leaves the keystore).*/
class SecretStore {
    private val prefs: SharedPreferences

    constructor(c: Context) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_secrets", Context.MODE_PRIVATE)
    }

    @Throws(Exception::class)
    private fun key(): SecretKey? {
        val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (ks.containsAlias(ALIAS)) {
            return ks.getKey(ALIAS, null) as SecretKey?
        }
        val g: KeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun put(name: String, value: String?) {
        try {
            if (value == null || value.isEmpty()) {
                prefs.edit().remove(name).apply()
                return
            }
            val c: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct: ByteArray = c.doFinal((value as java.lang.String).getBytes(StandardCharsets.UTF_8))
            val packed: String = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs.edit().putString(name, packed).apply()
        } catch (e: Exception) {
            throw IllegalStateException("cannot store secret: " + e.message, e)
        }
    }

    fun get(name: String): String {
        val packed: String? = prefs.getString(name, null)
        if (packed == null) {
            return ""
        }
        try {
            val p: Array<String> = (packed as java.lang.String).split(":")
            val c: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
            return String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }

    companion object {
        private const val ALIAS: String = "agentdroid_secrets"
    }
}
