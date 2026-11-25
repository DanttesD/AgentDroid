package com.termux.app.workspace

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.function.ToLongFunction
import org.json.JSONArray
import org.json.JSONObject

/** App-private copies of explicitly chosen files. No shell interpolation or remote fetches.*/
object ChatAttachments {
    const val MAX_COUNT: Int = 4
    const val VIDEO_FRAMES: Int = 12
    const val MAX_FILE: Long = 5L * 1024 * 1024 * 1024
    const val MAX_IMAGE: Long = 100L * 1024 * 1024
    const val GUEST: String = "/tmp/termux-jobs-codex/chat-attachments/"

    @JvmStatic
    fun root(c: Context): File {
        return File(CodexSessionShell.socketDirectory(c.getFilesDir()), "chat-attachments")
    }

    @JvmStatic
    fun supported(p: WorkspaceStore.Session?): Boolean {
        return p != null && (p.environment.equals("codex") || p.geminiAgent || p.claudeAgent || p.aiAgent.equals("opencode"))
    }

    @JvmStatic
    fun root(c: Context, p: WorkspaceStore.Session?): File {
        return File(if (p != null && !p.environment.equals("codex") && p.geminiAgent) GeminiCli.directory(c.getFilesDir()) else if (p != null && !p.environment.equals("codex") && p.claudeAgent) ClaudeCode.directory(c.getFilesDir()) else if (p != null && !p.environment.equals("codex") && p.aiAgent.equals("opencode")) AiAgents.directory(c.getFilesDir()) else CodexSessionShell.socketDirectory(c.getFilesDir()), "chat-attachments")
    }

    @JvmStatic
    fun prefix(p: WorkspaceStore.Session?): String {
        return if (p != null && !p.environment.equals("codex") && p.geminiAgent) GeminiCli.GUEST + "/chat-attachments/" else if (p != null && !p.environment.equals("codex") && p.claudeAgent) ClaudeCode.GUEST + "/chat-attachments/" else if (p != null && !p.environment.equals("codex") && p.aiAgent.equals("opencode")) "/tmp/termux-jobs-ai/chat-attachments/" else GUEST
    }

    @Throws(IOException::class)
    @JvmStatic
    fun type(name: String): String {
        val n: String = (name as java.lang.String).toLowerCase(Locale.ROOT)
        if ((n as java.lang.String).matches(".*\\.(png|jpe?g|webp|gif|bmp|heic|heif|avif)\$")) {
            return "image"
        }
        if ((n as java.lang.String).matches(".*\\.(mp4|mkv|webm|mov|m4v|3gp)\$")) {
            return "video"
        }
        if (ProjectFiles.mime(n).equals("text/plain") || (n as java.lang.String).matches(".*\\.(csv|ini|conf|out|ndjson|jsonl|trace)\$")) {
            return "file"
        }
        return if (n.endsWith(".apk")) "apk" else "binary"
    }

    @JvmStatic
    fun badge(name: String): String {
        val n: String = (name as java.lang.String).toLowerCase(Locale.ROOT)
        for (suffix in arrayOf<String>("tar.gz", "tar.bz2", "tar.xz", "tar.zst")) {
            if (n.endsWith("." + suffix)) {
                return (suffix as java.lang.String).toUpperCase(Locale.ROOT)
            }
        }
        val dot: Int = n.lastIndexOf('.')
        val ext: String = if (dot > 0) n.substring(dot + 1) else ""
        return if ((ext as java.lang.String).matches("[a-z0-9]{1,8}")) (ext as java.lang.String).toUpperCase(Locale.ROOT) else "FILE"
    }

    @JvmStatic
    fun limit(kind: String): Long {
        return if (kind.equals("image")) MAX_IMAGE else MAX_FILE
    }

