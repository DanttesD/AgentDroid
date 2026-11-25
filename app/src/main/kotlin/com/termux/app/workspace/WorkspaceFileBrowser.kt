package com.termux.app.workspace

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.termux.R
import com.termux.app.TermuxActivity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier

/** Project viewer and directory picker share one environment-aware backend.*/
class WorkspaceFileBrowser {
    fun interface DirectoryChoice {
        fun selected(path: String?)
    }

    fun interface FileChoice {
        fun selected(source: WorkspaceStore.Session, entry: ProjectFiles.Entry)
    }

    private var fileChoice: FileChoice? = null
    private var rootBoundary: String? = null

    fun setRootBoundary(root: String) {
        rootBoundary = root
    }

    private var fileChoices: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>? = null
    private val selectedFiles: LinkedHashMap<String, ProjectFiles.Entry> = LinkedHashMap<String, ProjectFiles.Entry>()
    private var chatChoices: Boolean = false
    private var choosingMultiple: Boolean = false
    private var selectionLimit: Int = 50

    fun setChatFileChoices(value: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>, limit: Int) {
        fileChoices = value
        chatChoices = true
        selectionLimit = Math.max(1, Math.min(ChatAttachments.MAX_COUNT, limit))
    }

    private val transferSelection: LinkedHashMap<String, ProjectFiles.Entry> = LinkedHashMap<String, ProjectFiles.Entry>()
    private var transferButton: TextView? = null
    private var clearTransferButton: TextView? = null

    private fun transfersEnabled(): Boolean {
        return choice == null && fileChoice == null && fileChoices == null
    }

    private fun updateTransfer() {
        if (transferButton == null) {
            return
        }
        val selected: Boolean = !transferSelection.isEmpty()
        transferButton!!.setEnabled(selected && !busy && listing != null)
        transferButton!!.setAlpha(if (selected) 1f else 0.4f)
        transferButton!!.setSelected(selected)
        transferButton!!.setBackground(WorkspaceDialogs.background(activity, selected))
        clearTransferButton!!.setVisibility(if (selected) View.VISIBLE else View.GONE)
        clearTransferButton!!.setText("× " + transferSelection.size)
        clearTransferButton!!.setEnabled(!busy)
        if (dialog != null && transfersEnabled()) {
            val all: Button? = dialog!!.getButton(DialogInterface.BUTTON_NEUTRAL)
            if (all != null) {
                all.setVisibility(if (selected) View.VISIBLE else View.GONE)
                all.setEnabled(selected && !busy && listing != null)
            }
        }
    }

    private var transferEpoch: Long = 0L
    private var transferChoice: AlertDialog? = null

    private fun invalidateTransferChoice() {
        transferEpoch++
        val old: AlertDialog? = transferChoice
        transferChoice = null
        if (old != null) {
            old.dismiss()
        }
    }

