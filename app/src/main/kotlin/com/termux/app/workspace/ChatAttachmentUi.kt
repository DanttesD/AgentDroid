package com.termux.app.workspace

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import com.termux.R
import com.termux.app.TermuxActivity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Attachment drafts are owned by a chat run, not by the currently visible tab.*/
class ChatAttachmentUi : AutoCloseable {
    private val activity: TermuxActivity
    private val view: AgentChatView
    private val picker: WorkspaceLocalFilePicker
    private val work: ExecutorService = Executors.newFixedThreadPool(2)
    private val main: Handler = Handler(Looper.getMainLooper())
    private val drafts: LinkedHashMap<String, MutableList<JSONObject>> = LinkedHashMap<String, MutableList<JSONObject>>()
    private val preparing: MutableSet<String> = HashSet<String>()
    private val submitted: MutableSet<String> = HashSet<String>()

    fun submitted(refs: JSONArray) {
        var i = 0
        while (i < refs.length()) {
            submitted.add(refs.optJSONObject(i).optString("id"))
            i++
        }
    }

    private fun discard(a: JSONObject) {
        if (!submitted.contains(a.optString("id"))) {
            ChatAttachments.discard(activity, a)
        }
    }

    @Volatile private var closed: Boolean = false
    private var owner: String = ""
    private var profile: WorkspaceStore.Session? = null
    private var supported: Boolean = false
    private var viewer: AlertDialog? = null

