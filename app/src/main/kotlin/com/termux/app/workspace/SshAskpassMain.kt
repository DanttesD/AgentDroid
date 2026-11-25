package com.termux.app.workspace

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Arrays

/** Started by app_process only for OpenSSH askpass; stdout belongs exclusively to SSH.*/
object SshAskpassMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var status = 1
        try {
            val socket = LocalSocket()
            var primaryException: Throwable? = null
            try {
                val address: String? = System.getenv("TERMUX_JOBS_SSH_SOCKET")
                if (address == null || !address.startsWith("termux-jobs-ssh-")) {
                    throw IOException()
                }
                socket.connect(LocalSocketAddress(address, LocalSocketAddress.Namespace.ABSTRACT))
                socket.setSoTimeout(125000)
                if (socket.getPeerCredentials().getUid() != Process.myUid()) {
                    throw IOException()
                }
                val output = DataOutputStream(socket.getOutputStream())
                val hint: String? = System.getenv("SSH_ASKPASS_PROMPT")
                output.writeUTF(if (hint == null) "" else hint)
                output.writeUTF(if (args.size == 0) "" else args[0])
                output.flush()
                val input = DataInputStream(socket.getInputStream())
                val count: Int = input.readInt()
                if (count >= 0 && count <= 8192) {
                    val answer = ByteArray(count)
                    try {
                        input.readFully(answer)
                        System.out.write(answer)
                        System.out.write('\n'.code)
                        System.out.flush()
                        status = 0
                    } finally {
                        Arrays.fill(answer, 0.toByte())
                    }
                }
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                if (primaryException != null) {
                    try {
                        socket.close()
                    } catch (suppressed: Throwable) {
                        primaryException.addSuppressed(suppressed)
                    }
                } else {
                    socket.close()
                }
            }
        } catch (ignored: Exception) {
            /* Never print exception data or a secret.*/
        }
        System.exit(status)
    }
}