    private fun bindTransferChoice(choice: AlertDialog, epoch: Long) {
        transferChoice = choice
        choice.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            if (transferChoice === choice) {
                transferChoice = null
                if (transferEpoch == epoch) {
                    transferEpoch++
                }
            }
        })
    }

    private fun acceptTransferChoice(epoch: Long): Boolean {
        if (closed || busy || epoch != transferEpoch || transferChoice == null) {
            return false
        }
        transferChoice = null
        return true
    }

    private fun toggleTransfer(entry: ProjectFiles.Entry) {
        if (busy) {
            return
        }
        invalidateTransferChoice()
        if (transferSelection.containsKey(entry.path)) {
            transferSelection.remove(entry.path)
        } else if (transferSelection.size < ProjectFileTransfer.MAX_SELECTED) {
            transferSelection.put(entry.path, entry)
        } else {
            message!!.setText(t("Можно выбрать до 50 объектов", "Select up to 50 entries"))
            return
        }
        val scroll: ScrollView? = rows!!.getParent() as ScrollView?
        val y: Int = scroll!!.getScrollY()
        render()
        scroll.post(Runnable { scroll.scrollTo(0, y) })
    }

    private fun visibleEntry(e: ProjectFiles.Entry): Boolean {
        return !(choice != null && !e.directory) && ProjectFiles.visible(e, filter) && (e.name as java.lang.String).toLowerCase(Locale.ROOT).contains((search!!.getText().toString() as java.lang.String).toLowerCase(Locale.ROOT))
    }

    private fun selectAllVisible() {
        if (busy || listing == null || transferSelection.isEmpty()) {
            return
        }
        invalidateTransferChoice()
        var limited = false
        for (e in listing!!.entries) {
            if (!visibleEntry(e) || transferSelection.containsKey(e.path)) {
                continue
            }
            if (transferSelection.size >= ProjectFileTransfer.MAX_SELECTED) {
                limited = true
                break
            }
            transferSelection.put(e.path, e)
        }
        val scroll: ScrollView? = rows!!.getParent() as ScrollView?
        val y: Int = scroll!!.getScrollY()
        render()
        scroll.post(Runnable { scroll.scrollTo(0, y) })
        if (limited) {
            message!!.setText(t("Достигнут лимит: 50 выбранных объектов", "Selection limit reached: 50 entries"))
        }
    }

    fun setFileChoices(value: BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>>) {
        fileChoices = value
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun updateSelection() {
        if (dialog != null && fileChoices != null) {
            val select: Button = dialog!!.getButton(DialogInterface.BUTTON_POSITIVE)
            select.setText(t("Загрузить выбранные", "Upload selected") + " (" + selectedFiles.size + ")")
            select.setVisibility(if (!chatChoices || choosingMultiple) View.VISIBLE else View.GONE)
            select.setEnabled(!selectedFiles.isEmpty() && !busy)
        }
    }

    private fun chooseFile(entry: ProjectFiles.Entry) {
        if (fileChoices != null && chatChoices && !choosingMultiple) {
            fileChoices!!.accept(files.profile, Collections.singletonList(entry))
            dialog!!.dismiss()
            return
        }
        if (fileChoices != null) {
            if (selectedFiles.containsKey(entry.path)) {
                selectedFiles.remove(entry.path)
            } else if (selectedFiles.size < selectionLimit) {
                selectedFiles.put(entry.path, entry)
            } else {
                message!!.setText(t("Можно выбрать до ", "Select up to ") + selectionLimit + t(" файлов", " files"))
                return
            }
            val viewport: ScrollView? = rows!!.getParent() as ScrollView?
            val y: Int = viewport!!.getScrollY()
            render()
            viewport.post(Runnable { viewport.scrollTo(0, y) })
            updateSelection()
        } else if (fileChoice != null) {
            fileChoice!!.selected(files.profile, entry)
            dialog!!.dismiss()
        } else {
            openFile(entry)
        }
    }

    fun setFileChoice(value: FileChoice) {
        fileChoice = value
    }

    private val activity: TermuxActivity
    private val files: ProjectFiles

    interface TransferBackend {
        @Throws(Exception::class)
        fun ready(entries: MutableList<ProjectFiles.Entry>, destination: String?, move: Boolean, rename: Boolean): Boolean

        @Throws(Exception::class)
        fun run(entries: MutableList<ProjectFiles.Entry>, destination: String?, move: Boolean, rename: Boolean)

        @Throws(Exception::class)
        fun list(destination: String?): ProjectFiles.Listing
    }

    private val transferBackend: TransferBackend
    private val choice: DirectoryChoice?
    private val thumbnails: WorkspaceMediaThumbnails
    private var viewport: ScrollView? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var onClosed: Runnable? = null

    fun setOnClosed(callback: Runnable) {
        onClosed = callback
    }

    private var dialog: AlertDialog? = null
    private var sortDialog: AlertDialog? = null
    private val editors: MutableList<WorkspaceTextEditor> = ArrayList<WorkspaceTextEditor>()
    private var rows: LinearLayout? = null
    private var filters: LinearLayout? = null
    private var pathLabel: TextView? = null
    private var message: TextView? = null
    private var search: EditText? = null
    private var listing: ProjectFiles.Listing? = null
    private var path: String? = null
    private var filter: String? = WorkspaceText.tr("Все")
    @Volatile private var closed: Boolean = false
    private var busy: Boolean = false
    private var transferring: Boolean = false

    constructor(activity: TermuxActivity, profile: WorkspaceStore.Session, path: String?, choice: DirectoryChoice?) {
        this.activity = activity
        this.files = ProjectFiles(activity, profile)
        this.path = path
        this.choice = choice
        this.transferBackend = object : TransferBackend {
            @Throws(Exception::class)
            override fun ready(e: MutableList<ProjectFiles.Entry>, d: String?, m: Boolean, r: Boolean): Boolean {
                return files.transferReady(e, d, m, r)
            }

            @Throws(Exception::class)
            override fun run(e: MutableList<ProjectFiles.Entry>, d: String?, m: Boolean, r: Boolean) {
                files.transfer(e, d, m, r)
            }

            @Throws(Exception::class)
            override fun list(d: String?): ProjectFiles.Listing {
                return files.list(d)
            }
        }
        this.thumbnails = WorkspaceMediaThumbnails(activity, profile)
    }

    fun setServerKnownHosts(file: File) {
        files.setServerKnownHosts(file)
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    private fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    private fun row(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.HORIZONTAL)
        v.setGravity(Gravity.CENTER_VERTICAL)
        return v
    }

    private fun text(label: String?, size: Int): TextView {
        val t = TextView(activity)
        t.setText(label)
        t.setTextSize(size.toFloat())
        t.setTextColor(Pal.TEXT)
        return t
    }

    private fun action(label: String?, run: Runnable): TextView {
        var t: TextView
        if (label!!.equals("↑") || label.equals("⌂")) {
            val icon = WorkspaceIconButton(activity)
            icon.setTextColor(Pal.TEXT)
            icon.setMinHeight(dp(48))
            icon.setBackground(WorkspaceDialogs.background(activity, false))
            icon.setIcon(activity.getDrawable(if (label.equals("↑")) R.drawable.workspace_server_back else R.drawable.workspace_home)!!.mutate(), dp(22))
            icon.setContentDescription(if (label.equals("↑")) WorkspaceText.tr("Родительская папка") else WorkspaceText.tr("Домашний каталог"))
            t = icon
        } else {
            t = WorkspaceDialogs.action(activity, label, 0)
        }
        t.setOnClickListener(View.OnClickListener { v -> run.run() })
        return t
    }

    fun show() {
        val body: LinearLayout = column()
        body.setPadding(dp(12), 0, dp(12), dp(8))
        body.addView(text(ProjectFiles.environmentLabel(files.profile), 12))
        val navigation: LinearLayout = row()
        navigation.addView(action("↑", Runnable { load(parent(path)) }), LinearLayout.LayoutParams(dp(48), -2))
        pathLabel = text(path, 12)
        pathLabel!!.setMaxLines(2)
        pathLabel!!.setEllipsize(TextUtils.TruncateAt.MIDDLE)
        navigation.addView(pathLabel, LinearLayout.LayoutParams(0, -2, 1.0f))
        navigation.addView(action("⌂", Runnable { load("~") }), LinearLayout.LayoutParams(dp(48), -2))
        val sort = WorkspaceIconButton(activity)
        sort.setIcon(activity.getDrawable(R.drawable.workspace_sort)!!.mutate(), dp(22))
        sort.setTextColor(Pal.TEXT)
        sort.setBackground(WorkspaceDialogs.background(activity, false))
        sort.setTag("workspace-file-sort")
        sort.setContentDescription(t("Сортировка файлов", "Sort files"))
        sort.setOnClickListener(View.OnClickListener { v -> sortMenu() })
        navigation.addView(sort, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(navigation)
        search = EditText(activity)
        search!!.setTextColor(Pal.TEXT)
        search!!.setHintTextColor(Pal.MUTED)
        search!!.setTextSize(14.0f)
        search!!.setSingleLine(true)
        search!!.setHint(if (choice == null) WorkspaceText.tr("Найти файл или папку") else WorkspaceText.tr("Найти папку"))
        val searchBar: LinearLayout = row()
        searchBar.addView(search, LinearLayout.LayoutParams(0, -2, 1.0f))
        if (transfersEnabled()) {
            clearTransferButton = action("", Runnable {
                invalidateTransferChoice()
                transferSelection.clear()
                render()
            })
            clearTransferButton!!.setContentDescription(t("Снять всё выделение", "Clear selection"))
            searchBar.addView(clearTransferButton, LinearLayout.LayoutParams(-2, dp(48)))
            val down = WorkspaceIconButton(activity)
            down.setIcon(activity.getDrawable(R.drawable.workspace_diag_download)!!.mutate(), dp(22))
            down.setTextColor(Pal.ACCENT)
            down.setContentDescription(t("Копировать или переместить сюда", "Copy or move here"))
            down.setOnClickListener(View.OnClickListener { v -> transferMenu() })
            transferButton = down
            searchBar.addView(down, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        body.addView(searchBar)
        search!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, c: Int, b: Int) {
            }

            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
                render()
            }

            override fun afterTextChanged(e: Editable) {
            }
        })
        if (choice == null) {
            filters = row()
            val scroll = HorizontalScrollView(activity)
            scroll.setHorizontalScrollBarEnabled(false)
            scroll.addView(filters)
            body.addView(scroll)
            for (name in arrayOf<String?>(WorkspaceText.tr("Все"), WorkspaceText.tr("Текст"), WorkspaceText.tr("Изображения"), "APK")) {
                val item: TextView = action(name, Runnable {
                    filter = name
                    render()
                })
                item.setTextSize(13.0f)
                item.setMinHeight(dp(42))
                item.setPadding(dp(12), dp(8), dp(12), dp(8))
                styleFilter(activity, item, name!!.equals(filter))
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.setMarginEnd(dp(4))
                filters!!.addView(item, lp)
            }
        }
        message = text(WorkspaceText.tr("Загрузка…"), 12)
        body.addView(message)
        rows = column()
        val scroll = ScrollView(activity)
        viewport = scroll
        scroll.addView(rows)
        scroll.setOnScrollChangeListener(View.OnScrollChangeListener { v, x, y, ox, oy -> thumbnails.schedule(scroll) })
        scroll.addOnLayoutChangeListener(View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob -> thumbnails.schedule(scroll) })
        body.addView(scroll, LinearLayout.LayoutParams(-1, Math.max(dp(160), Math.min(dp(400), activity.getResources().getDisplayMetrics().heightPixels / 2))))
        val builder: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(if (choice == null) WorkspaceText.tr("Файлы · ") + files.profile.name else WorkspaceText.tr("Рабочий каталог")).setView(body).setNegativeButton(WorkspaceText.tr("Закрыть"), null)
        if (transfersEnabled()) {
            builder.setNeutralButton(t("Выбрать всё", "Select all"), null)
        }
        if (fileChoices != null) {
            builder.setPositiveButton(t("Загрузить выбранные", "Upload selected"), null)
        }
        if (choice != null) {
            builder.setPositiveButton(WorkspaceText.tr("Выбрать папку"), null).setNeutralButton(WorkspaceText.tr("Создать папку"), null)
        }
        dialog = builder.create()
        dialog!!.setOnDismissListener(DialogInterface.OnDismissListener { d -> close() })
        dialog!!.setOnShowListener(DialogInterface.OnShowListener { d ->
            if (transfersEnabled()) {
                dialog!!.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v -> selectAllVisible() })
            }
            if (choice != null) {
                dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                    if (!busy && listing != null) {
                        choice.selected(listing!!.path)
                        dialog!!.dismiss()
                    }
                })
                dialog!!.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v -> createFolder() })
            }
            if (fileChoices != null) {
                dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                    if (!busy && !selectedFiles.isEmpty()) {
                        fileChoices!!.accept(files.profile, ArrayList<ProjectFiles.Entry>(selectedFiles.values))
                        dialog!!.dismiss()
                    }
                })
                updateSelection()
            }
        })
        dialog!!.show()
        updateTransfer()
        if (ProjectFiles.environment(files.profile).equals("ssh") && files.profile.sshAuth.equals("password") && files.profile.sshEncryptedPassword.isEmpty()) {
            askPassword()
        } else {
            load(path)
        }
    }

    private fun askPassword() {
        val input = EditText(activity)
        input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        input.setSaveEnabled(false)
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Пароль SSH для файлов")).setView(input).setPositiveButton(WorkspaceText.tr("Подключиться"), DialogInterface.OnClickListener { d, w ->
            val secret = CharArray(input.length())
            TextUtils.getChars(input.getText(), 0, input.length(), secret, 0)
            files.setPassword(secret)
            Arrays.fill(secret, '\u0000')
            input.setText("")
            load(path)
        }).setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> dialog!!.dismiss() }).show()
    }

    private fun interface Work {
        @Throws(Exception::class)
        fun run()
    }

    private fun execute(work: Work) {
        if (closed || busy) {
            return
        }
        busy = true
        updateSelection()
        updateTransfer()
        message!!.setText(if (transferring) t("Выполняется операция с файлами…", "Processing files…") else WorkspaceText.tr("Загрузка…"))
        if (choice != null) {
            dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false)
        }
        worker.execute(Runnable {
            try {
                work.run()
            } catch (e: Exception) {
                activity.runOnUiThread(Runnable {
                    if (!closed) {
                        message!!.setText(if (e.message == null) WorkspaceText.tr("Не удалось открыть файлы") else e.message)
                    }
                })
            } finally {
                activity.runOnUiThread(Runnable {
                    busy = false
                    if (transferring) {
                        transferring = false
                        if (!closed) {
                            dialog!!.setCancelable(true)
                            dialog!!.setCanceledOnTouchOutside(true)
                            dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(true)
                        }
                    }
                    if (!closed && choice != null) {
                        dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(listing != null)
                    }
                    if (!closed) {
                        updateSelection()
                        updateTransfer()
                    }
                })
            }
        })
    }

    private fun load(requested: String?) {
        var requested = requested
        if (closed || busy) {
            return
        }
        invalidateTransferChoice()
        if (rootBoundary != null) {
            try {
                val root: String = File(rootBoundary).getCanonicalPath()
                val candidate: String = File(if (requested!!.equals("~")) rootBoundary else requested).getCanonicalPath()
                requested = if (candidate.equals(root) || candidate.startsWith(root + "/")) candidate else rootBoundary
            } catch (e: IOException) {
                return
            }
        }
        if (busy) {
            return
        }
        thumbnails.reset()
        listing = null
        rows!!.removeAllViews()
        val targetPath: String? = requested
        execute(Work {
            val result: ProjectFiles.Listing = files.list(targetPath)
            activity.runOnUiThread(Runnable {
                if (closed) {
                    return@Runnable
                }
                listing = result
                path = result.path
                pathLabel!!.setText(path)
                render()
            })
        })
    }

    private fun render() {
        if (filters != null) {
            var i = 0
            while (i < filters!!.getChildCount()) {
                val t: TextView? = filters!!.getChildAt(i) as TextView?
                styleFilter(activity, t, t!!.getText().toString().equals(filter))
                i++
            }
        }
        if (rows == null || listing == null) {
            return
        }
        thumbnails.reset()
        rows!!.removeAllViews()
        var count = 0
        for (e in FileSort.ordered(listing!!.entries, FileSort.read(activity))) {
            if (!visibleEntry(e)) {
                continue
            }
            val item: TextView = action((if (e.directory) "▸  " else "▤  ") + e.name + (if (e.directory) "" else "\n" + Formatter.formatShortFileSize(activity, e.size)), Runnable {
                if (e.directory) {
                    load(e.path)
                } else {
                    chooseFile(e)
                }
            })
            if (!e.directory && fileChoices != null && (!chatChoices || choosingMultiple)) {
                item.setText((if (selectedFiles.containsKey(e.path)) "☑  " else "☐  ") + e.name + "\n" + Formatter.formatShortFileSize(activity, e.size))
                item.setSelected(selectedFiles.containsKey(e.path))
                item.setBackground(WorkspaceDialogs.background(activity, item.isSelected()))
            }
            item.setContentDescription((if (e.directory) WorkspaceText.tr("Папка ") else WorkspaceText.tr("Файл ")) + e.name)
            val line: LinearLayout = row()
            line.setOnClickListener(View.OnClickListener { v ->
                if (e.directory) {
                    load(e.path)
                } else {
                    chooseFile(e)
                }
            })
            if (chatChoices && choosingMultiple && !e.directory) {
                item.setText(e.name + "\n" + Formatter.formatShortFileSize(activity, e.size))
                val selected = CheckBox(activity)
                selected.setChecked(selectedFiles.containsKey(e.path))
                selected.setContentDescription(t("Выбрать ", "Select ") + e.name)
                selected.setOnClickListener(View.OnClickListener { v ->
                    chooseFile(e)
                    selected.setChecked(selectedFiles.containsKey(e.path))
                })
                line.addView(selected, LinearLayout.LayoutParams(dp(44), dp(48)))
            }
            if (chatChoices && !e.directory) {
                val mark = View.OnLongClickListener { v ->
                    if (busy) {
                        return@OnLongClickListener true
                    }
                    choosingMultiple = true
                    chooseFile(e)
                    return@OnLongClickListener true
                }
                item.setOnLongClickListener(mark)
                line.setOnLongClickListener(mark)
            }
            if (transfersEnabled()) {
                val checked: Boolean = transferSelection.containsKey(e.path)
                item.setSelected(checked)
                item.setBackground(WorkspaceDialogs.background(activity, checked))
                item.setOnLongClickListener(View.OnLongClickListener { v ->
                    toggleTransfer(e)
                    return@OnLongClickListener true
                })
                line.setOnLongClickListener(View.OnLongClickListener { v ->
                    toggleTransfer(e)
                    return@OnLongClickListener true
                })
                if (!transferSelection.isEmpty()) {
                    val check = CheckBox(activity)
                    check.setChecked(checked)
                    check.setContentDescription(t("Выбрать ", "Select ") + e.name)
                    check.setOnClickListener(View.OnClickListener { v -> toggleTransfer(e) })
                    line.addView(check, LinearLayout.LayoutParams(dp(44), dp(48)))
                }
            }
            if (thumbnails.enabled() && WorkspaceMediaThumbnails.media(e)) {
                val frame = FrameLayout(activity)
                val preview = ImageView(activity)
                val video: Boolean = ProjectFiles.mime(e.name).startsWith("video/")
                preview.setImageResource(if (video) R.drawable.workspace_media_video else R.drawable.workspace_media_photo)
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP)
                preview.setContentDescription(t("Миниатюра ", "Thumbnail ") + e.name)
                frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
                val shape = GradientDrawable()
                shape.setColor(Pal.CARD2)
                shape.setCornerRadius(dp(8).toFloat())
                frame.setBackground(shape)
                frame.setClipToOutline(true)
                if (video) {
                    val play = ImageView(activity)
                    play.setImageResource(R.drawable.workspace_play)
                    play.setColorFilter(0xffffffff.toInt())
                    play.setPadding(dp(3), dp(3), dp(3), dp(3))
                    val circle = GradientDrawable()
                    circle.setColor(0xcc000000.toInt())
                    circle.setShape(GradientDrawable.OVAL)
                    play.setBackground(circle)
                    val overlay = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END)
                    overlay.setMargins(dp(3), dp(3), dp(3), dp(3))
                    frame.addView(play, overlay)
                }
                val tile = LinearLayout.LayoutParams(dp(64), dp(64))
                tile.setMargins(dp(2), dp(4), dp(6), dp(4))
                line.addView(frame, tile)
                thumbnails.bind(line, preview, e)
            }
            line.addView(item, LinearLayout.LayoutParams(0, -2, 1.0f))
            rows!!.addView(line)
            count++
        }
        if (viewport != null) {
            thumbnails.schedule(viewport)
        }
        updateTransfer()
        message!!.setText(if (count == 0) WorkspaceText.tr("Нет подходящих файлов или папок") else WorkspaceText.tr("Открыт каталог выбранной среды · ") + count)
    }

    private fun sortMenu() {
        if (closed) {
            return
        }
        if (sortDialog != null) {
            sortDialog!!.dismiss()
        }
        sortDialog = WorkspaceDialogs.builder(activity).setTitle(t("Сортировка", "Sort files")).setSingleChoiceItems(FileSort.labels(activity), FileSort.read(activity).ordinal, DialogInterface.OnClickListener { d, which ->
            if (closed) {
                return@OnClickListener
            }
            FileSort.save(activity, FileSort.Order.values()[which])
            d.dismiss()
            render()
            if (viewport != null) {
                viewport!!.scrollTo(0, 0)
            }
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        sortDialog!!.show()
    }

    private fun transferMenu() {
        if (busy || listing == null || transferSelection.isEmpty()) {
            return
        }
        invalidateTransferChoice()
        val epoch: Long = transferEpoch
        val entries: MutableList<ProjectFiles.Entry> = ArrayList<ProjectFiles.Entry>(transferSelection.values)
        val destination: String? = listing!!.path
        val menu: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("В текущую папку", "Into current folder")).setMessage(destination + "\n" + t("Выбрано: ", "Selected: ") + entries.size).setPositiveButton(t("Копировать сюда", "Copy here"), DialogInterface.OnClickListener { d, w ->
            if (acceptTransferChoice(epoch)) {
                prepareTransfer(entries, destination, false, epoch)
            }
        }).setNeutralButton(t("Переместить сюда", "Move here"), DialogInterface.OnClickListener { d, w ->
            if (acceptTransferChoice(epoch)) {
                prepareTransfer(entries, destination, true, epoch)
            }
        }).setNegativeButton(t("Назад", "Back"), null).create()
        bindTransferChoice(menu, epoch)
        menu.setCanceledOnTouchOutside(false)
        menu.show()
    }

    private fun prepareTransfer(entries: MutableList<ProjectFiles.Entry>, destination: String?, move: Boolean, epoch: Long) {
        execute(Work {
            val ready: Boolean = transferBackend.ready(entries, destination, move, false)
            val renameReady: Boolean = ready || transferBackend.ready(entries, destination, move, true)
            activity.runOnUiThread(Runnable {
                if (closed || epoch != transferEpoch) {
                    return@Runnable
                }
                if (!renameReady) {
                    message!!.setText(t("Операция недоступна. Проверьте доступность путей и права. Нельзя выбирать папку вместе с её содержимым или помещать её внутрь самой себя.", "Operation unavailable. Check paths and permissions. A folder cannot be selected together with its contents or placed inside itself."))
                    return@Runnable
                }
                val explanation: String = destination + "\n\n" + (if (ready) t("Существующие объекты не заменяются. При прерывании уже завершённые действия сохраняются.", "Existing entries are not replaced. Completed actions remain if interrupted.") else t("Есть совпадающие имена. Добавить к новым именам (2), (3)…? Существующие объекты останутся без изменений.", "Some names already exist. Add (2), (3)… to new names? Existing entries will remain unchanged."))
                val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (move) t("Переместить выбранное?", "Move selection?") else t("Копировать выбранное?", "Copy selection?")).setMessage(explanation).setPositiveButton(t("Продолжить", "Continue"), DialogInterface.OnClickListener { d, w ->
                    if (acceptTransferChoice(epoch)) {
                        transferEpoch++
                        performTransfer(entries, destination, move, !ready)
                    }
                }).setNegativeButton(t("Назад", "Back"), null).create()
                bindTransferChoice(confirm, epoch)
                confirm.setCanceledOnTouchOutside(false)
                confirm.show()
            })
        })
    }

    private fun performTransfer(entries: MutableList<ProjectFiles.Entry>, destination: String?, move: Boolean, rename: Boolean) {
        if (closed || busy) {
            return
        }
        transferring = true
        dialog!!.setCancelable(false)
        dialog!!.setCanceledOnTouchOutside(false)
        dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false)
        execute(Work {
            var outcome: String
            try {
                transferBackend.run(entries, destination, move, rename)
                outcome = if (move) t("Перемещено: ", "Moved: ") + entries.size else t("Скопировано: ", "Copied: ") + entries.size
            } catch (failure: ProjectFiles.TransferFailure) {
                outcome = t("Завершение операции не подтверждено. Получено подтверждений: ", "Operation completion is unconfirmed. Acknowledged entries: ") + failure.acknowledged + " / " + entries.size + t(". Действий могло завершиться больше; операция может ещё выполняться в окружении или на сервере. Перед повтором проверьте исходную и конечную папки. Автоматического повтора нет.", ". More actions may have completed; work may still be running in the environment or on the server. Check both source and destination before retrying. There is no automatic retry.")
            }
            var refreshed: ProjectFiles.Listing? = null
            try {
                refreshed = transferBackend.list(destination)
            } catch (ignored: Exception) {
            }
            val result: ProjectFiles.Listing? = refreshed
            val status: String = outcome
            activity.runOnUiThread(Runnable {
                if (closed) {
                    return@Runnable
                }
                transferSelection.clear()
                if (result != null) {
                    listing = result
                    path = result.path
                    pathLabel!!.setText(path)
                }
                render()
                message!!.setText(status)
            })
        })
    }

    private fun createFolder() {
        if (busy || listing == null) {
            return
        }
        val input = EditText(activity)
        input.setSingleLine(true)
        input.setHint(WorkspaceText.tr("Название новой папки"))
        val create: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Новая пустая папка")).setView(input).setPositiveButton(WorkspaceText.tr("Создать"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        create.setOnShowListener(DialogInterface.OnShowListener { d ->
            create.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                val name: String = input.getText().toString()
                if ((name as java.lang.String).trim().isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
                    input.setError(WorkspaceText.tr("Введите имя одной папки"))
                    return@OnClickListener
                }
                create.dismiss()
                execute(Work {
                    val result: ProjectFiles.Listing = files.createDirectory(path, name)
                    activity.runOnUiThread(Runnable {
                        if (!closed) {
                            listing = result
                            path = result.path
                            pathLabel!!.setText(path)
                            render()
                        }
                    })
                })
            })
        })
        create.show()
    }

    private fun openFile(e: ProjectFiles.Entry) {
        val mime: String = ProjectFiles.mime(e.name)
        val actions: MutableList<String?> = ArrayList<String?>()
        if (mime.equals("text/plain") || mime.startsWith("image/")) {
            actions.add(WorkspaceText.tr("Просмотреть"))
        }
        if (mime.equals("text/plain")) {
            actions.add(WorkspaceText.tr("Редактировать"))
        }
        Collections.addAll(actions, WorkspaceText.tr("Поделиться"), WorkspaceText.tr("Копировать путь"), WorkspaceText.tr("В Download"))
        WorkspaceDialogs.builder(activity).setCustomTitle(fileHeading(e)).setItems(actions.toTypedArray(), DialogInterface.OnClickListener { d, w ->
            val a: String? = actions.get(w)
            if (a!!.equals(WorkspaceText.tr("Копировать путь"))) {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(WorkspaceText.tr("Путь файла"), e.path))
                activity.showToast(WorkspaceText.tr("Путь скопирован"), false)
            } else if (a.equals(WorkspaceText.tr("Просмотреть"))) {
                preview(e, mime)
            } else if (a.equals(WorkspaceText.tr("Редактировать"))) {
                edit(e)
            } else {
                export(e, a.equals(WorkspaceText.tr("В Download")))
            }
        }).setNegativeButton(WorkspaceText.tr("Закрыть"), null).show()
    }

    private fun fileHeading(e: ProjectFiles.Entry): View {
        val heading: LinearLayout = column()
        heading.setPadding(dp(20), dp(16), dp(20), dp(8))
        heading.addView(text(e.name, 17))
        heading.addView(text(Formatter.formatShortFileSize(activity, e.size), 12))
        return heading
    }

    fun serverProfileId(): String {
        return files.profile.id
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        invalidateTransferChoice()
        if (sortDialog != null) {
            sortDialog!!.dismiss()
        }
        thumbnails.close()
        if (dialog != null && dialog!!.isShowing()) {
            dialog!!.dismiss()
        }
        for (editor in editors) {
            editor.close()
        }
        editors.clear()
        try {
            files.close()
        } finally {
            worker.shutdownNow()
            listing = null
            selectedFiles.clear()
            transferSelection.clear()
            val callback: Runnable? = onClosed
            onClosed = null
            if (callback != null) {
                callback.run()
            }
        }
    }

    private fun preview(e: ProjectFiles.Entry, mime: String) {
        execute(Work {
            if (mime.startsWith("image/")) {
                if (e.size > 16L * 1024 * 1024) {
                    throw IOException(WorkspaceText.tr("Изображение больше 16 МБ; используйте экспорт"))
                }
                val temporary: File = cache(e.name)
                files.export(e, temporary, 16L * 1024 * 1024)
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeFile(temporary.getPath(), bounds)
                val options = BitmapFactory.Options()
                options.inSampleSize = 1
                while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
                    options.inSampleSize *= 2
                }
                val bitmap: Bitmap? = BitmapFactory.decodeFile(temporary.getPath(), options)
                temporary.delete()
                if (bitmap == null) {
                    throw IOException(WorkspaceText.tr("Не удалось прочитать изображение"))
                }
                activity.runOnUiThread(Runnable {
                    if (closed) {
                        bitmap.recycle()
                        return@Runnable
                    }
                    val image = ImageView(activity)
                    image.setAdjustViewBounds(true)
                    image.setImageBitmap(bitmap)
                    val view: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(e.name).setView(image).setNegativeButton(WorkspaceText.tr("Закрыть"), null).create()
                    view.setOnDismissListener(DialogInterface.OnDismissListener { d ->
                        image.setImageDrawable(null)
                        bitmap.recycle()
                    })
                    view.show()
                })
            } else {
                val value = String(files.preview(e), StandardCharsets.UTF_8)
                activity.runOnUiThread(Runnable {
                    if (closed) {
                        return@Runnable
                    }
                    val t: TextView = text(value, 13)
                    t.setTypeface(Typeface.MONOSPACE)
                    t.setTextIsSelectable(true)
                    t.setPadding(dp(14), dp(12), dp(14), dp(12))
                    val scroll = ScrollView(activity)
                    scroll.addView(t)
                    WorkspaceDialogs.builder(activity).setTitle(e.name).setView(scroll).setNegativeButton(WorkspaceText.tr("Закрыть"), null).show()
                })
            }
            activity.runOnUiThread(Runnable {
                if (!closed) {
                    message!!.setText(WorkspaceText.tr("Просмотр: ") + e.name)
                }
            })
        })
    }

    private fun edit(entry: ProjectFiles.Entry) {
        execute(Work {
            val snapshot: TextFileEdits.Snapshot = files.editRead(entry)
            activity.runOnUiThread(Runnable {
                if (closed) {
                    return@Runnable
                }
                val editor = WorkspaceTextEditor(activity, files, worker, snapshot, Runnable {
                    if (!closed) {
                        load(path)
                    }
                })
                editors.add(editor)
                editor.show()
            })
        })
    }

    @Throws(IOException::class)
    private fun cache(name: String): File {
        val dir = File(activity.getCacheDir(), "workspace-share/" + UUID.randomUUID())
        if (!dir.mkdirs()) {
            throw IOException(WorkspaceText.tr("Не удалось создать временный файл"))
        }
        return File(dir, name)
    }

    private fun export(e: ProjectFiles.Entry, download: Boolean) {
        execute(Work {
            val temporary: File = cache(e.name)
            var sharePending = false
            try {
                files.export(e, temporary, ProjectFiles.MAX_EXPORT)
                if (closed) {
                    return@Work
                }
                if (download) {
                    var destination: File
                    val directory: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val input = FileInputStream(temporary)
                    var primaryException: Throwable? = null
                    try {
                        destination = ProjectFileExport.save(input, directory, e.name, ProjectFiles.MAX_EXPORT, BooleanSupplier { closed })
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
                    activity.runOnUiThread(Runnable {
                        if (!closed) {
                            message!!.setText(WorkspaceText.tr("Сохранено: ") + destination.getName())
                            activity.showToast(WorkspaceText.tr("Файл сохранён в Download"), false)
                        }
                    })
                } else {
                    val uri: Uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".workspace.files", temporary)
                    activity.runOnUiThread(Runnable {
                        if (closed) {
                            releaseCache(temporary)
                            return@Runnable
                        }
                        val intent: Intent = Intent(Intent.ACTION_SEND).setType(ProjectFiles.mime(e.name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.setClipData(ClipData.newRawUri(WorkspaceText.tr("Файл проекта"), uri))
                        try {
                            activity.startActivity(Intent.createChooser(intent, WorkspaceText.tr("Поделиться файлом")))
                        } catch (error: ActivityNotFoundException) {
                            releaseCache(temporary)
                            message!!.setText(WorkspaceText.tr("Нет приложения для отправки файла"))
                        }
                    })
                    // Retain a successful share for the recipient's later URI read.
                    sharePending = true
                }
            } finally {
                if (!sharePending) {
                    releaseCache(temporary)
                }
            }
        })
    }

    companion object {
        @JvmStatic
        fun styleFilter(context: Context, item: TextView?, selected: Boolean) {
            item!!.setSelected(selected)
            item.setBackground(WorkspaceDialogs.background(context, selected))
            item.setTextColor(if (selected) Pal.ACCENT else Pal.MUTED)
            item.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            item.setContentDescription(WorkspaceText.tr("Фильтр файлов: ") + item.getText() + (if (selected) WorkspaceText.tr(", выбран") else ""))
        }

        @JvmStatic
        fun parent(path: String?): String {
            val slash: Int = path!!.lastIndexOf('/')
            return if (slash <= 0) "/" else path.substring(0, slash)
        }

        @JvmStatic
        private fun releaseCache(file: File) {
            file.delete()
            file.getParentFile()!!.delete()
        }
    }
}
