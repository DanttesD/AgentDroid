package com.termux.app.workspace

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.termux.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/** Manual public-release updates. Never reads shell credentials or changes user data.*/
object WorkspaceUpdates {
    /** One attempt per foreground opening, including failed/disabled/busy openings.*/
    class Opening {
        private var entered: Boolean = false

        fun enter(enabled: Boolean): Boolean {
            if (entered) {
                return false
            }
            entered = true
            return enabled
        }

        fun background(changingConfiguration: Boolean) {
            if (!changingConfiguration) {
                entered = false
            }
        }
    }

    class Release {
        @JvmField val code: Long
        @JvmField val size: Long
        @JvmField val name: String
        @JvmField val url: String
        @JvmField val hash: String
        @JvmField val notes: String
        @JvmField val minSdk: Int

        @Throws(Exception::class)
        constructor(m: JSONObject, tag: String, ru: Boolean) {
            if (m.getInt("schema") != 1) {
                throw IOException("Unsupported update manifest")
            }
            code = m.getLong("versionCode")
            name = m.getString("versionName")
            minSdk = m.getInt("minSdk")
            val apk: JSONObject = m.getJSONObject("apk")
            url = apk.getString("url")
            hash = apk.getString("sha256")
            size = apk.getLong("size")
            if (code <= 0 || !(name as java.lang.String).matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !(tag as java.lang.String).matches(TAG) || !(hash as java.lang.String).matches("[a-f0-9]{64}") || size <= 0 || size > MAX_APK || minSdk < 24 || !m.getString("packageId").equals("com.agentdroid") || !apk.getString("abi").equals("arm64-v8a") || !validAsset(url, tag, APK_NAME)) {
                throw IOException("Invalid update manifest")
            }
            val lines: JSONArray = m.getJSONObject("changes").getJSONArray(if (ru) "ru" else "en")
            if (lines.length() == 0 || lines.length() > 20) {
                throw IOException("Invalid release notes")
            }
            val b = StringBuilder()
            var i = 0
            while (i < lines.length()) {
                val s: String = lines.getString(i)
                if (s.length > 1000) {
                    throw IOException("Release note too long")
                }
                b.append("• ").append(s).append('\n')
                i++
            }
            notes = b.toString()
        }
    }

    /** UI state only. Download authenticity and cancellation remain in their existing worker.*/
    class UpdateProgress {
        @JvmField val view: LinearLayout
        @JvmField val bar: ProgressBar
        @JvmField val status: TextView
        private val activity: Activity

        constructor(a: Activity) {
            activity = a
            view = LinearLayout(a)
            view.setOrientation(LinearLayout.VERTICAL)
            val pad: Int = WorkspaceDialogs.dp(a, 20.0f)
            view.setPadding(pad, pad, pad, pad)
            status = TextView(a)
            status.setTextColor(Pal.TEXT)
            status.setTextSize(14.0f)
            view.addView(status)
            bar = ProgressBar(a, null, R.attr.progressBarStyleHorizontal)
            bar.setMax(100)
            val tint: ColorStateList = ColorStateList.valueOf(Pal.ACCENT)
            bar.setProgressTintList(tint)
            bar.setIndeterminateTintList(tint)
            val params = LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(a, 6.0f))
            params.topMargin = WorkspaceDialogs.dp(a, 16.0f)
            view.addView(bar, params)
            checking()
        }

        fun checking() {
            bar.setIndeterminate(true)
            status.setText(text(activity, "Проверка GitHub…", "Checking GitHub…"))
        }

        fun downloaded(bytes: Long, total: Long) {
            if (total <= 0) {
                bar.setIndeterminate(true)
                status.setText(text(activity, "Подключение к загрузке…", "Connecting to download…"))
                return
            }
            val count: Long = Math.max(0L, Math.min(bytes, total))
            val percent: Int = (count * 100 / total).toInt()
            bar.setIndeterminate(false)
            bar.setProgress(percent)
            status.setText(percent.toString() + "% · " + Formatter.formatFileSize(activity, count) + " / " + Formatter.formatFileSize(activity, total))
        }

