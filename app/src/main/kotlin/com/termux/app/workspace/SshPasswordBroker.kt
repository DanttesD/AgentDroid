package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.net.LocalServerSocket
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One password attempt, same-UID socket, no plaintext on disk, argv or environment.*/
class SshPasswordBroker : AutoCloseable {
    @JvmField val address: String = "termux-jobs-ssh-" + UUID.randomUUID()
    private val server: LocalServerSocket
    private val activity: WeakReference<Activity>
    private var password: CharArray? = null
    private val keyPath: String?
    private val closed: AtomicBoolean = AtomicBoolean()
    private val main: Handler = Handler(Looper.getMainLooper())
    private val expiry: Runnable = Runnable(this::close)

    @Throws(IOException::class)
    constructor(activity: Activity, password: CharArray) : this(activity, password, null)

    @Throws(IOException::class)
    constructor(activity: Activity, password: CharArray, keyPath: String?) {
        this.keyPath = keyPath
        this.activity = WeakReference<Activity>(activity)
        server = LocalServerSocket(address)
        this.password = password.clone()
        main.postDelayed(expiry, 120000L)
        val worker = Thread(Runnable(this::serve), "ssh-password-once")
        worker.setDaemon(true)
        worker.start()
    }

    @Throws(InterruptedException::class)
    private fun confirm(prompt: String): Boolean {
        val done = CountDownLatch(1)
        val accepted = AtomicBoolean()
        main.post(Runnable {
            val current: Activity? = activity.get()
            if (closed.get() || current == null || current.isFinishing() || current.isDestroyed()) {
                done.countDown()
                return@Runnable
            }
            val dialog: AlertDialog = WorkspaceDialogs.builder(current).setTitle(WorkspaceText.tr("Проверка SSH-сервера")).setMessage(prompt).setPositiveButton(WorkspaceText.tr("Доверять"), DialogInterface.OnClickListener { d, w ->
                accepted.set(true)
                done.countDown()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> done.countDown() }).create()
            dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> done.countDown() })
            dialog.show()
            main.postDelayed(Runnable {
                if (dialog.isShowing()) {
                    dialog.dismiss()
                }
            }, 90000L)
        })
        return done.await(95L, TimeUnit.SECONDS) && accepted.get() && !closed.get()
    }

    private fun serve() {
        try {
            while (!closed.get()) {
                val socket = server.accept()
                var primaryException: Throwable? = null
                try {
                    socket.setSoTimeout(10000)
                    if (socket.getPeerCredentials().getUid() != Process.myUid()) {
                        continue
                    }
                    val input = DataInputStream(socket.getInputStream())
                    val hint: String = input.readUTF()
                    val prompt: String = input.readUTF()
                    val output = DataOutputStream(socket.getOutputStream())
                    if (isHostPrompt(hint, prompt)) {
                        val answer: ByteArray = ((if (confirm(prompt)) "yes" else "no") as java.lang.String).getBytes(StandardCharsets.UTF_8)
                        output.writeInt(answer.size)
                        output.write(answer)
                        output.flush()
                        if (answer[0].toInt() == 'n'.code) {
                            break
                        }
                    } else if (if (keyPath == null) isPasswordPrompt(hint, prompt) else isKeyPassphrasePrompt(keyPath, hint, prompt)) {
                        var answerOrNull: ByteArray? = null
                        synchronized(this) {
                            if (password == null || closed.get()) {
                                output.writeInt(-1)
                                output.flush()
                            } else {
                                val buffer: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                if (buffer.hasArray()) {
                                    Arrays.fill(buffer.array(), 0.toByte())
                                }
                                answerOrNull = bytes
                            }
                        }
                        val answer: ByteArray = answerOrNull ?: break
                        try {
                            output.writeInt(answer.size)
                            output.write(answer)
                            output.flush()
                        } finally {
                            Arrays.fill(answer, 0.toByte())
                        }
                        break
                    } else {
                        output.writeInt(-1)
                        output.flush()
                        break
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
            }
        } catch (ignored: Exception) {
            /* Fail closed, without logging authentication data.*/
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        main.removeCallbacks(expiry)
        synchronized(this) {
            if (password != null) {
                Arrays.fill(password, '\u0000')
                password = null
            }
        }
        try {
            server.close()
        } catch (ignored: IOException) {
        }
    }

    companion object {
        @JvmStatic
        fun isPasswordPrompt(hint: String, prompt: String): Boolean {
            return hint.isEmpty() && (prompt as java.lang.String).trim().endsWith("password:")
        }

        @JvmStatic
        fun isKeyPassphrasePrompt(keyPath: String?, hint: String, prompt: String): Boolean {
            if (keyPath == null || !hint.isEmpty()) {
                return false
            }
            // OpenSSH formats this prompt with %.100s (bytes, not Java characters).
            val path: ByteArray = (keyPath as java.lang.String).getBytes(StandardCharsets.UTF_8)
            val shown = String(path, 0, Math.min(100, path.size), StandardCharsets.UTF_8)
            return (prompt as java.lang.String).trim().equals("Enter passphrase for key '" + shown + "':")
        }

        @JvmStatic
        fun isHostPrompt(hint: String, prompt: String): Boolean {
            return hint.equals("confirm") || prompt.contains("Are you sure you want to continue connecting")
        }
    }
}
