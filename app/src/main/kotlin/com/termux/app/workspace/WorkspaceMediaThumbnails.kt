package com.termux.app.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import androidx.exifinterface.media.ExifInterface
import com.termux.R
import com.termux.app.TermuxActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.ToLongFunction

/** A single independent, cancellable reader; only viewport thumbnails are decoded.*/
class WorkspaceMediaThumbnails : AutoCloseable {
    interface Loader {
        @Throws(Exception::class)
        fun load(entry: ProjectFiles.Entry): Bitmap

        fun cancel()
    }

    private class Slot {
        @JvmField val row: View
        @JvmField val image: ImageView
        @JvmField val entry: ProjectFiles.Entry
        @JvmField val key: String
        @JvmField val generation: Int
        @JvmField @Volatile var visible: Boolean = false
        @JvmField @Volatile var attempted: Boolean = false
        @JvmField var shown: Boolean = false

        constructor(row: View, image: ImageView, entry: ProjectFiles.Entry, key: String, generation: Int) {
            this.row = row
            this.image = image
            this.entry = entry
            this.key = key
            this.generation = generation
        }
    }

    private val main: Handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val working: AtomicBoolean = AtomicBoolean()
    private val slots: MutableList<Slot> = ArrayList<Slot>()
    private val memory: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        protected override fun sizeOf(key: String, value: Bitmap): Int {
            return value.getAllocationByteCount()
        }
    }
    private val loader: Loader
    private val environment: String
    @Volatile private var generation: Int = 0
    @Volatile private var closed: Boolean = false
    @Volatile private var wanted: MutableList<Slot> = Collections.emptyList()
    private var viewport: ScrollView? = null

    constructor(a: TermuxActivity, p: WorkspaceStore.Session) : this(ProjectFiles.environment(p), Source(a, p))

    constructor(environment: String, loader: Loader) {
        this.environment = environment
        this.loader = loader
    }

    fun enabled(): Boolean {
        return !environment.equals("ssh")
    }

    fun reset() {
        generation++
        wanted = Collections.emptyList()
        for (s in slots) {
            s.visible = false
            s.image.setImageDrawable(null)
        }
        slots.clear()
        loader.cancel()
        main.removeCallbacks(refresh)
    }

    fun bind(row: View, image: ImageView, entry: ProjectFiles.Entry) {
        slots.add(Slot(row, image, entry, identity(environment, entry), generation))
    }

    fun schedule(viewport: ScrollView?) {
        this.viewport = viewport
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 50L)
    }

    private val refresh: Runnable = Runnable {
        if (closed || viewport == null) {
            return@Runnable
        }
        val top: Int = viewport!!.getScrollY()
        val height: Int = viewport!!.getHeight()
        val next: MutableList<Slot> = ArrayList<Slot>()
        var pending = false
        for (s in slots) {
            s.visible = next.size < 24 && inViewport(s.row.getTop(), s.row.getBottom(), top, height)
            if (!s.visible) {
                if (s.shown) {
                    placeholder(s)
                    s.shown = false
                }
                continue
            }
            next.add(s)
            val cached: Bitmap? = memory.get(s.key)
            if (cached != null) {
                s.image.setImageBitmap(cached)
                s.shown = true
            } else if (!s.attempted) {
                pending = true
            }
        }
        wanted = next
        if (pending && working.compareAndSet(false, true)) {
            worker.execute(Runnable(this::drain))
        }
    }

    private fun placeholder(s: Slot) {
        s.image.setImageResource(if (ProjectFiles.mime(s.entry.name).startsWith("video/")) R.drawable.workspace_media_video else R.drawable.workspace_media_photo)
        s.image.setScaleType(ImageView.ScaleType.CENTER_CROP)
    }

    private fun drain() {
        try {
            for (s in wanted) {
                if (closed || s.generation != generation || !s.visible || s.attempted) {
                    continue
                }
                s.attempted = true
                var bitmap: Bitmap? = memory.get(s.key)
                try {
                    if (bitmap == null) {
                        bitmap = loader.load(s.entry)
                    }
                } catch (ignored: Exception) {
                }
                if (bitmap == null) {
                    continue
                }
                if (closed || s.generation != generation) {
                    continue
                }
                memory.put(s.key, bitmap)
                val ready: Bitmap = bitmap
                main.post(Runnable {
                    if (!closed && s.generation == generation && s.visible && s.image.getParent() != null) {
                        s.image.setImageBitmap(ready)
                        s.shown = true
                    }
                })
            }
        } finally {
            working.set(false)
            main.post(refresh)
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        reset()
        worker.shutdownNow()
        memory.evictAll()
    }

    /** Cache only small thumbnails. Temporary proot originals are removed after each read.*/
    private class Source : Loader {
        private val activity: TermuxActivity
        private val profile: WorkspaceStore.Session
        private val cache: File
        @Volatile private var active: ProjectFiles? = null
        private var epoch: Int = 0

        constructor(a: TermuxActivity, p: WorkspaceStore.Session) {
            activity = a
            profile = p.copy()
            profile.environment = ProjectFiles.environment(p)
            profile.prootAlias = ""
            cache = File(a.getCacheDir(), "workspace-media-thumbnails-v1")
        }

        override fun cancel() {
            var f: ProjectFiles?
            synchronized(this) {
                epoch++
                f = active
            }
            if (f != null) {
                f!!.close()
            }
        }

        @Throws(Exception::class)
        override fun load(e: ProjectFiles.Entry): Bitmap {
            var current: Int
            synchronized(this) {
                current = epoch
            }
            if (!cache.isDirectory() && !cache.mkdirs()) {
                throw IOException("CACHE_UNAVAILABLE")
            }
            val key: String = ChatAttachments.hash(identity(ProjectFiles.environment(profile), e))
            val saved = File(cache, key + ".jpg")
            if (saved.isFile()) {
                val b: Bitmap? = BitmapFactory.decodeFile(saved.getPath())
                if (b != null) {
                    saved.setLastModified(System.currentTimeMillis())
                    return b
                }
                saved.delete()
            }
            val video: Boolean = ProjectFiles.mime(e.name).startsWith("video/")
            var original: File?
            var temp: File? = null
            try {
                if (ProjectFiles.environment(profile).equals("termux")) {
                    original = File(e.path)
                } else {
                    val limit: Long = if (video) 64L * 1024 * 1024 else 20L * 1024 * 1024
                    if (e.size <= 0 || e.size > limit) {
                        throw IOException("THUMBNAIL_LIMIT")
                    }
                    temp = File.createTempFile("source-", ".tmp", cache)
                    original = temp
                    try {
                        (ProjectFiles(activity, profile)).use { files ->
                            synchronized(this) {
                                if (epoch != current) {
                                    throw IOException("CANCELLED")
                                }
                                active = files
                            }
                            if (Thread.currentThread().isInterrupted()) {
                                throw IOException("CANCELLED")
                            }
                            files.export(e, temp, limit)
                        }
                    } finally {
                        active = null
                    }
                }
                val thumbnail: Bitmap? = decode(original, video)
                if (thumbnail == null) {
                    throw IOException("NO_THUMBNAIL")
                }
                val partial: File = File.createTempFile("thumb-", ".tmp", cache)
                try {
                    (FileOutputStream(partial)).use { out ->
                        if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, out)) {
                            throw IOException("NO_THUMBNAIL")
                        }
                    }
                    if (!partial.renameTo(saved)) {
                        throw IOException("CACHE_UNAVAILABLE")
                    }
                } finally {
                    partial.delete()
                }
                prune(cache, System.currentTimeMillis())
                return thumbnail
            } finally {
                if (temp != null) {
                    temp.delete()
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun media(e: ProjectFiles.Entry): Boolean {
            val mime: String = ProjectFiles.mime(e.name)
            return !e.directory && (mime.startsWith("image/") || mime.startsWith("video/"))
        }

        @JvmStatic
        fun identity(environment: String, e: ProjectFiles.Entry): String {
            return environment + "\u0000" + e.path + "\u0000" + e.size + "\u0000" + e.modified
        }

        @JvmStatic
        fun inViewport(top: Int, bottom: Int, scroll: Int, height: Int): Boolean {
            return height > 0 && bottom > scroll && top < scroll + height
        }

        @Throws(Exception::class)
        @JvmStatic
        fun decode(original: File?, video: Boolean): Bitmap? {
            var bitmap: Bitmap?
            if (video) {
                val r = MediaMetadataRetriever()
                try {
                    val input = FileInputStream(original)
                    var primaryException: Throwable? = null
                    try {
                        r.setDataSource(input.getFD())
                        val duration: String? = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val ms: Long = if (duration == null) 0L else Math.max(0L, java.lang.Long.parseLong(duration))
                        val timestamp: Long = Math.min(1000L, ms / 3) * 1000
                        if (Build.VERSION.SDK_INT >= 27) {
                            bitmap = r.getScaledFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                        } else {
                            val w: String? = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            val h: String? = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            if (w == null || h == null || Integer.parseInt(w).toLong() * Integer.parseInt(h) > 16000000L) {
                                return null
                            }
                            bitmap = r.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (t: Throwable) {
                        primaryException = t
                        throw t
                    } finally {
                        if (primaryException != null) {
                            try {
                                input.close()
                            } catch (suppressed: Throwable) {
                                primaryException.addSuppressed(suppressed)
                            }
                        } else {
                            input.close()
                        }
                    }
                } finally {
                    r.release()
                }
            } else {
                bitmap = ChatAttachments.bitmap(original, 384)
                try {
                    val m: Matrix = imageOrientation(original)
                    if (!m.isIdentity()) {
                        val rotated: Bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true)
                        if (rotated !== bitmap) {
                            bitmap.recycle()
                        }
                        bitmap = rotated
                    }
                } catch (ignored: IOException) {
                }
            }
            return if (bitmap == null) null else ThumbnailUtils.extractThumbnail(bitmap, 192, 192, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
        }

        @Throws(IOException::class)
        @JvmStatic
        fun imageOrientation(original: File?): Matrix {
            val exif = ExifInterface(original!!.getPath())
            val o: Int = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            if (o == 2) {
                m.setScale((-1).toFloat(), 1.0f)
            } else if (o == 3) {
                m.setRotate(180.0f)
            } else if (o == 4) {
                m.setScale(1.0f, (-1).toFloat())
            } else if (o == 5) {
                m.setRotate(90.0f)
                m.postScale(1.0f, (-1).toFloat())
            } else if (o == 6) {
                m.setRotate(90.0f)
            } else if (o == 7) {
                m.setRotate(90.0f)
                m.postScale((-1).toFloat(), 1.0f)
            } else if (o == 8) {
                m.setRotate(270.0f)
            }
            return m
        }

        @JvmStatic
        fun prune(directory: File, now: Long) {
            val files: Array<File>? = directory.listFiles()
            if (files == null) {
                return
            }
            Arrays.sort(files, Comparator.comparingLong(ToLongFunction<File>(File::lastModified)))
            var total: Long = 0L
            for (f in files) {
                if (f.getName().endsWith(".jpg")) {
                    total += f.length()
                }
            }
            for (f in files) {
                if (f.getName().endsWith(".jpg") && total > 16L * 1024 * 1024) {
                    val size: Long = f.length()
                    if (f.delete()) {
                        total -= size
                    }
                } else if (f.getName().endsWith(".tmp") && now - f.lastModified() > 10 * 60 * 1000) {
                    f.delete()
                }
            }
        }
    }
}
