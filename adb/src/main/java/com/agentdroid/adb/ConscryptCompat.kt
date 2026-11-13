package com.agentdroid.adb

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import javax.net.ssl.SSLSocket

/**
 * Replacement for the hidden-API stub used by the original Shizuku code:
 * calls the platform Conscrypt.exportKeyingMaterial through reflection.
 */
internal object ConscryptCompat {
    private const val TAG = "AgentAdb"

    fun exportKeyingMaterial(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
            } catch (t: Throwable) {
                Log.w(TAG, "Hidden API exemption failed", t)
            }
        }
        val cls = Class.forName("com.android.org.conscrypt.Conscrypt")
        val m = cls.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType
        )
        return m.invoke(null, socket, label, context, length) as ByteArray
    }
}
