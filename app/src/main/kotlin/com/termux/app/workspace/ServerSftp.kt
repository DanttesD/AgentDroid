package com.termux.app.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import java.util.UUID

/** Bounded-window SFTP v3 client over the existing pinned SSH transport. No shell filenames.*/
class ServerSftp : AutoCloseable {
    fun interface Progress {
        fun sent(bytes: Long)
    }

    private val `in`: DataInputStream
    private val out: DataOutputStream
    private val transport: AutoCloseable
    @Volatile private var pendingTemp: String? = null

    fun pendingTemp(): String? {
        return pendingTemp
    }

    @Throws(IOException::class)
    fun removeTemporary(path: String?) {
        if (path == null || !path.startsWith("/") || path.indexOf('\u0000') >= 0 || !(File(path).getName() as java.lang.String).matches("\\.termux-jobs-upload-[a-f0-9-]{36}")) {
            throw IOException("INVALID_PATH")
        }
        if (stat(path).exists) {
            ok(request(13, Body { b -> string(b, path) }))
        }
    }

    private var sequence: Int = 0
    private var posixRename: Boolean = false
    @Volatile private var closed: Boolean = false

    class Info {
        @JvmField val exists: Boolean
        @JvmField val size: Long
        @JvmField val permissions: Int
        @JvmField val mtime: Int

        constructor(e: Boolean, s: Long, p: Int, m: Int) {
            exists = e
            size = s
            permissions = p
            mtime = m
        }

        fun directory(): Boolean {
            return permissions and 61440 == 16384
        }

        fun regular(): Boolean {
            return permissions and 61440 == 32768
        }

        fun same(other: Info): Boolean {
            return exists == other.exists && (!exists || size == other.size && permissions == other.permissions && mtime == other.mtime)
        }
    }

    @Throws(Exception::class)
    constructor(input: InputStream, output: OutputStream, transport: AutoCloseable) {
        `in` = DataInputStream(input)
        out = DataOutputStream(output)
        this.transport = transport
        val bytes = ByteArrayOutputStream()
        val b = DataOutputStream(bytes)
        b.writeInt(3)
        packet(1, bytes.toByteArray())
        val version: DataInputStream = response()
        if (version.readUnsignedByte() != 2 || version.readInt() != 3) {
            throw IOException("SFTP_VERSION")
        }
        while (version.available() > 0) {
            val name: String = string(version)
            val value: String = string(version)
            if (name.equals("posix-rename@openssh.com") && value.equals("1")) {
                posixRename = true
            }
        }
    }

    @Throws(IOException::class)
    private fun packet(type: Int, data: ByteArray) {
        if (closed) {
            throw IOException("CANCELLED")
        }
        out.writeInt(data.size + 1)
        out.writeByte(type)
        out.write(data)
        out.flush()
    }

    @Throws(IOException::class)
    private fun response(): DataInputStream {
        val n: Int = `in`.readInt()
        if (n < 1 || n > 262144) {
            throw IOException("SFTP_PACKET")
        }
        val data = ByteArray(n)
        `in`.readFully(data)
        return DataInputStream(ByteArrayInputStream(data))
    }

    private fun interface Body {
        @Throws(IOException::class)
        fun write(b: DataOutputStream)
    }

    @Throws(IOException::class)
    private fun send(type: Int, body: Body): Int {
        val bytes = ByteArrayOutputStream()
        val b = DataOutputStream(bytes)
        val id: Int = ++sequence
        b.writeInt(id)
        body.write(b)
        try {
            packet(type, bytes.toByteArray())
        } catch (failure: IOException) {
            close()
            throw failure
        }
        return id
    }

    @Throws(IOException::class)
    private fun request(type: Int, body: Body): DataInputStream {
        val id: Int = send(type, body)
        val r: DataInputStream = response()
        val responseType: Int = r.readUnsignedByte()
        if (r.readInt() != id) {
            throw IOException("SFTP_ID")
        }
        val answer = ByteArrayOutputStream()
        answer.write(responseType)
        val rest = ByteArray(r.available())
        r.readFully(rest)
        answer.write(rest)
        return DataInputStream(ByteArrayInputStream(answer.toByteArray()))
    }