        fun verifying() {
            bar.setIndeterminate(true)
            status.setText(text(activity, "Загрузка завершена. Проверка APK и подписи…", "Download complete. Verifying APK and signature…"))
        }
    }

    const val API: String = "https://api.github.com/repos/DanttesD/AgentDroid/releases?per_page=10"
    const val DOWNLOAD: String = "https://github.com/DanttesD/AgentDroid/releases/download/"
    const val APK_NAME: String = "agentdroid-debug.apk"
    const val TAG: String = "apk-[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-[0-9]+"
    private const val MAX_APK: Long = 256L * 1024 * 1024
    private val BUSY: AtomicBoolean = AtomicBoolean()

    @JvmStatic
    fun label(a: Activity): String {
        return text(a, "Проверить обновления", "Check for updates")
    }

    @JvmStatic
    private fun text(a: Activity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    private fun alive(a: Activity): Boolean {
        return !a.isFinishing() && !a.isDestroyed()
    }

    @JvmStatic
    private fun message(a: Activity, value: String) {
        if (alive(a)) {
            WorkspaceDialogs.builder(a).setTitle(label(a)).setMessage(value).setPositiveButton("OK", null).show()
        }
    }

    private val OPENING: Opening = Opening()

    @JvmStatic
    fun background(a: Activity) {
        OPENING.background(a.isChangingConfigurations())
    }

    @JvmStatic
    private fun prefs(a: Activity): SharedPreferences {
        return a.getSharedPreferences("workspace-updates", 0)
    }

    @JvmStatic
    fun autoEnabled(a: Activity): Boolean {
        return prefs(a).getBoolean("automatic", true)
    }

    @JvmStatic
    fun autoEnabled(a: Activity, enabled: Boolean) {
        prefs(a).edit().putBoolean("automatic", enabled).apply()
    }

    @JvmStatic
    fun available(a: Activity): Boolean {
        return prefs(a).getLong("availableCode", 0L) > BuildConfig.VERSION_CODE
    }

    @JvmStatic
    fun availableName(a: Activity): String? {
        return prefs(a).getString("availableName", "")
    }

    @JvmStatic
    fun menuLabel(a: Activity): String {
        return label(a) + (if (available(a)) " · " + prefs(a).getString("availableName", "") else "")
    }

    @JvmStatic
    fun menuRow(a: Activity, manual: Runnable): LinearLayout {
        val row = LinearLayout(a)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setBackground(WorkspaceDialogs.background(a, false))
        val title: TextView = WorkspaceDialogs.action(a, menuLabel(a), 0)
        title.setBackground(null)
        val labels = LinearLayout(a)
        labels.setOrientation(LinearLayout.VERTICAL)
        labels.addView(title)
        val hint = TextView(a)
        hint.setText(text(a, "Проверять при открытии приложения", "Check when opening the app"))
        hint.setTextColor(Pal.MUTED)
        hint.setTextSize(12.0f)
        val pad: Int = WorkspaceDialogs.dp(a, 16.0f)
        hint.setPadding(pad, 0, 0, WorkspaceDialogs.dp(a, 12.0f))
        labels.addView(hint)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1.0f))
        val automatic = CheckBox(a)
        automatic.setChecked(autoEnabled(a))
        automatic.setButtonTintList(ColorStateList.valueOf(Pal.ACCENT))
        automatic.setContentDescription(text(a, "Автопроверка при открытии приложения", "Check automatically when opening the app"))
        automatic.setText(text(a, "Авто", "Auto"))
        automatic.setTextSize(12.0f)
        automatic.setTextColor(Pal.MUTED)
        val size: Int = WorkspaceDialogs.dp(a, 54.0f)
        row.addView(automatic, LinearLayout.LayoutParams(WorkspaceDialogs.dp(a, 90.0f), size))
        automatic.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { button, checked -> autoEnabled(a, checked) })
        title.setOnClickListener(View.OnClickListener { v -> manual.run() })
        labels.setOnClickListener(View.OnClickListener { v -> manual.run() })
        row.setOnClickListener(View.OnClickListener { v -> manual.run() })
        return row
    }

    @JvmStatic
    private fun remember(a: Activity, r: Release) {
        prefs(a).edit().putLong("availableCode", r.code).putString("availableName", r.name).apply()
    }

    @JvmStatic
    fun automatic(a: Activity, changed: Runnable) {
        if (!OPENING.enter(autoEnabled(a)) || !BUSY.compareAndSet(false, true)) {
            return
        }
        Thread(Runnable {
            try {
                val release: Release? = latest(a, UpdateConnection())
                if (release != null) {
                    remember(a, release)
                }
            } catch (ignored: Exception) {
            } finally {
                BUSY.set(false)
                a.runOnUiThread(Runnable {
                    if (alive(a)) {
                        changed.run()
                    }
                })
            }
        }, "jobs-update-auto").start()
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun latest(a: Activity, cancelled: UpdateConnection): Release? {
        // Our CI publishes every build as a pre-release "apk-YYYY.MM.DD-N" with update.json; take the newest one that has it.
        val list = JSONArray(String(fetch(API, (2 * 1024 * 1024).toLong(), cancelled), Charsets.UTF_8))
        var r = 0
        while (r < list.length()) {
            val latest: JSONObject = list.getJSONObject(r)
            if (latest.optBoolean("draft")) {
                r++
                continue
            }
            val tag: String = latest.getString("tag_name")
            if (!(tag as java.lang.String).matches(TAG)) {
                r++
                continue
            }
            val assets: JSONArray = latest.getJSONArray("assets")
            var i = 0
            while (i < assets.length()) {
                val asset: JSONObject = assets.getJSONObject(i)
                if (asset.getString("name").equals("update.json")) {
                    val url: String = asset.getString("browser_download_url")
                    if (!validAsset(url, tag, "update.json")) {
                        throw IOException("Invalid manifest URL")
                    }
                    return Release(JSONObject(String(fetch(url, (64 * 1024).toLong(), cancelled), Charsets.UTF_8)), tag, WorkspaceLanguage.code(a)!!.equals("ru"))
                }
                i++
            }
            r++
        }
        return null
    }

    @JvmStatic
    fun version(p: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= 28) p.getLongVersionCode() else p.versionCode.toLong()
    }

    @JvmStatic
    fun validAsset(url: String, tag: String, name: String): Boolean {
        return (tag as java.lang.String).matches(TAG) && url.equals(DOWNLOAD + tag + "/" + name)
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun fetch(url: String, limit: Long, cancelled: UpdateConnection): ByteArray {
        val c: HttpURLConnection = cancelled.connect(url)
        try {
            (c.getInputStream()).use { `in` ->
                (ByteArrayOutputStream()).use { out ->
                    val b = ByteArray(8192)
                    var n: Int = 0
                    cancelled.check()
                    while (true) {
                        n = `in`.read(b)
                        if (n == -1) break
                        if (cancelled.isCancelled()) {
                            throw InterruptedIOException()
                        }
                        if (out.size() + n > limit) {
                            throw IOException("Response too large")
                        }
                        out.write(b, 0, n)
                    }
                    cancelled.check()
                    return out.toByteArray()
                }
            }
        } finally {
            cancelled.release(c)
        }
    }

    @JvmStatic
    fun check(a: Activity) {
        if (!BUSY.compareAndSet(false, true)) {
            message(a, text(a, "Проверка или скачивание уже выполняется.", "A check or download is already running."))
            return
        }
        val cancelled = UpdateConnection()
        val indicator = UpdateProgress(a)
        val wait: AlertDialog = WorkspaceDialogs.builder(a).setTitle(label(a)).setView(indicator.view).setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> cancelled.cancel() }).create()
        wait.setOnCancelListener(DialogInterface.OnCancelListener { d -> cancelled.cancel() })
        wait.show()
        Thread(Runnable {
            val result: Array<Runnable?> = arrayOf<Runnable?>(null)
            try {
                val r: Release? = latest(a, cancelled)
                if (r == null) {
                    result[0] = Runnable {
                        message(a, text(a, "Последний релиз пока не содержит данных для встроенного обновления. Установлено: ", "The latest release does not yet support in-app updates. Installed: ") + BuildConfig.VERSION_NAME)
                    }
                    return@Runnable
                }
                remember(a, r)
                val installed: PackageInfo = a.getPackageManager().getPackageInfo(a.getPackageName(), 0)
                result[0] = Runnable {
                    if (cancelled.isCancelled() || !alive(a)) {
                        return@Runnable
                    }
                    if (r.code <= version(installed)) {
                        message(a, text(a, "Установлена актуальная версия: ", "You are up to date: ") + installed.versionName)
                        return@Runnable
                    }
                    if (Build.VERSION.SDK_INT < r.minSdk || !Arrays.asList(*Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
                        message(a, text(a, "Обновление несовместимо с этим устройством.", "This update is not compatible with this device."))
                        return@Runnable
                    }
                    WorkspaceDialogs.builder(a).setTitle(text(a, "Обновление ", "Update ") + r.name).setMessage(r.notes + "\n" + text(a, "Данные сохраняются при обновлении поверх. Не удаляйте приложение. Завершите важные задачи: Android может остановить процессы приложения при установке.", "An in-place update keeps your data. Do not uninstall the app. Finish important tasks: Android may stop app processes during installation.")).setPositiveButton(text(a, "Скачать обновление", "Download update"), DialogInterface.OnClickListener { d, w -> download(a, r) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
                }
            } catch (e: Exception) {
                if (!cancelled.isCancelled()) {
                    result[0] = Runnable {
                        message(a, text(a, "Не удалось проверить обновления. Проверьте интернет и повторите позже.\n", "Could not check for updates. Check your connection and try later.\n") + safeError(e))
                    }
                }
            } finally {
                BUSY.set(false)
                a.runOnUiThread(Runnable {
                    if (alive(a)) {
                        wait.dismiss()
                        if (!cancelled.isCancelled() && result[0] != null) {
                            result[0]!!.run()
                        }
                    }
                })
            }
        }, "jobs-update-check").start()
    }

    @JvmStatic
    private fun safeError(e: Exception): String? {
        return if (e is IOException && e.message != null && (e.message!! as java.lang.String).matches("GitHub HTTP [0-9]{3}")) e.message else e.javaClass.getSimpleName()
    }

    @JvmStatic
    private fun download(a: Activity, r: Release) {
        if (!BUSY.compareAndSet(false, true)) {
            return
        }
        val cancelled = UpdateConnection()
        val indicator = UpdateProgress(a)
        indicator.downloaded(0L, r.size)
        val wait: AlertDialog = WorkspaceDialogs.builder(a).setTitle(text(a, "Скачивание обновления", "Downloading update")).setView(indicator.view).setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> cancelled.cancel() }).create()
        wait.setOnCancelListener(DialogInterface.OnCancelListener { d -> cancelled.cancel() })
        wait.show()
        Thread(Runnable {
            var output: File? = null
            var ready = false
            try {
                val dir = File(a.getCacheDir(), "workspace-updates")
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw IOException("Cache unavailable")
                }
                // Only updater-owned files; never touch user storage or shell environments.
                val old: Array<File>? = dir.listFiles()
                if (old != null) {
                    for (f in old) {
                        if (f.getName().startsWith("update-") && f.isFile()) {
                            f.delete()
                        }
                    }
                }
                output = File.createTempFile("update-", ".apk", dir)
                val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
                var count: Long = 0L
                var shown = -1
                val c: HttpURLConnection = cancelled.connect(r.url)
                try {
                    val `in` = c.getInputStream()
                    var primaryException: Throwable? = null
                    try {
                        val out = FileOutputStream(output)
                        var primaryException1: Throwable? = null
                        try {
                            val b = ByteArray(65536)
                            var n: Int = 0
                            cancelled.check()
                            while (true) {
                                n = `in`.read(b)
                                if (n == -1) break
                                if (cancelled.isCancelled()) {
                                    throw InterruptedIOException()
                                }
                                count += n
                                if (count > r.size) {
                                    throw IOException("APK size mismatch")
                                }
                                out.write(b, 0, n)
                                hash.update(b, 0, n)
                                val percent: Int = (count * 100 / r.size).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    val downloaded: Long = count
                                    a.runOnUiThread(Runnable {
                                        if (alive(a) && wait.isShowing()) {
                                            indicator.downloaded(downloaded, r.size)
                                        }
                                    })
                                }
                            }
                        } catch (t: Throwable) {
                            primaryException1 = t
                            throw t
                        } finally {
                            if (primaryException1 != null) {
                                try {
                                    out.close()
                                } catch (suppressed: Throwable) {
                                    primaryException1.addSuppressed(suppressed)
                                }
                            } else {
                                out.close()
                            }
                        }
                    } catch (t: Throwable) {
                        primaryException = t
                        throw t
                    } finally {
                        if (primaryException != null) {
                            try {
                                `in`.close()
                            } catch (suppressed: Throwable) {
                                primaryException.addSuppressed(suppressed)
                            }
                        } else {
                            `in`.close()
                        }
                    }
                } finally {
                    cancelled.release(c)
                }
                if (count != r.size || !hex(hash.digest()).equals(r.hash)) {
                    throw IOException("APK checksum mismatch")
                }
                a.runOnUiThread(Runnable {
                    if (alive(a) && wait.isShowing()) {
                        indicator.verifying()
                    }
                })
                verify(a, output, r)
                if (cancelled.isCancelled()) {
                    throw InterruptedIOException()
                }
                ready = true
                val apk: File? = output
                a.runOnUiThread(Runnable {
                    if (alive(a) && !cancelled.isCancelled()) {
                        offerInstall(a, apk, r)
                    }
                })
            } catch (e: Exception) {
                if (!cancelled.isCancelled()) {
                    a.runOnUiThread(Runnable {
                        message(a, text(a, "Обновление не прошло проверку или не скачалось. Установка заблокирована; данные не изменены.\n", "Download or verification failed. Installation blocked; your data was not changed.\n") + safeError(e))
                    })
                }
            } finally {
                if (!ready && output != null) {
                    output.delete()
                }
                BUSY.set(false)
                a.runOnUiThread(Runnable {
                    if (alive(a)) {
                        wait.dismiss()
                    }
                })
            }
        }, "jobs-update-download").start()
    }

    /** Recheck the exact cached bytes after any time spent in permission/settings dialogs.*/
    @Throws(Exception::class)
    @JvmStatic
    fun verifyContent(file: File?, size: Long, expectedHash: String) {
        if (!file!!.isFile() || file.length() != size) {
            throw IOException("APK size mismatch")
        }
        val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
        var count: Long = 0L
        (FileInputStream(file)).use { input ->
            val buffer = ByteArray(65536)
            var n: Int = 0
            while (true) {
                n = input.read(buffer)
                if (n == -1) break
                count += n
                if (count > size) {
                    throw IOException("APK size mismatch")
                }
                hash.update(buffer, 0, n)
            }
        }
        if (count != size || !hex(hash.digest()).equals(expectedHash)) {
            throw IOException("APK checksum mismatch")
        }
    }

    @JvmStatic
    fun prepareInstall(a: Activity, apk: File?, release: Release) {
        if (!BUSY.compareAndSet(false, true)) {
            message(a, text(a, "Дождитесь окончания проверки или загрузки и повторите установку.", "Wait for the current check or download, then try Install again."))
            return
        }
        val cancelled = UpdateConnection()
        val indicator = UpdateProgress(a)
        indicator.verifying()
        val wait: AlertDialog = WorkspaceDialogs.builder(a).setTitle(label(a)).setView(indicator.view).setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> cancelled.cancel() }).create()
        wait.setOnCancelListener(DialogInterface.OnCancelListener { d -> cancelled.cancel() })
        wait.show()
        Thread(Runnable {
            var failure: Exception? = null
            try {
                verifyContent(apk, release.size, release.hash)
                cancelled.check()
                verify(a, apk, release)
                cancelled.check()
            } catch (e: Exception) {
                failure = e
            } finally {
                val error: Exception? = failure
                a.runOnUiThread(Runnable {
                    BUSY.set(false)
                    if (!alive(a)) {
                        return@Runnable
                    }
                    wait.dismiss()
                    if (cancelled.isCancelled()) {
                        return@Runnable
                    }
                    if (error != null) {
                        message(a, text(a, "Сохранённый APK недоступен или не прошёл повторную проверку. Скачайте обновление заново.", "The cached APK is unavailable or failed verification. Download the update again."))
                        return@Runnable
                    }
                    try {
                        val uri: Uri = FileProvider.getUriForFile(a, a.getPackageName() + ".workspace.files", apk!!)
                        val intent: Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.setClipData(ClipData.newRawUri("Update APK", uri))
                        a.startActivity(intent)
                    } catch (e: Exception) {
                        message(a, text(a, "Не удалось открыть установщик. Повторите загрузку обновления.", "Could not open the installer. Download the update again."))
                    }
                })
            }
        }, "jobs-update-install-check").start()
    }

    @Throws(Exception::class)
    @JvmStatic
    fun verify(a: Activity, file: File?, r: Release) {
        (ZipFile(file)).use { zip ->
            if (zip.getEntry("lib/arm64-v8a/libtermux.so") == null) {
                throw IOException("Missing ARM64 library")
            }
        }
        val pm: PackageManager = a.getPackageManager()
        val apk: PackageInfo? = pm.getPackageArchiveInfo(file!!.getPath(), PackageManager.GET_SIGNATURES)
        val installed: PackageInfo = pm.getPackageInfo(a.getPackageName(), PackageManager.GET_SIGNATURES)
        validatePackage(a.getPackageName(), installed, apk, r, Build.VERSION.SDK_INT)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun validatePackage(packageName: String, installed: PackageInfo, apk: PackageInfo?, r: Release, sdk: Int) {
        if (apk == null || !packageName.equals(apk.packageName) || version(apk) != r.code || version(apk) <= version(installed) || !r.name.equals(apk.versionName) || apk.applicationInfo == null || apk.applicationInfo!!.minSdkVersion > sdk || !sameSignatures(installed.signatures, apk.signatures)) {
            throw IOException("APK package, version or signature mismatch")
        }
    }

    @JvmStatic
    fun sameSignatures(a: Array<Signature>?, b: Array<Signature>?): Boolean {
        if (a == null || b == null || a.size == 0 || a.size != b.size) {
            return false
        }
        val x: MutableSet<Signature> = HashSet<Signature>(Arrays.asList(*a))
        val y: MutableSet<Signature> = HashSet<Signature>(Arrays.asList(*b))
        return x.size == a.size && x.equals(y)
    }

    @JvmStatic
    fun hex(b: ByteArray): String {
        val s = StringBuilder()
        for (v in b) {
            s.append(java.lang.String.format(Locale.ROOT, "%02x", v.toInt() and 255))
        }
        return s.toString()
    }

    @JvmStatic
    private fun offerInstall(a: Activity, apk: File?, r: Release) {
        WorkspaceDialogs.builder(a).setTitle(text(a, "Обновление проверено", "Update verified")).setMessage(text(a, "Пакет, версия, подпись и SHA-256 проверены. Android запросит подтверждение установки. Данные сохраняются при обновлении поверх.", "Package, version, signature and SHA-256 verified. Android will ask you to confirm installation. An in-place update keeps your data.")).setPositiveButton(text(a, "Установить", "Install"), DialogInterface.OnClickListener { d, w ->
            try {
                if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                    WorkspaceDialogs.builder(a).setTitle(text(a, "Разрешение на установку", "Installation permission")).setMessage(text(a, "Разрешите установку из этого приложения в настройках Android. После возвращения нажмите «Продолжить».", "Allow installation from this app in Android settings. When you return, tap Continue.")).setNeutralButton(text(a, "Настройки Android", "Android settings"), DialogInterface.OnClickListener { p, q ->
                        try {
                            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())))
                            offerInstall(a, apk, r)
                        } catch (e: Exception) {
                            message(a, text(a, "Не удалось открыть настройки Android.", "Could not open Android settings."))
                        }
                    }).setPositiveButton(text(a, "Продолжить", "Continue"), DialogInterface.OnClickListener { p, q -> offerInstall(a, apk, r) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
                    return@OnClickListener
                }
                prepareInstall(a, apk, r)
            } catch (e: Exception) {
                message(a, text(a, "Не удалось открыть установщик. Приложение не удалялось.", "Could not open the installer. The app was not uninstalled."))
            }
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }
}
