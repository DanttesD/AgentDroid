package com.termux.app.workspace

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext only in workspace JSON. The encryption key never leaves Android Keystore.*/
object SshPasswordVault {
    private const val ALIAS: String = "termux.jobs.ssh.password.v1"

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    private fun key(create: Boolean): SecretKey? {
        val store: KeyStore = KeyStore.getInstance("AndroidKeyStore")
        try {
            store.load(null)
        } catch (e: IOException) {
            throw GeneralSecurityException(e)
        }
        if (store.containsAlias(ALIAS)) {
            return store.getKey(ALIAS, null) as SecretKey?
        }
        if (!create) {
            throw GeneralSecurityException("Password key unavailable")
        }
        val generator: KeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @JvmStatic
    fun binding(s: WorkspaceStore.Session?): ByteArray {
        return ((s!!.id + "\n" + s.sshHost + "\n" + s.sshUser + "\n" + s.sshPort) as java.lang.String).getBytes(StandardCharsets.UTF_8)
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun encrypt(s: WorkspaceStore.Session, password: CharArray): String {
        return encrypt(key(true), binding(s), password)
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun decrypt(s: WorkspaceStore.Session?): CharArray {
        return decrypt(key(false), binding(s), s!!.sshEncryptedPassword)
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun encrypt(key: SecretKey?, binding: ByteArray, password: CharArray): String {
        val encoded: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val plain = ByteArray(encoded.remaining())
        encoded.get(plain)
        try {
            val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(binding)
            return "1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
        } finally {
            Arrays.fill(plain, 0.toByte())
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), 0.toByte())
            }
        }
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun decrypt(key: SecretKey?, binding: ByteArray, value: String): CharArray {
        val parts: Array<String> = (value as java.lang.String).split(":", -1)
        if (parts.size != 3 || !parts[0].equals("1")) {
            throw GeneralSecurityException("Invalid password record")
        }
        try {
            val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            cipher.updateAAD(binding)
            val plain: ByteArray = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
            try {
                val chars: CharBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain))
                val result = CharArray(chars.remaining())
                chars.get(result)
                if (chars.hasArray()) {
                    Arrays.fill(chars.array(), '\u0000')
                }
                return result
            } finally {
                Arrays.fill(plain, 0.toByte())
            }
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid password record", e)
        }
    }
}