    @Throws(IOException::class)
    private fun acknowledgeWrite(pending: MutableMap<Int, Int>): Int {
        var count: Int
        var status: Int
        try {
            val r: DataInputStream = response()
            val type: Int = r.readUnsignedByte()
            val id: Int = r.readInt()
            val size: Int? = pending.remove(id)
            if (type != 101 || size == null) {
                throw IOException("SFTP_ID")
            }
            count = size
            status = r.readInt()
        } catch (broken: IOException) {
            close()
            throw broken
        }
        if (status != 0) {
            throw IOException("SFTP_WRITE_FAILED")
        }
        return count
    }

    @Throws(Exception::class)
    private fun writeFile(source: InputStream?, handle: ByteArray?, progress: Progress) {
        val pending: MutableMap<Int, Int> = LinkedHashMap<Int, Int>()
        val buffer = ByteArray(32768)
        var sent: Long = 0L
        var acknowledged: Long = 0L
        var eof = false
        try {
            while (!eof || !pending.isEmpty()) {
                while (!eof && pending.size < WRITE_WINDOW) {
                    if (closed) {
                        throw IOException("CANCELLED")
                    }
                    val count: Int = source!!.read(buffer)
                    if (count < 0) {
                        eof = true
                        break
                    }
                    if (count == 0) {
                        continue
                    }
                    if (sent + count > MAX_BYTES) {
                        throw IOException("FILE_LIMIT")
                    }
                    val offset: Long = sent
                    val size: Int = count
                    val id: Int = send(6, Body { b ->
                        blob(b, handle)
                        b.writeLong(offset)
                        b.writeInt(size)
                        b.write(buffer, 0, size)
                    })
                    pending.put(id, size)
                    sent += size
                }
                if (!pending.isEmpty()) {
                    acknowledged += acknowledgeWrite(pending)
                    progress.sent(acknowledged)
                }
            }
        } catch (failure: Exception) {
            // Consume every known outstanding reply before CLOSE/REMOVE. Never reuse a corrupt stream.
            while (!closed && !pending.isEmpty()) {
                try {
                    acknowledgeWrite(pending)
                } catch (drain: IOException) {
                    failure.addSuppressed(drain)
                }
            }
            throw failure
        }
    }

    @Throws(IOException::class)
    fun canonical(directory: String?): String {
        val r: DataInputStream = request(16, Body { b -> string(b, directory) })
        if (r.readUnsignedByte() != 104 || r.readInt() != 1) {
            throw IOException("SFTP_DIRECTORY")
        }
        val path: String = string(r)
        if (!path.startsWith("/") || path.indexOf('\u0000') >= 0) {
            throw IOException("SFTP_DIRECTORY")
        }
        return path
    }

    @Throws(IOException::class)
    fun stat(path: String): Info {
        val r: DataInputStream = request(7, Body { b -> string(b, path) })
        val type: Int = r.readUnsignedByte()
        if (type == 101) {
            if (r.readInt() == 2) {
                return Info(false, 0L, 0, 0)
            }
            throw IOException("SFTP_STAT")
        }
        if (type != 105) {
            throw IOException("SFTP_STAT")
        }
        return attributes(r)
    }

    class Entry {
        @JvmField val name: String
        @JvmField val info: Info

        constructor(n: String, i: Info) {
            name = n
            info = i
        }
    }

    @Throws(IOException::class)
    fun list(path: String): MutableList<Entry> {
        val opened: DataInputStream = request(11, Body { b -> string(b, path) })
        if (opened.readUnsignedByte() != 102) {
            throw IOException("SFTP_DIRECTORY")
        }
        val handle: ByteArray = blob(opened)
        val entries: MutableList<Entry> = ArrayList<Entry>()
        try {
            while (true) {
                val r: DataInputStream = request(12, Body { b -> blob(b, handle) })
                val type: Int = r.readUnsignedByte()
                if (type == 101) {
                    if (r.readInt() == 1) {
                        break
                    }
                    throw IOException("SFTP_READDIR")
                }
                if (type != 104) {
                    throw IOException("SFTP_READDIR")
                }
                val count: Int = r.readInt()
                if (count < 0 || count > 20000 || entries.size + count > 20002) {
                    throw IOException("DIRECTORY_LIMIT")
                }
                for (i in 0 until (count)) {
                    val name: String = string(r)
                    string(r)
                    val info: Info = attributes(r)
                    if (name.equals(".") || name.equals("..")) {
                        continue
                    }
                    if (!validName(name)) {
                        throw IOException("UNSAFE_NAME")
                    }
                    entries.add(Entry(name, info))
                }
            }
            return entries
        } finally {
            if (!closed) {
                try {
                    ok(request(4, Body { b -> blob(b, handle) }))
                } catch (error: IOException) {
                    close()
                }
            }
        }
    }

