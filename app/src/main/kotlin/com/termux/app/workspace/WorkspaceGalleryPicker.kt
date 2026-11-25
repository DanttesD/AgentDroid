package com.termux.app.workspace

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import java.util.Collections
import java.util.function.Consumer

/** Native media selection; grants only the URIs explicitly picked by the user.*/
class WorkspaceGalleryPicker {
    private val activity: Activity
    private var pending: Consumer<MutableList<Uri?>>? = null
    private var request: Int = 24000
    private var limit: Int = 0
    private var closed: Boolean = false

    constructor(activity: Activity) {
        this.activity = activity
    }

    fun pick(count: Int, selected: Consumer<MutableList<Uri?>>) {
        if (closed || pending != null) {
            return
        }
        limit = Math.max(1, Math.min(ChatAttachments.MAX_COUNT, count))
        pending = selected
        request++
        if (request > 28000) {
            request = 24001
        }
        try {
            activity.startActivityForResult(intent(limit, Build.VERSION.SDK_INT >= 33), request)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivityForResult(intent(limit, false), request)
            } catch (unavailable: ActivityNotFoundException) {
                val callback: Consumer<MutableList<Uri?>>? = pending
                pending = null
                callback!!.accept(Collections.emptyList())
            }
        }
    }

    fun result(code: Int, result: Int, data: Intent): Boolean {
        if (code != request) {
            return false
        }
        val callback: Consumer<MutableList<Uri?>>? = pending
        pending = null
        if (closed || callback == null) {
            return true
        }
        var items: MutableList<Uri?>
        try {
            items = if (result == Activity.RESULT_OK) selection(data, limit) else Collections.emptyList()
        } catch (error: IllegalArgumentException) {
            items = Collections.emptyList()
            Toast.makeText(activity, if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Выберите фото или видео: до " + limit + " вложений." else "Choose photos or videos: up to " + limit + " attachments.", Toast.LENGTH_LONG).show()
        }
        callback.accept(items)
        return true
    }

    fun close() {
        closed = true
        pending = null
    }

    companion object {
        @JvmStatic
        fun intent(limit: Int, photoPicker: Boolean): Intent {
            if (photoPicker) {
                val i = Intent(MediaStore.ACTION_PICK_IMAGES)
                if (limit > 1) {
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, limit)
                }
                return i
            }
            return Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES, arrayOf<String>("image/*", "video/*")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, limit > 1).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        @JvmStatic
        fun selection(data: Intent?, limit: Int): MutableList<Uri?> {
            val items = LinkedHashSet<Uri?>()
            if (data != null) {
                if (data.getData() != null) {
                    items.add(data.getData())
                }
                val clips: ClipData? = data.getClipData()
                if (clips != null) {
                    if (clips.getItemCount() > ChatAttachments.MAX_COUNT) {
                        throw IllegalArgumentException("MEDIA_LIMIT")
                    }
                    var i = 0
                    while (i < clips.getItemCount()) {
                        items.add(clips.getItemAt(i).getUri())
                        i++
                    }
                }
            }
            if (items.size > limit) {
                throw IllegalArgumentException("MEDIA_LIMIT")
            }
            for (uri in items) {
                if (uri == null || !"content".equals(uri.getScheme())) {
                    throw IllegalArgumentException("MEDIA_URI")
                }
            }
            return ArrayList<Uri?>(items)
        }
    }
}
