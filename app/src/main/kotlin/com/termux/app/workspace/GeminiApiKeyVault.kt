package com.termux.app.workspace

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Arrays
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Per-profile ciphertext in the library; one private handoff file consumed by the owned CLI.*/
object GeminiApiKeyVault {
    private const val ALIAS: String = "termux.jobs.gemini.api.v1"

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    private fun key(create: Boolean): SecretKey {
        val store: KeyStore = KeyStore.getInstance("AndroidKeyStore")
        try {
            store.load(null)
        } catch (e: IOException) {
            throw GeneralSecurityException(e)
        }
        if (store.containsAlias(ALIAS)) {
            return store.getKey(ALIAS, null) as SecretKey
        }
        if (!create) {
            throw GeneralSecurityException("API key unavailable")
        }
        val generator: KeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @JvmStatic
    fun binding(p: WorkspaceStore.Session?): ByteArray {
        return (("gemini-api-v1\n" + p!!.id) as java.lang.String).getBytes(StandardCharsets.UTF_8)
    }

    @JvmStatic
    fun valid(value: CharArray): Boolean {
        if (value.size == 0 || value.size > 4096) {
            return false
        }
        for (c in value) {
            if (c.code < 33 || c.code > 126) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun validRecord(value: String): Boolean {
        return value.isEmpty() || value.length <= 8192 && (value as java.lang.String).matches("1:[A-Za-z0-9+/]{16}:[A-Za-z0-9+/]+=*")
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun encrypt(p: WorkspaceStore.Session, value: CharArray): String {
        return encrypt(key(true), p, value)
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun encrypt(key: SecretKey, p: WorkspaceStore.Session, value: CharArray): String {
        if (!valid(value)) {
            throw GeneralSecurityException("Invalid API key")
        }
        return SshPasswordVault.encrypt(key, binding(p), value)
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun decrypt(key: SecretKey, p: WorkspaceStore.Session?): CharArray {
        return SshPasswordVault.decrypt(key, binding(p), p!!.geminiEncryptedKey)
    }

    @JvmStatic
    fun runtimePath(files: File, p: WorkspaceStore.Session?): File {
        UUID.fromString(p!!.id)
        return File(File(GeminiCli.directory(files), "keys"), p.id + ".key")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun runtime(c: Context, p: WorkspaceStore.Session?): File {
        return runtimePath(c.getFilesDir(), p)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    @JvmStatic
    fun prepare(c: Context, p: WorkspaceStore.Session?) {
        val target: File = runtime(c, p)
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot clear API handoff")
        }
        if (!p!!.geminiAuth.equals("api") || p.geminiEncryptedKey.isEmpty()) {
            return
        }
        val value: CharArray = decrypt(key(false), p)
        try {
            write(target, value)
        } finally {
            Arrays.fill(value, '\u0000')
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun write(target: File, value: CharArray) {
        if (!valid(value)) {
            throw IOException("Invalid API key")
        }
        val directory: File? = target.getParentFile()
        if (!directory!!.isDirectory() && !directory.mkdirs()) {
            throw IOException("Cannot create API handoff")
        }
        CodexChatControl.protect(directory)
        if (!directory.setExecutable(false, false) || !directory.setExecutable(true, true)) {
            throw IOException("Cannot protect API handoff")
        }
        val temp: File = File.createTempFile("api-", ".tmp", directory)
        val encoded: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        try {
            CodexChatControl.protect(temp)
            (FileOutputStream(temp)).use { out ->
                out.write(bytes)
            }
            if (!temp.renameTo(target)) {
                throw IOException("Cannot publish API handoff")
            }
        } finally {
            temp.delete()
            Arrays.fill(bytes, 0.toByte())
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), 0.toByte())
            }
        }
    }

    @JvmStatic
    fun discard(c: Context, p: WorkspaceStore.Session?) {
        try {
            runtime(c, p).delete()
        } catch (ignored: Exception) {
        }
    }
}