    @Throws(Exception::class)
    fun download(path: String, target: OutputStream, expected: Info, progress: Progress) {
        if (!expected.regular() || expected.size > MAX_DOWNLOAD_BYTES) {
            throw IOException("FILE_LIMIT_OR_TYPE")
        }
        if (!expected.same(stat(path))) {
            throw IOException("REMOTE_CHANGED")
        }
        val r: DataInputStream = request(3, Body { b ->
            string(b, path)
            b.writeInt(1)
            b.writeInt(0)
        })
        if (r.readUnsignedByte() != 102) {
            throw IOException("SFTP_OPEN")
        }
        val handle: ByteArray = blob(r)
        var success = false
        try {
            var received: Long = 0L
            var next: Long = 0L
            val pending: MutableMap<Int, Long> = HashMap<Int, Long>()
            val ready = TreeMap<Long, ByteArray>()
            while (received < expected.size) {
                while (next < expected.size && pending.size + ready.size < WRITE_WINDOW) {
                    val offset: Long = next
                    val length: Int = Math.min(32768L, expected.size - next).toInt()
                    val id: Int = send(5, Body { b ->
                        blob(b, handle)
                        b.writeLong(offset)
                        b.writeInt(length)
                    })
                    pending.put(id, offset)
                    next += length
                }
                val data: DataInputStream = response()
                val type: Int = data.readUnsignedByte()
                val id: Int = data.readInt()
                val offset: Long? = pending.remove(id)
                if (type != 103 || offset == null) {
                    throw IOException("SFTP_READ")
                }
                val chunk: ByteArray = blob(data)
                val wanted: Int = Math.min(32768L, expected.size - offset).toInt()
                if (chunk.size != wanted) {
                    throw IOException("SFTP_SHORT_READ")
                }
                ready.put(offset, chunk)
                while (ready.containsKey(received)) {
                    val current: ByteArray? = ready.remove(received)
                    target.write(current)
                    received += current!!.size
                    progress.sent(received)
                }
            }
            success = true
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            if (!closed) {
                ok(request(4, Body { b -> blob(b, handle) }))
            }
        }
        if (success && !expected.same(stat(path))) {
            throw IOException("REMOTE_CHANGED")
        }
    }

    @Throws(Exception::class)
    fun move(source: String, directory: String, expected: Info) {
        val name: String = File(source).getName()
        if (!source.startsWith("/") || !validName(name) || !directory.startsWith("/")) {
            throw IOException("INVALID_PATH")
        }
        val target: String = directory + (if (directory.equals("/")) "" else "/") + name
        if (!expected.regular() && !expected.directory()) {
            throw IOException("UNSUPPORTED_TYPE")
        }
        if (target.equals(source) || directory.equals(source) || directory.startsWith(source + "/")) {
            throw IOException("MOVE_INTO_SELF")
        }
        if (!expected.same(stat(source))) {
            throw IOException("REMOTE_CHANGED")
        }
        if (stat(target).exists) {
            throw IOException("DESTINATION_EXISTS")
        }
        ok(request(18, Body { b ->
            string(b, source)
            string(b, target)
        }))
    }

    fun canReplace(): Boolean {
        return posixRename
    }