    constructor(activity: TermuxActivity, view: AgentChatView) {
        this.activity = activity
        this.view = view
        picker = WorkspaceLocalFilePicker(activity)
        view.folder.setOnClickListener(View.OnClickListener { v -> choose() })
        view.apkDetails = BiConsumer<JSONObject, TextView>(this::apkDetails)
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    fun sync(next: String, p: WorkspaceStore.Session?, available: Boolean) {
        val changed: Boolean = !owner.equals(next)
        if (changed) {
            picker.cancelSelection()
            if (viewer != null) {
                viewer!!.dismiss()
            }
        }
        owner = next
        profile = p
        supported = available
        view.attachmentPicker(ChatAttachments.supported(p))
        if (changed) {
            render()
        }
    }

    fun has(): Boolean {
        return !drafts.getOrDefault(owner, Collections.emptyList()).isEmpty()
    }

    fun busy(): Boolean {
        return preparing.contains(owner)
    }

    fun references(): JSONArray {
        val refs = JSONArray()
        for (a in drafts.getOrDefault(owner, Collections.emptyList())) {
            val ref = JSONObject()
            try {
                ref.put("id", a.getString("id"))
            } catch (ignored: JSONException) {
            }
            refs.put(ref)
        }
        return refs
    }

    fun accepted(run: String, refs: JSONArray) {
        val list: MutableList<JSONObject>? = drafts.get(run)
        if (list != null) {
            var i = 0
            while (i < refs.length()) {
                val id: String = refs.optJSONObject(i).optString("id")
                list.removeIf(Predicate<JSONObject> { a -> a.optString("id").equals(id) })
                i++
            }
        }
        if (run.equals(owner)) {
            render()
        }
    }

    private fun choose() {
        if (closed || !ChatAttachments.supported(profile)) {
            return
        }
        if (!supported) {
            Toast.makeText(activity, t("Для вложений перезапустите эту сессию после обновления и дождитесь подключения чата.", "For attachments, restart this session after updating and wait for the chat to connect."), Toast.LENGTH_LONG).show()
            return
        }
        if (busy() || drafts.getOrDefault(owner, Collections.emptyList()).size >= ChatAttachments.MAX_COUNT) {
            Toast.makeText(activity, t("Дождитесь подготовки файла. В одном сообщении — до 4 вложений.", "Wait for file preparation. Up to 4 attachments per message."), Toast.LENGTH_LONG).show()
            return
        }
        val run: String = owner
        val destination: WorkspaceStore.Session = profile!!.copy()
        val available: Int = ChatAttachments.MAX_COUNT - drafts.getOrDefault(run, Collections.emptyList()).size
        picker.chooseChatAttachments(available, BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>> { source, entries ->
            if (closed || !run.equals(owner)) {
                return@BiConsumer
            }
            val jobs: MutableList<Staging> = ArrayList<Staging>()
            for (entry in entries) {
                jobs.add(Staging { ChatAttachments.stage(activity, source, entry, destination) })
            }
            prepare(run, jobs)
        }, Runnable {
            activity.requestWorkspaceGallery(available, Consumer<MutableList<Uri?>> { uris ->
                if (closed || !run.equals(owner)) {
                    return@Consumer
                }
                val jobs: MutableList<Staging> = ArrayList<Staging>()
                for (uri in uris) {
                    jobs.add(Staging { ChatAttachments.stageUri(activity, uri, destination) })
                }
                prepare(run, jobs)
            })
        })
    }

    private fun interface Staging {
        @Throws(Exception::class)
        fun stage(): JSONObject
    }

    private fun prepare(run: String, jobs: MutableList<Staging>) {
        if (closed || jobs.isEmpty() || preparing.contains(run)) {
            return
        }
        if (jobs.size + drafts.getOrDefault(run, Collections.emptyList()).size > ChatAttachments.MAX_COUNT) {
            Toast.makeText(activity, t("В одном сообщении — до 4 вложений.", "Up to 4 attachments per message."), Toast.LENGTH_LONG).show()
            return
        }
        preparing.add(run)
        render()
        work.execute(Runnable {
            val ready: MutableList<JSONObject> = ArrayList<JSONObject>()
            var failure: Exception? = null
            for (job in jobs) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    break
                }
                try {
                    ready.add(job.stage())
                } catch (error: Exception) {
                    if (failure == null) {
                        failure = error
                    }
                }
            }
            val error: Exception? = failure
            main.post(Runnable {
                preparing.remove(run)
                if (closed) {
                    for (a in ready) {
                        discard(a)
                    }
                    return@Runnable
                }
                if (!ready.isEmpty()) {
                    drafts.computeIfAbsent(run, Function<String, MutableList<JSONObject>> { k -> ArrayList<JSONObject>() }).addAll(ready)
                }
                while (drafts.size > 32) {
                    val oldest: String = drafts.keys.iterator().next()
                    for (a in drafts.remove(oldest)!!) {
                        discard(a)
                    }
                }
                if (run.equals(owner)) {
                    render()
                    if (error != null) {
                        Toast.makeText(activity, error(error), Toast.LENGTH_LONG).show()
                    }
                }
            })
        })
    }

    private fun error(e: Exception): String {
        val code: String? = e.message
        if ("SUPPORTED_TYPES".equals(code)) {
            return t("Выберите изображение, текстовый файл/лог или видео MP4, MKV, WebM, MOV, M4V, 3GP.", "Choose an image, text/log file, or MP4, MKV, WebM, MOV, M4V, 3GP video.")
        }
        if ("FILE_LIMIT".equals(code)) {
            return t("Файл пуст или слишком большой: изображения до 100 МБ, остальные файлы до 5 ГБ.", "The file is empty or too large: images up to 100 MB, other files up to 5 GB.")
        }
        return t("Не удалось подготовить вложение. Проверьте доступ, формат файла и свободное место.", "Could not prepare the attachment. Check access, file format and free space.")
    }

    private fun render() {
        view.attachmentDrafts.removeAllViews()
        if (busy()) {
            view.attachmentDrafts.addView(view.label(t("Подготовка вложения…", "Preparing attachment…"), 12))
        }
        for (a in drafts.getOrDefault(owner, Collections.emptyList())) {
            val row = LinearLayout(activity)
            row.setGravity(Gravity.CENTER_VERTICAL)
            val name: TextView = view.label(a.optString("name") + (if (a.optString("type").equals("video")) t(" · до 12 кадров, без звука", " · up to 12 frames, no audio") else ""), 12)
            name.setMaxLines(2)
            name.setOnClickListener(View.OnClickListener { v -> open(a) })
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1.0f))
            val remove: TextView = view.pill("×", Runnable {
                val list: MutableList<JSONObject>? = drafts.get(owner)
                if (list != null && list.remove(a)) {
                    discard(a)
                    render()
                }
            })
            remove.setTextColor(Color.TRANSPARENT)
            val cross = LayerDrawable(arrayOf<Drawable?>(activity.getDrawable(R.drawable.workspace_attachment_remove)))
            cross.setLayerSize(0, view.dp(18), view.dp(18))
            cross.setLayerGravity(0, Gravity.CENTER)
            remove.setForeground(cross)
            remove.setContentDescription(t("Убрать вложение", "Remove attachment"))
            row.addView(remove)
            view.attachmentDrafts.addView(row)
        }
        view.attachmentDrafts.setVisibility(if (busy() || has()) View.VISIBLE else View.GONE)
        view.scaleContent()
    }

    private val thumbnails: ArrayDeque<WeakReference<ImageView>> = ArrayDeque<WeakReference<ImageView>>()

    fun thumbnail(a: JSONObject, target: ImageView) {
        val token = Any()
        target.setTag(token)
        thumbnails.add(WeakReference<ImageView>(target))
        while (thumbnails.size > 12) {
            val old: ImageView? = thumbnails.removeFirst().get()
            if (old != null) {
                old.setTag(null)
                old.setImageDrawable(null)
            }
        }
        val run: String = owner
        val p: WorkspaceStore.Session? = profile
        if (p == null) {
            return
        }
        var image: JSONObject = a
        if (a.optString("type").equals("video")) {
            val frames: JSONArray? = a.optJSONArray("frames")
            if (frames == null || frames.length() == 0) {
                return
            }
            try {
                image = JSONObject().put("path", frames.getJSONObject(0).getString("path"))
            } catch (e: JSONException) {
                return
            }
        }
        if (!a.optString("type").equals("image") && !a.optString("type").equals("video")) {
            return
        }
        val requested: JSONObject = image
        work.execute(Runnable {
            var bitmap: Bitmap? = null
            try {
                bitmap = ChatAttachments.bitmap(ChatAttachments.materialize(activity, p, requested), 320)
            } catch (ignored: Exception) {
            }
            val b: Bitmap? = bitmap
            main.post(Runnable {
                if (!closed && run.equals(owner) && target.getTag() === token && target.getParent() != null && b != null) {
                    target.setImageBitmap(b)
                }
            })
        })
    }

    private fun apkDetails(a: JSONObject, target: TextView) {
        val token = Any()
        target.setTag(token)
        val run: String = owner
        val p: WorkspaceStore.Session? = profile
        if (p == null || closed) {
            return
        }
        work.execute(Runnable {
            var info: String
            try {
                val file: File? = ChatAttachments.materialize(activity, p, a)
                val hash: String = ChatAttachments.fileSha256(file)
                info = Formatter.formatFileSize(activity, file!!.length()) + "\nSHA-256: " + hash
            } catch (e: Exception) {
                info = t("Файл недоступен", "File unavailable")
            }
            val text: String = info
            main.post(Runnable {
                if (!closed && run.equals(owner) && target.getTag() === token && target.getParent() != null) {
                    target.setText(text)
                }
            })
        })
    }

    fun open(a: JSONObject) {
        if (closed || profile == null) {
            return
        }
        if (viewer != null) {
            viewer!!.dismiss()
        }
        val run: String = owner
        val p: WorkspaceStore.Session? = profile
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val loading: TextView = view.label(t("Загрузка…", "Loading…"), 13)
        box.addView(loading)
        val apk: Boolean = a.optString("type").equals("apk")
        val binary: Boolean = a.optString("type").equals("binary")
        val builder: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(a.optString("name", t("Вложение", "Attachment"))).setView(box)
        if (apk) {
            builder.setNeutralButton(t("В Download", "To Download"), null).setPositiveButton(t("Установить", "Install"), null)
        } else {
            builder.setPositiveButton(t("Сохранить в Download", "Save to Download"), null)
            if (!binary) {
                builder.setNegativeButton(t("Закрыть", "Close"), null)
            }
        }
        val dialog: AlertDialog = builder.create()
        viewer = dialog
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.getWindow()!!.setLayout(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT)
        val saveButton: Int = if (apk) DialogInterface.BUTTON_NEUTRAL else DialogInterface.BUTTON_POSITIVE
        dialog.getButton(saveButton).setEnabled(false)
        if (apk) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false)
        }
        work.execute(Runnable {
            var file: File? = null
            var bitmap: Bitmap? = null
            var text = ""
            var mediaWidth = 0
            var mediaHeight = 0
            var failure: Exception? = null
            try {
                file = ChatAttachments.materialize(activity, p, a)
                val type: String = a.optString("type")
                if (type.equals("image")) {
                    bitmap = ChatAttachments.bitmap(file, 2048)
                    mediaWidth = bitmap.getWidth()
                    mediaHeight = bitmap.getHeight()
                } else if (type.equals("video")) {
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(file!!.getPath())
                        mediaWidth = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                        mediaHeight = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                        val rotation: String? = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        if ("90".equals(rotation) || "270".equals(rotation)) {
                            val swap: Int = mediaWidth
                            mediaWidth = mediaHeight
                            mediaHeight = swap
                        }
                    } finally {
                        metadata.release()
                    }
                } else if (type.equals("apk")) {
                    text = Formatter.formatFileSize(activity, file!!.length()) + "\nSHA-256: " + ChatAttachments.fileSha256(file) + t("\n\nХеш описывает этот файл, но не подтверждает доверие к его автору. Установку подтверждает Android.", "\n\nThe hash identifies this file; it does not establish publisher trust. Android confirms installation.")
                } else if (type.equals("binary")) {
                    text = ChatAttachments.badge(a.optString("name")) + " · " + Formatter.formatFileSize(activity, file!!.length())
                } else if (type.equals("file")) {
                    text = ChatAttachments.textPreview(file, 65536) + (if (file!!.length() > 65536) t("\n\nПоказаны первые 64 КБ. Полный файл сохранён во вложении.", "\n\nShowing the first 64 KB. The complete file remains attached.") else "")
                }
            } catch (e: Exception) {
                failure = e
            }
            val ready: File? = file
            val image: Bitmap? = bitmap
            val content: String = text
            val error: Exception? = failure
            val originalWidth: Int = mediaWidth
            val originalHeight: Int = mediaHeight
            main.post(Runnable {
                if (closed || !run.equals(owner) || !dialog.isShowing()) {
                    return@Runnable
                }
                box.removeAllViews()
                if (error != null) {
                    box.addView(view.label(t("Вложение недоступно: исходный файл удалён или нет доступа.", "Attachment unavailable: the source was removed or access is missing."), 14))
                    return@Runnable
                }
                if (image != null) {
                    val img = ZoomImage(activity)
                    img.setImageBitmap(image)
                    box.addView(img, compactMedia(dialog, originalWidth, originalHeight))
                } else if (a.optString("type").equals("video")) {
                    val video = VideoView(activity)
                    val controls = MediaController(activity)
                    controls.setAnchorView(video)
                    video.setMediaController(controls)
                    video.setVideoPath(ready!!.getPath())
                    box.addView(video, compactMedia(dialog, originalWidth, originalHeight))
                    box.addView(view.label(t("Агенту передаются выбранные кадры с временными метками, без звука.", "The agent receives sampled timestamped frames, without audio."), 12))
                    dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> video.stopPlayback() })
                    video.start()
                } else if (apk) {
                    val details: TextView = view.label(content, 12)
                    details.setTextIsSelectable(true)
                    box.addView(details)
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true)
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v -> install(ready) })
                } else if (binary) {
                    val body: TextView = view.label(content, 13)
                    body.setTextIsSelectable(true)
                    box.addView(body)
                } else {
                    val scroll = ScrollView(activity)
                    val body: TextView = view.label(content, 13)
                    body.setTextIsSelectable(true)
                    scroll.addView(body)
                    box.addView(scroll, LinearLayout.LayoutParams(-1, Math.min(view.dp(420), Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.6f))))
                }
                dialog.getButton(saveButton).setEnabled(true)
                dialog.getButton(saveButton).setOnClickListener(View.OnClickListener { v ->
                    activity.requestWorkspaceStorageAccess(Runnable { save(ready, a.optString("name", "attachment"), dialog, saveButton) })
                })
            })
        })
    }

    private fun install(apk: File?) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                WorkspaceDialogs.builder(activity).setTitle(t("Разрешение на установку", "Installation permission")).setMessage(t("Разрешите установку из AgentDroid в настройках Android, затем вернитесь и снова нажмите «Установить».", "Allow installs from AgentDroid in Android settings, then return and tap Install again.")).setPositiveButton(t("Настройки Android", "Android settings"), DialogInterface.OnClickListener { d, w ->
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())))
                    } catch (e: Exception) {
                        Toast.makeText(activity, t("Не удалось открыть настройки", "Could not open settings"), Toast.LENGTH_LONG).show()
                    }
                }).setNegativeButton(t("Отмена", "Cancel"), null).show()
                return
            }
            activity.startActivity(installIntent(activity, apk))
        } catch (e: Exception) {
            Toast.makeText(activity, t("Не удалось открыть установщик APK", "Could not open the APK installer"), Toast.LENGTH_LONG).show()
        }
    }

    private fun compactMedia(dialog: AlertDialog, width: Int, height: Int): LinearLayout.LayoutParams {
        val display: DisplayMetrics = activity.getResources().getDisplayMetrics()
        val size: IntArray = mediaBounds(display.widthPixels, display.heightPixels, width, height, display.density)
        val window: Int = Math.min(Math.round(display.widthPixels * 0.94f), Math.max(view.dp(280), size[0] + view.dp(48)))
        dialog.getWindow()!!.setLayout(window, ViewGroup.LayoutParams.WRAP_CONTENT)
        val params = LinearLayout.LayoutParams(size[0], size[1])
        params.gravity = Gravity.CENTER_HORIZONTAL
        return params
    }

    private fun save(source: File?, name: String, dialog: AlertDialog, saveButton: Int) {
        dialog.getButton(saveButton).setEnabled(false)
        work.execute(Runnable {
            var ok = false
            try {
                saveAttachment(source, File("/sdcard/Download"), name, BooleanSupplier { closed })
                ok = true
            } catch (e: Exception) {
            }
            val saved: Boolean = ok
            main.post(Runnable {
                if (closed) {
                    return@Runnable
                }
                if (dialog.isShowing()) {
                    dialog.getButton(saveButton).setEnabled(true)
                }
                Toast.makeText(activity, if (saved) t("Сохранено в Download", "Saved to Download") else t("Не удалось сохранить файл", "Could not save the file"), Toast.LENGTH_LONG).show()
            })
        })
    }

    override fun close() {
        closed = true
        picker.close()
        if (viewer != null) {
            viewer!!.dismiss()
        }
        for (list in drafts.values) {
            for (a in list) {
                discard(a)
            }
        }
        drafts.clear()
        work.shutdownNow()
    }

    /** Fit initially, pinch to zoom and drag without recreating the chat surface.*/
    class ZoomImage : AppCompatImageView {
        private val transform: Matrix = Matrix()
        private var x: Float = 0f
        private var y: Float = 0f
        private var downX: Float = 0f
        private var downY: Float = 0f
        private var tap: Boolean = false
        private val scale: ScaleGestureDetector

        constructor(c: Context) : super(c) {
            setScaleType(ImageView.ScaleType.MATRIX)
            scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    transform.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY())
                    setImageMatrix(transform)
                    return true
                }
            })
        }

        protected override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (getDrawable() != null) {
                val s: Float = Math.min(w.toFloat() / getDrawable().getIntrinsicWidth(), h.toFloat() / getDrawable().getIntrinsicHeight())
                transform.setScale(s, s)
                transform.postTranslate((w - getDrawable().getIntrinsicWidth() * s) / 2, (h - getDrawable().getIntrinsicHeight() * s) / 2)
                setImageMatrix(transform)
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scale.onTouchEvent(e)
            val action: Int = e.getActionMasked()
            if (action == MotionEvent.ACTION_DOWN) {
                downX = e.getX()
                downY = e.getY()
                tap = true
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL || scale.isInProgress()) {
                tap = false
            }
            if (action == MotionEvent.ACTION_MOVE) {
                val slop: Int = ViewConfiguration.get(getContext()).getScaledTouchSlop()
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                    tap = false
                }
                if (!scale.isInProgress()) {
                    transform.postTranslate(e.getX() - x, e.getY() - y)
                    setImageMatrix(transform)
                }
            }
            if (action == MotionEvent.ACTION_UP) {
                if (tap) {
                    performClick()
                }
                tap = false
            }
            x = e.getX()
            y = e.getY()
            return true
        }
    }

    companion object {
        @JvmStatic
        fun installIntent(context: Context, apk: File?): Intent {
            val uri: Uri = FileProvider.getUriForFile(context, context.getPackageName() + ".workspace.files", apk!!)
            val intent: Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setClipData(ClipData.newRawUri("APK", uri))
            return intent
        }

        @JvmStatic
        fun mediaBounds(screenWidth: Int, screenHeight: Int, width: Int, height: Int, density: Float): IntArray {
            var width = width
            var height = height
            val maxWidth: Int = Math.max(1, Math.round(screenWidth * 0.94f - 48 * density))
            val maxHeight: Int = Math.max(1, Math.round(screenHeight * 0.82f - 144 * density))
            if (width <= 0 || height <= 0) {
                width = 16
                height = 9
            }
            val scale: Double = Math.min(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
            return intArrayOf(Math.max(1, Math.round(width * scale).toInt()), Math.max(1, Math.round(height * scale).toInt()))
        }

        @Throws(IOException::class)
        @JvmStatic
        fun saveAttachment(source: File?, directory: File, name: String, cancelled: BooleanSupplier): File {
            var safe: String = (name as java.lang.String).replaceAll("[\\\\/\\p{Cntrl}]", "_")
            if (safe.equals(".") || safe.equals("..") || safe.isEmpty()) {
                safe = "attachment"
            }
            if (safe.length > 150) {
                safe = safe.substring(0, 150)
            }
            (FileInputStream(source)).use { input ->
                return ProjectFileExport.save(input, directory, safe, java.lang.Long.MAX_VALUE, cancelled)
            }
        }
    }
}