    @Throws(IOException::class)
    @JvmStatic
    fun requireSize(kind: String, bytes: Long) {
        if (bytes <= 0 || bytes > limit(kind)) {
            throw IOException("FILE_LIMIT")
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun inside(root: File, relative: String?): File {
        if (relative == null || !(relative as java.lang.String).matches("[a-f0-9]{32}/[A-Za-z0-9_.-]{1,100}")) {
            throw IOException("INVALID_ATTACHMENT")
        }
        val canonicalRoot: File = root.getCanonicalFile()
        val f = File(canonicalRoot, relative)
        if (!f.getCanonicalPath().equals(f.getAbsolutePath()) || !f.getCanonicalPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw IOException("INVALID_ATTACHMENT")
        }
        return f
    }

    @Throws(IOException::class)
    @JvmStatic
    fun guest(root: File, file: File): String {
        val relative: String = root.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString()
        inside(root, relative)
        return GUEST + relative
    }

    @Throws(Exception::class)
    @JvmStatic
    fun stage(c: Context, source: WorkspaceStore.Session, entry: ProjectFiles.Entry, destination: WorkspaceStore.Session): JSONObject {
        val kind: String = type(entry.name)
        val max: Long = limit(kind)
        if (entry.directory) {
            throw IOException("FILE_LIMIT")
        }
        requireSize(kind, entry.size)
        val root: File = root(c, destination).getCanonicalFile()
        if (!root.isDirectory() && !root.mkdirs()) {
            throw IOException("STORAGE_UNAVAILABLE")
        }
        val id: String = (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
        val dir = File(root, id)
        if (!dir.mkdir()) {
            throw IOException("STORAGE_UNAVAILABLE")
        }
        var ext: String = if (entry.name.contains(".")) (entry.name.substring(entry.name.lastIndexOf('.')) as java.lang.String).toLowerCase(Locale.ROOT) else ".txt"
        if (!(ext as java.lang.String).matches("\\.[a-z0-9]{1,15}")) {
            ext = ".txt"
        }
        val file: File = inside(root, id + "/original" + ext)
        try {
            (ProjectFiles(c as Activity, source)).use { files ->
                files.export(entry, file, max)
            }
            if (file.length() <= 0) {
                throw IOException("FILE_LIMIT")
            }
            return finish(c, root, destination, entry.name, kind, id, file, false)
        } catch (e: Exception) {
            delete(dir)
            throw e
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun finish(c: Context, root: File, destination: WorkspaceStore.Session, name: String?, kind: String, id: String, file: File, gallery: Boolean): JSONObject {
        requireSize(kind, file.length())
        val dir: File? = file.getParentFile()
        CodexChatControl.protect(file)
        val a: JSONObject = JSONObject().put("id", id).put("name", name).put("type", kind).put("path", (guest(root, file) as java.lang.String).replace(GUEST, prefix(destination))).put("bytes", file.length())
        if (kind.equals("image")) {
            checkImage(file)
        }
        if (kind.equals("file")) {
            textPreview(file, 8192)
        }
        if (kind.equals("video")) {
            video(root, file, a)
            val frames: JSONArray = a.getJSONArray("frames")
            var i = 0
            while (i < frames.length()) {
                val frame: JSONObject = frames.getJSONObject(i)
                frame.put("path", (frame.getString("path") as java.lang.String).replace(GUEST, prefix(destination)))
                i++
            }
        }
        if (kind.equals("image") && (gallery || !(file.getName() as java.lang.String).matches("(?i).*\\.(png|jpe?g|webp|gif)\$") || !prefix(destination).equals(GUEST) || file.length() > 20L * 1024 * 1024)) {
            val decoded: Bitmap = bitmap(file, 1280)
            val model: Bitmap = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.RGB_565)
            val canvas = Canvas(model)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(decoded, 0.0f, 0.0f, null)
            val prepared = File(dir, "model.jpg")
            try {
                (FileOutputStream(prepared)).use { out ->
                    if (!model.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                        throw IOException("INVALID_IMAGE")
                    }
                }
            } finally {
                decoded.recycle()
                model.recycle()
            }
            CodexChatControl.protect(prepared)
            a.put("modelPath", (guest(root, prepared) as java.lang.String).replace(GUEST, prefix(destination)))
        }
        (FileOutputStream(File(dir, "attachment.json"))).use { out ->
            out.write((a.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8))
        }
        return a
    }

    @Throws(Exception::class)
    @JvmStatic
    fun stageUri(c: Context, uri: Uri?, destination: WorkspaceStore.Session): JSONObject {
        if (uri == null || !"content".equals(uri.getScheme())) {
            throw IOException("INVALID_ATTACHMENT")
        }
        val mime: String? = c.getContentResolver().getType(uri)
        var name: String? = ""
        var size: Long = (-1).toLong()
        val cursor = c.getContentResolver().query(uri, arrayOf<String>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        var primaryException: Throwable? = null
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val n: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s: Int = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) {
                    name = cursor.getString(n)
                }
                if (s >= 0 && !cursor.isNull(s)) {
                    size = cursor.getLong(s)
                }
            }
        } catch (t: Throwable) {
            primaryException = t
            throw t
        } finally {
            if (primaryException != null) {
                try {
                    cursor?.close()
                } catch (suppressed: Throwable) {
                    primaryException.addSuppressed(suppressed)
                }
            } else {
                cursor?.close()
            }
        }
        val kind: String = if (mime != null && mime.startsWith("image/")) "image" else if (mime != null && mime.startsWith("video/")) "video" else ""
        if (kind.isEmpty()) {
            throw IOException("SUPPORTED_TYPES")
        }
        if (name == null || name.isEmpty()) {
            name = if (kind.equals("image")) "photo.jpg" else "video.mp4"
        }
        name = (name as java.lang.String).replaceAll("[\\\\/\\p{Cntrl}]", "_")
        if (name!!.length > 512) {
            throw IOException("INVALID_ATTACHMENT")
        }
        if (size >= 0) {
            requireSize(kind, size)
        }
        val root: File = root(c, destination).getCanonicalFile()
        if (!root.isDirectory() && !root.mkdirs()) {
            throw IOException("STORAGE_UNAVAILABLE")
        }
        val id: String = (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
        val dir = File(root, id)
        if (!dir.mkdir()) {
            throw IOException("STORAGE_UNAVAILABLE")
        }
        var ext: String = if (name.contains(".")) (name.substring(name.lastIndexOf('.')) as java.lang.String).toLowerCase(Locale.ROOT) else if (kind.equals("image")) ".jpg" else ".mp4"
        if (!(ext as java.lang.String).matches("\\.[a-z0-9]{1,15}")) {
            ext = if (kind.equals("image")) ".jpg" else ".mp4"
        }
        val file: File = inside(root, id + "/original" + ext)
        try {
            (c.getContentResolver().openInputStream(uri)).use { `in` ->
                (FileOutputStream(file)).use { out ->
                    if (`in` == null) {
                        throw IOException("ATTACHMENT_MISSING")
                    }
                    val buffer = ByteArray(65536)
                    var copied: Long = 0L
                    var count: Int = 0
                    while (true) {
                        count = `in`.read(buffer)
                        if (count == -1) break
                        if (Thread.currentThread().isInterrupted()) {
                            throw InterruptedIOException()
                        }
                        copied += count
                        if (copied > limit(kind)) {
                            throw IOException("FILE_LIMIT")
                        }
                        out.write(buffer, 0, count)
                    }
                }
            }
            return finish(c, root, destination, name, kind, id, file, true)
        } catch (error: Exception) {
            delete(dir)
            throw error
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun checkImage(f: File?) {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f!!.getPath(), o)
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth.toLong() * o.outHeight > 64000000L) {
            throw IOException("INVALID_IMAGE")
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun bitmap(f: File?, edge: Int): Bitmap {
        checkImage(f)
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f!!.getPath(), o)
        var sample = 1
        while (Math.max(o.outWidth, o.outHeight) / sample > edge) {
            sample *= 2
        }
        o.inJustDecodeBounds = false
        o.inSampleSize = sample
        val b: Bitmap? = BitmapFactory.decodeFile(f.getPath(), o)
        if (b == null) {
            throw IOException("INVALID_IMAGE")
        }
        return b
    }

    @Throws(IOException::class)
    @JvmStatic
    fun textPreview(f: File?, max: Int): String {
        val b = ByteArray(Math.min(f!!.length(), max.toLong()).toInt())
        (FileInputStream(f)).use { `in` ->
            var n = 0
            var r: Int = 0
            while (n < b.size && `in`.read(b, n, b.size - n).also { r = it } > 0) {
                n += r
            }
        }
        for (v in b) {
            if (v.toInt() == 0) {
                throw IOException("INVALID_TEXT")
            }
        }
        // A sample may end inside a UTF-8 sequence; the complete file remains intact.
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(b)).toString()
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun video(root: File, file: File, a: JSONObject) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.getPath())
            val duration: Long = java.lang.Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
            val w: Int = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            val h: Int = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            if (duration <= 0 || w <= 0 || h <= 0 || w.toLong() * h > 64000000L) {
                throw IOException("INVALID_VIDEO")
            }
            val frames = JSONArray()
            val count: Int = Math.min(VIDEO_FRAMES.toLong(), Math.max(1L, (duration + 999) / 1000)).toInt()
            val scale: Float = Math.min(1f, 1280f / Math.max(w, h))
            val tw: Int = Math.max(1, Math.round(w * scale))
            val th: Int = Math.max(1, Math.round(h * scale))
            for (i in 0 until (count)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw IOException("CANCELLED")
                }
                val ms: Long = if (count == 1) 0L else Math.min(duration - 1, i * (duration - 1) / (count - 1))
                var b: Bitmap? = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, tw, th) else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (b == null) {
                    throw IOException("INVALID_VIDEO")
                }
                if (Math.max(b.getWidth(), b.getHeight()) > 1280) {
                    val small: Bitmap = Bitmap.createScaledBitmap(b, tw, th, true)
                    if (small !== b) {
                        b.recycle()
                    }
                    b = small
                }
                val frame = File(file.getParentFile(), "frame-" + i + ".jpg")
                try {
                    (FileOutputStream(frame)).use { out ->
                        if (!b.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                            throw IOException("INVALID_VIDEO")
                        }
                    }
                } finally {
                    b.recycle()
                }
                CodexChatControl.protect(frame)
                frames.put(JSONObject().put("path", guest(root, frame)).put("timeMs", ms))
            }
            a.put("durationMs", duration).put("frames", frames)
        } finally {
            r.release()
        }
    }

    @JvmStatic
    fun discard(c: Context, a: JSONObject) {
        try {
            var storage: File = root(c)
            val path: String = a.optString("path")
            for (kind in arrayOf<String>("gemini", "claude", "opencode")) {
                val p = WorkspaceStore.Session()
                p.geminiAgent = kind.equals("gemini")
                p.claudeAgent = kind.equals("claude")
                p.aiAgent = if (kind.equals("opencode")) kind else ""
                if (path.startsWith(prefix(p))) {
                    storage = root(c, p)
                }
            }
            val f: File = inside(storage, a.getString("id") + "/attachment.json")
            delete(f.getParentFile())
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun delete(f: File?) {
        try {
            if (f!!.isDirectory() && f.getCanonicalFile().equals(f.getAbsoluteFile())) {
                val files: Array<File>? = f.listFiles()
                if (files != null) {
                    for (child in files) {
                        delete(child)
                    }
                }
            }
        } catch (ignored: IOException) {
        }
        f!!.delete()
    }

    @Throws(Exception::class)
    @JvmStatic
    fun hash(s: String): String {
        val b: ByteArray = MessageDigest.getInstance("SHA-256").digest((s as java.lang.String).getBytes(StandardCharsets.UTF_8))
        val h = StringBuilder()
        for (v in b) {
            h.append(java.lang.String.format(Locale.ROOT, "%02x", v.toInt() and 255))
        }
        return h.toString()
    }

    @Throws(Exception::class)
    @JvmStatic
    fun fileSha256(file: File?): String {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        (FileInputStream(file)).use { `in` ->
            var n: Int = 0
            while (true) {
                n = `in`.read(buffer)
                if (n == -1) break
                if (Thread.currentThread().isInterrupted()) {
                    throw InterruptedIOException()
                }
                digest.update(buffer, 0, n)
            }
        }
        return WorkspaceUpdates.hex(digest.digest())
    }

    @Throws(Exception::class)
    @JvmStatic
    fun materialize(c: Context, profile: WorkspaceStore.Session?, a: JSONObject): File? {
        val path: String = a.getString("path")
        val root: File = root(c, profile)
        val guestPrefix: String = prefix(profile)
        val apk: Boolean = a.optString("type").equals("apk")
        var managed: File? = null
        if (path.startsWith(guestPrefix)) {
            managed = inside(root, path.substring(guestPrefix.length))
            if (!managed.isFile()) {
                throw IOException("ATTACHMENT_MISSING")
            }
            requireSize(a.optString("type", "image"), managed.length())
            if (!apk) {
                return managed
            }
        }
        if (!path.startsWith("/") || path.contains("\n") || path.contains("\r") || path.contains("\u0000")) {
            throw IOException("INVALID_ATTACHMENT")
        }
        if (apk && !(path as java.lang.String).toLowerCase(Locale.ROOT).endsWith(".apk")) {
            throw IOException("INVALID_ATTACHMENT")
        }
        val image: Boolean = a.optString("type").equals("image") || !a.has("type")
        val cache = File(c.getCacheDir(), if (apk) "chat-apk-artifacts" else if (image) "chat-image-previews" else "chat-file-previews")
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw IOException("STORAGE_UNAVAILABLE")
        }
        val f = File(cache, hash(ProjectFiles.environment(profile) + "\u0000" + a.optString("cacheKey") + "\u0000" + path) + (if (apk) ".apk" else if (image) ".image" else ".file"))
        if (f.isFile()) {
            return f
        }
        synchronized(ChatAttachments::class.java) {
            if (f.isFile()) {
                return f
            }
            val temp: File = File.createTempFile("image-", ".tmp", cache)
            try {
                if (managed != null) {
                    if (managed.length() > MAX_FILE) {
                        throw IOException("FILE_LIMIT")
                    }
                    (FileInputStream(managed)).use { `in` ->
                        (FileOutputStream(temp)).use { out ->
                            val buffer = ByteArray(65536)
                            var n: Int = 0
                            var count: Long = 0L
                            while (true) {
                                n = `in`.read(buffer)
                                if (n == -1) break
                                count += n
                                if (count > MAX_FILE) {
                                    throw IOException("FILE_LIMIT")
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                } else {
                    (ProjectFiles(c as Activity, profile)).use { files ->
                        files.export(ProjectFiles.Entry(File(path).getParent(), File(path).getName(), false, 0L), temp, if (image) MAX_IMAGE else MAX_FILE)
                    }
                }
                if (!image) {
                    if (temp.length() <= 0) {
                        throw IOException("FILE_LIMIT")
                    }
                } else {
                    checkImage(temp)
                }
                if (!temp.renameTo(f)) {
                    throw IOException("STORAGE_UNAVAILABLE")
                }
                val cached: Array<File>? = cache.listFiles()
                if (cached != null) {
                    Arrays.sort(cached, Comparator.comparingLong(ToLongFunction<File>(File::lastModified)))
                    var total: Long = 0L
                    for (x in cached) {
                        total += x.length()
                    }
                    for (x in cached) {
                        if (total > (if (image) 64L else 512L) * 1024 * 1024 && !x.equals(f)) {
                            val n: Long = x.length()
                            if (x.delete()) {
                                total -= n
                            }
                        }
                    }
                }
            } finally {
                temp.delete()
            }
        }
        return f
    }
}