    @Throws(Exception::class)
    fun upload(source: InputStream?, directory: String, name: String, expected: Info, progress: Progress) {
        if (!validName(name) || !directory.startsWith("/")) {
            throw IOException("INVALID_PATH")
        }
        if (expected.exists && (!expected.regular() || !posixRename)) {
            throw IOException("SFTP_REPLACE_UNSUPPORTED")
        }
        val target: String = directory + (if (directory.equals("/")) "" else "/") + name
        val temp: String = directory + (if (directory.equals("/")) "" else "/") + ".termux-jobs-upload-" + UUID.randomUUID()
        pendingTemp = temp
        var handle: ByteArray? = null
        var published = false
        try {
            val r: DataInputStream = request(3, Body { b ->
                string(b, temp)
                b.writeInt(2 or 8 or 32)
                b.writeInt(4)
                b.writeInt(384)
            })
            if (r.readUnsignedByte() != 102) {
                throw IOException("SFTP_OPEN")
            }
            handle = blob(r)
            val h: ByteArray? = handle
            writeFile(source, h, progress)
            if (expected.exists) {
                ok(request(10, Body { b ->
                    blob(b, h)
                    b.writeInt(4)
                    b.writeInt(expected.permissions and 511)
                }))
            }
            ok(request(4, Body { b -> blob(b, h) }))
            handle = null
            if (!expected.same(stat(target))) {
                throw IOException("REMOTE_CHANGED")
            }
            if (expected.exists) {
                ok(request(200, Body { b ->
                    string(b, "posix-rename@openssh.com")
                    string(b, temp)
                    string(b, target)
                }))
            } else {
                ok(request(18, Body { b ->
                    string(b, temp)
                    string(b, target)
                }))
            }
            published = true
            pendingTemp = null
        } finally {
            if (handle != null && !closed) {
                val h: ByteArray = handle
                try {
                    ok(request(4, Body { b -> blob(b, h) }))
                } catch (ignored: Exception) {
                }
            }
            if (!published && !closed) {
                try {
                    ok(request(13, Body { b -> string(b, temp) }))
                    pendingTemp = null
                } catch (ignored: Exception) {
                }
            }
        }
    }

    override fun close() {
        closed = true
        try {
            transport.close()
        } catch (ignored: Exception) {
        }
        try {
            `in`.close()
        } catch (ignored: Exception) {
        }
        try {
            out.close()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        const val WRITE_WINDOW: Int = 8
        const val MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

        @JvmStatic
        fun validName(name: String?): Boolean {
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || (name as java.lang.String).getBytes(StandardCharsets.UTF_8).size > 255) {
                return false
            }
            var i = 0
            while (i < name.length) {
                if (name[i] == '/' || Character.isISOControl(name[i]) || Character.isSurrogate(name[i]) && (i + 1 >= name.length || !Character.isSurrogatePair(name[i], name[++i]))) {
                    return false
                }
                i++
            }
            return true
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun string(b: DataOutputStream, value: String?) {
            blob(b, (value!! as java.lang.String).getBytes(StandardCharsets.UTF_8))
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun blob(b: DataOutputStream, value: ByteArray?) {
            b.writeInt(value!!.size)
            b.write(value)
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun blob(b: DataInputStream): ByteArray {
            val n: Int = b.readInt()
            if (n < 0 || n > 65536 || n > b.available()) {
                throw IOException("SFTP_PACKET")
            }
            val bytes = ByteArray(n)
            b.readFully(bytes)
            return bytes
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun string(b: DataInputStream): String {
            return String(blob(b), StandardCharsets.UTF_8)
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun ok(r: DataInputStream) {
            if (r.readUnsignedByte() != 101 || r.readInt() != 0) {
                throw IOException("SFTP_FAILED")
            }
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun attributes(r: DataInputStream): Info {
            val flags: Int = r.readInt()
            if (flags and 15.inv() != 0) {
                throw IOException("SFTP_ATTRIBUTES")
            }
            val size: Long = if (flags and 1 != 0) r.readLong() else 0L
            if (size < 0) {
                throw IOException("SFTP_SIZE")
            }
            if (flags and 2 != 0) {
                r.readInt()
                r.readInt()
            }
            val mode: Int = if (flags and 4 != 0) r.readInt() else 0
            var modified = 0
            if (flags and 8 != 0) {
                r.readInt()
                modified = r.readInt()
            }
            return Info(true, size, mode, modified)
        }

        const val MAX_DOWNLOAD_BYTES: Long = 10L * 1024 * 1024 * 1024
    }
}
