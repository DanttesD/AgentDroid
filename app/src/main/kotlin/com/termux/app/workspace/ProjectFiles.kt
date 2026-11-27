package com.termux.app.workspace

import android.app.Activity
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Comparator
import java.util.Locale
import java.util.function.Function
import java.util.regex.Matcher

/** Bounded, independent file operations. Never types into the user's terminal.*/
class ProjectFiles : AutoCloseable {
    @JvmField val activity: Activity
    @JvmField val profile: WorkspaceStore.Session
    private val transport: ProjectFileTransport = ProjectFileTransport()
    @Volatile private var closed: Boolean = false
    private var password: CharArray? = null
    private var serverKnownHosts: File? = null

    fun setServerKnownHosts(file: File) {
        serverKnownHosts = file
    }

    constructor(activity: Activity, profile: WorkspaceStore.Session?) {
        this.activity = activity
        this.profile = profile!!.copy()
        this.profile.id = profile.id
        this.profile.name = profile.name
        this.profile.handle = profile.handle
        this.profile.codexSocketId = profile.codexSocketId
        this.profile.sshEncryptedPassword = profile.sshEncryptedPassword
    }

    @Synchronized
    fun setPassword(value: CharArray) {
        if (closed) {
            return
        }
        if (password != null) {
            Arrays.fill(password, '\u0000')
        }
        password = value.clone()
    }

    @Synchronized
    private fun passwordCopy(): CharArray? {
        return if (password == null) null else password!!.clone()
    }

    class Entry {
        @JvmField val name: String
        @JvmField val path: String
        @JvmField val directory: Boolean
        @JvmField val size: Long
        @JvmField val modified: Long

        constructor(parent: String?, name: String, directory: Boolean, size: Long) : this(parent, name, directory, size, (-1).toLong())

        constructor(parent: String?, name: String, directory: Boolean, size: Long, modified: Long) {
            this.modified = modified
            this.name = name
            this.path = (if (parent!!.equals("/")) "/" else parent + "/") + name
            this.directory = directory
            this.size = size
        }
    }

    class Listing {
        @JvmField var path: String? = null
        @JvmField val entries: MutableList<Entry> = ArrayList<Entry>()
    }

    @Throws(Exception::class)
    fun list(path: String?): Listing {
        return parse(bytes(if (environment(profile).startsWith("proot:")) portableListScript(path) else listScript(path), MAX_LIST))
    }

    @Throws(Exception::class)
    fun createDirectory(parent: String?, name: String): Listing {
        if ((name as java.lang.String).trim().isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\n") || name.contains("\r") || name.indexOf('\u0000') >= 0) {
            throw IOException(WorkspaceText.tr("Введите имя одной папки без / и переносов"))
        }
        bytes("cd -- " + target(parent) + " && mkdir -- " + SessionShell.quote(name), 1024)
        return list((if (parent!!.equals("/")) "/" else parent + "/") + name)
    }

    @Throws(Exception::class)
    fun preview(e: Entry): ByteArray {
        if (e.size > MAX_PREVIEW) {
            throw IOException(WorkspaceText.tr("Текст больше 256 КБ; используйте терминал или экспорт"))
        }
        val data: ByteArray = bytes("test -f " + target(e.path) + " && exec cat -- " + target(e.path), MAX_PREVIEW)
        for (b in data) {
            if (b.toInt() == 0) {
                throw IOException(WorkspaceText.tr("Бинарный файл; доступен экспорт"))
            }
        }
        return data
    }

    @Throws(Exception::class)
    fun export(e: Entry, destination: File?, max: Long) {
        if (e.size > max) {
            throw IOException(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Файл превышает лимит выбранной операции" else "File exceeds the selected operation's size limit")
        }
        // Chat artifacts may be several GB. Keep bounded streaming and owned
        // child cancellation, without the small-query 90-second deadline.
        val timeout: Int = if (max > MAX_EXPORT) 6 * 60 * 60 else 90
        try {
            (FileOutputStream(destination)).use { out ->
                run("test -f " + target(e.path) + " && exec cat -- " + target(e.path), out, max, null, timeout)
            }
        } catch (error: Exception) {
            destination!!.delete()
            throw error
        }
    }

    @Throws(Exception::class)
    fun editRead(entry: Entry): TextFileEdits.Snapshot {
        if (entry.size > TextFileEdits.MAX_BYTES) {
            throw IOException(WorkspaceText.tr("Редактор поддерживает текст UTF-8 до 256 КБ. Бинарные файлы и повреждённый текст не редактируются."))
        }
        return TextFileEdits.parse(entry.path, bytes(TextFileEdits.readScript(entry.path), TextFileEdits.MAX_BYTES + 513))
    }

    @Throws(Exception::class)
    fun editSave(original: TextFileEdits.Snapshot, value: String) {
        val payload: ByteArray = TextFileEdits.encode(value)
        val output = ByteArrayOutputStream()
        try {
            run(TextFileEdits.saveScript(original), output, 1024L, payload)
        } catch (error: Exception) {
            throw IOException(WorkspaceText.tr("Не удалось сохранить файл. Проверьте права и доступность окружения; введённый текст сохранён в окне."))
        }
        if (!output.toString("UTF-8").equals("SAVED")) {
            throw IOException(WorkspaceText.tr("Файл изменился после открытия. Сохранение отменено: закройте редактор и откройте файл заново. Ваш текст остался в окне."))
        }
    }

    private fun packageScript(script: String): String {
        return if (environment(profile).startsWith("proot:")) EnvironmentPackages.nativeCommand(script) else script
    }

    @Throws(Exception::class)
    fun packageBackend(): EnvironmentPackages.Kind {
        return EnvironmentPackages.kind(bytes(packageScript(EnvironmentPackages.probe()), 128))
    }

    @Throws(Exception::class)
    fun packageQuery(kind: EnvironmentPackages.Kind?, tab: Int): ByteArray {
        return bytes(packageScript(EnvironmentPackages.query(kind, tab)), PackageCatalog.MAX_BYTES)
    }

    @Throws(Exception::class)
    fun packageDescription(kind: EnvironmentPackages.Kind?, entry: PackageCatalog.Entry, installed: Boolean): ByteArray {
        return bytes(packageScript(EnvironmentPackages.description(kind, entry, installed)), 256 * 1024)
    }

    @Throws(Exception::class)
    fun packageDescription(entry: PackageCatalog.Entry, installed: Boolean): ByteArray {
        try {
            return bytes(PackageCatalog.descriptionQuery(entry, installed), 256 * 1024)
        } catch (error: Exception) {
            if (closed) {
                throw error
            }
            throw IOException(WorkspaceText.tr("Описание недоступно. Обновите каталог или проверьте выбранное окружение."))
        }
    }

    @Throws(Exception::class)
    fun packageVersions(name: String): ByteArray {
        try {
            return bytes(if (environment(profile).startsWith("proot:")) EnvironmentPackages.nativeCommand("exec bash --noprofile --norc -c " + SessionShell.quote(PackageCatalog.versionsQuery(name))) else PackageCatalog.versionsQuery(name), 1024 * 1024)
        } catch (error: Exception) {
            if (closed) {
                throw error
            }
            throw IOException(WorkspaceText.tr("Не удалось прочитать доступные версии пакета. Проверьте APT и выбранное окружение."))
        }
    }

    @Throws(Exception::class)
    fun packageVersions(kind: EnvironmentPackages.Kind?, name: String): ByteArray {
        return bytes(packageScript(EnvironmentPackageVersions.query(kind, name)), EnvironmentPackageVersions.MAX_BYTES)
    }

    @Throws(Exception::class)
    fun packageQuery(tab: Int): ByteArray {
        try {
            return bytes(PackageCatalog.query(tab), PackageCatalog.MAX_BYTES)
        } catch (error: Exception) {
            if (closed) {
                throw error
            }
            throw IOException(WorkspaceText.tr("Не удалось прочитать пакеты. Проверьте наличие APT/dpkg и доступность выбранного окружения."))
        }
    }

    class TransferFailure : IOException {
        @JvmField val acknowledged: Int

        constructor(acknowledged: Int, cause: Throwable) : super("TRANSFER_FAILED", cause) {
            this.acknowledged = acknowledged
        }
    }

    @Throws(Exception::class)
    fun transferReady(entries: MutableList<Entry>, folder: String?, move: Boolean, rename: Boolean): Boolean {
        try {
            return String(bytes(ProjectFileTransfer.script(entries, folder, move, rename, true), 1024), StandardCharsets.UTF_8).equals("READY")
        } catch (error: Exception) {
            return false
        }
    }

    @Throws(Exception::class)
    fun transfer(entries: MutableList<Entry>, folder: String?, move: Boolean, rename: Boolean) {
        ProjectFileTransfer.execute(entries, folder, move, rename, ProjectFileTransfer.Runner { script, output, max -> run(script, output, max.toLong(), null, 6 * 60 * 60) })
    }

    @Throws(Exception::class)
    fun aliasOperation(script: String): String {
        val out = ByteArrayOutputStream()
        try {
            run("exec sh -s", out, 16384L, (script as java.lang.String).getBytes(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            val code: String = (String(out.toByteArray(), StandardCharsets.UTF_8) as java.lang.String).trim()
            if ((code as java.lang.String).matches("ALIAS_[A-Z_]+")) {
                throw IOException(code)
            }
            throw error
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    @Throws(Exception::class)
    private fun bytes(script: String, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        run(script, out, max.toLong())
        return out.toByteArray()
    }

    @Throws(Exception::class)
    private fun run(script: String, out: OutputStream, max: Long) {
        run(script, out, max, null)
    }

    @Throws(Exception::class)
    private fun run(script: String, out: OutputStream, max: Long, input: ByteArray?) {
        run(script, out, max, input, 90)
    }

    @Throws(Exception::class)
    private fun run(script: String, out: OutputStream, max: Long, input: ByteArray?, timeoutSeconds: Int) {
        if (closed || serverKnownHosts != null && ServerConnection.disconnected(activity, profile.id)) {
            throw IOException(WorkspaceText.tr("Окно закрыто"))
        }
        var invocation: String = command(profile, script)
        if (serverKnownHosts != null && environment(profile).equals("ssh")) {
            invocation = (invocation as java.lang.String).replaceFirst("ssh -T", Matcher.quoteReplacement("ssh -T -o " + SessionShell.quote("UserKnownHostsFile=" + serverKnownHosts!!.getAbsolutePath()) + " -o GlobalKnownHostsFile=/dev/null"))
        }
        val builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", "exec " + invocation)
        builder.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        builder.environment().clear()
        for (item in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val eq: Int = item.indexOf('=')
            if (eq > 0) {
                builder.environment().put(item.substring(0, eq), item.substring(eq + 1))
            }
        }
        var broker: SshPasswordBroker? = null
        try {
            if (environment(profile).equals("ssh") && (profile.sshAuth.equals("password") || profile.sshAuth.equals("key") && !profile.sshEncryptedPassword.isEmpty())) {
                var secret: CharArray? = passwordCopy()
                if (secret == null && !profile.sshEncryptedPassword.isEmpty()) {
                    secret = SshPasswordVault.decrypt(profile)
                }
                if (secret == null) {
                    throw IOException(WorkspaceText.tr("Введите пароль для просмотра файлов"))
                }
                try {
                    broker = SshPasswordBroker(activity, secret, if (profile.sshAuth.equals("key")) SshKeyFiles.identity(profile.sshIdentity) else null)
                } finally {
                    Arrays.fill(secret, '\u0000')
                }
                val askpass: File = SessionShell.prepareAskpass(activity.getFilesDir(), profile, activity.getApplicationInfo().sourceDir)
                builder.environment().put("SSH_ASKPASS", askpass.getAbsolutePath())
                builder.environment().put("SSH_ASKPASS_REQUIRE", "force")
                builder.environment().put("DISPLAY", ":0")
                builder.environment().put("TERMUX_JOBS_SSH_SOCKET", broker!!.address)
                builder.environment().put("LC_ALL", "C")
            }
            builder.redirectError(File("/dev/null"))
            try {
                (transport.open(ProjectFileTransport.Starter(builder::start), input, max, timeoutSeconds * 1000L)).use { `in` ->
                    if (closed || serverKnownHosts != null && ServerConnection.disconnected(activity, profile.id)) {
                        throw IOException("CANCELLED")
                    }
                    val buffer = ByteArray(32768)
                    var n: Int = 0
                    while (true) {
                        n = `in`.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                    }
                }
            } catch (error: IOException) {
                throw transportError(error)
            }
        } finally {
            if (broker != null) {
                broker.close()
            }
        }
    }

    private fun transportError(error: IOException): IOException {
        val code: String? = error.message
        if ("FILE_LIMIT".equals(code)) {
            return IOException(WorkspaceText.tr("Файл или список слишком большой"), error)
        }
        if ("CANCELLED".equals(code)) {
            return IOException(WorkspaceText.tr("Операция отменена"), error)
        }
        if ("FILE_OPERATION_TIMEOUT".equals(code)) {
            return IOException(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Время файловой операции истекло. Повторите попытку." else "File operation timed out. Please try again.", error)
        }
        if ("FILE_OPERATION_BUSY".equals(code)) {
            return IOException(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Другая файловая операция ещё выполняется." else "Another file operation is still running.", error)
        }
        if ("SOURCE_UNAVAILABLE".equals(code)) {
            return IOException(if (environment(profile).equals("ssh")) WorkspaceText.tr("SSH недоступен: проверьте вход, пароль и доверенный ключ сервера в терминале") else WorkspaceText.tr("Каталог или файл недоступен; проверьте путь и выбранную среду"), error)
        }
        return error
    }

    @Throws(Exception::class)
    fun localOperation(script: String) {
        if (environment(profile).equals("ssh")) {
            throw IOException("LOCAL_DESTINATION_REQUIRED")
        }
        bytes(script, 1024)
    }

    @Throws(Exception::class)
    fun openUpload(entry: Entry): InputStream {
        return openLocalRead(entry, ServerSftp.MAX_BYTES)
    }

    @Throws(Exception::class)
    fun openLocalRead(entry: Entry, maxBytes: Long): InputStream {
        val env: String = environment(profile)
        if (env.equals("ssh")) {
            throw IOException("LOCAL_SOURCE_REQUIRED")
        }
        if (entry.directory || entry.size < 0 || entry.size > maxBytes) {
            throw IOException("FILE_LIMIT")
        }
        val builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", "exec " + command(profile, "test -f " + target(entry.path) + " && exec cat -- " + target(entry.path)))
        builder.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        builder.environment().clear()
        for (item in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val eq: Int = item.indexOf('=')
            if (eq > 0) {
                builder.environment().put(item.substring(0, eq), item.substring(eq + 1))
            }
        }
        builder.redirectError(File("/dev/null"))
        return transport.open(ProjectFileTransport.Starter(builder::start), null, maxBytes, 6 * 60 * 60 * 1000L)
    }

    override fun close() {
        closed = true
        transport.close()
        synchronized(this) {
            if (password != null) {
                Arrays.fill(password, '\u0000')
                password = null
            }
        }
    }

    companion object {
        const val MAX_LIST: Int = 2 * 1024 * 1024
        const val MAX_PREVIEW: Int = 256 * 1024
        const val MAX_EXPORT: Long = 256L * 1024 * 1024

        @JvmStatic
        fun environment(s: WorkspaceStore.Session?): String {
            return if (!AgentPreparation.agent(s).isEmpty() && s!!.codexEnvironment.equals("ubuntu-ai")) "proot:ubuntu-ai" else if (!s!!.prootAlias.isEmpty()) "proot:" + s.prootAlias else if (s.environment.equals("codex")) s.codexEnvironment else if (s.environment.equals("legacy")) "termux" else s.environment
        }

        @JvmStatic
        fun environmentLabel(s: WorkspaceStore.Session?): String {
            val e: String = environment(s)
            return if (e.startsWith("proot:")) WorkspaceEnvironments.label(e.substring(6)) else if (e.equals("ubuntu")) "Ubuntu" else if (e.equals("ssh")) "SSH · " + s!!.sshHost else "Termux"
        }

        @JvmStatic
        fun target(path: String?): String {
            if (path == null || path.indexOf('\u0000') >= 0 || path.contains("\n") || path.contains("\r")) {
                throw IllegalArgumentException(WorkspaceText.tr("Некорректный путь"))
            }
            if (path.isEmpty() || path.equals("~")) {
                return "\"\$HOME\""
            }
            return if (path.startsWith("~/")) "\"\$HOME\"/" + SessionShell.quote(path.substring(2)) else SessionShell.quote(path)
        }

        @JvmStatic
        fun listScript(path: String?): String {
            return "cd -- " + target(path) + " || exit 2; printf 'JOBS_FILES_2\\0%s\\0' \"\$(pwd -P)\"; exec find -L . -mindepth 1 -maxdepth 1 -printf '%y\\0%f\\0%s\\0%T@\\0'"
        }

        @Throws(IOException::class)
        @JvmStatic
        fun parse(bytes: ByteArray): Listing {
            val fields: Array<String> = (String(bytes, StandardCharsets.UTF_8) as java.lang.String).split("\u0000", -1)
            val version2: Boolean = fields.size > 0 && fields[0].equals("JOBS_FILES_2")
            val start: Int = if (version2) 2 else 1
            val stride: Int = if (version2) 4 else 3
            if (fields.size < start + 1 || !fields[start - 1].startsWith("/") || (fields.size - start - 1) % stride != 0) {
                throw IOException(WorkspaceText.tr("Некорректный список файлов"))
            }
            val result = Listing()
            result.path = fields[start - 1]
            var i: Int = start
            while (i + stride - 1 < fields.size) {
                if (fields[i + 1].isEmpty() || fields[i + 1].contains("/") || fields[i + 1].equals(".") || fields[i + 1].equals("..")) {
                    throw IOException(WorkspaceText.tr("Некорректное имя файла"))
                }
                var size: Long
                try {
                    size = java.lang.Long.parseLong(fields[i + 2])
                } catch (e: NumberFormatException) {
                    throw IOException(WorkspaceText.tr("Некорректный размер файла"))
                }
                var modified: Long = (-1).toLong()
                if (version2) {
                    val value: String = fields[i + 3]
                    if (!(value as java.lang.String).matches("-?[0-9]{1,13}(\\.[0-9]{1,12})?")) {
                        throw IOException("INVALID_FILE_TIME")
                    }
                    val seconds: Double = java.lang.Double.parseDouble(value)
                    if (!java.lang.Double.isFinite(seconds) || Math.abs(seconds) > 9.0E12) {
                        throw IOException("INVALID_FILE_TIME")
                    }
                    modified = (seconds * 1000).toLong()
                }
                if (fields[i].equals("d") || fields[i].equals("f")) {
                    result.entries.add(Entry(result.path, fields[i + 1], fields[i].equals("d"), size, modified))
                }
                if (result.entries.size > 4000) {
                    throw IOException(WorkspaceText.tr("Слишком много файлов; выберите вложенную папку"))
                }
                i += stride
            }
            result.entries.sortWith(Comparator.comparing(Function<Entry, Boolean> { e -> !e.directory }).thenComparing(Function<Entry, String> { e -> (e.name as java.lang.String).toLowerCase(Locale.ROOT) }))
            return result
        }

        @JvmStatic
        fun mime(name: String): String {
            val n: String = (name as java.lang.String).toLowerCase(Locale.ROOT)
            if (n.endsWith(".apk")) {
                return "application/vnd.android.package-archive"
            }
            if (n.endsWith(".png")) {
                return "image/png"
            }
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                return "image/jpeg"
            }
            if (n.endsWith(".webp")) {
                return "image/webp"
            }
            if (n.endsWith(".gif")) {
                return "image/gif"
            }
            if (n.endsWith(".mp4") || n.endsWith(".m4v")) {
                return "video/mp4"
            }
            if (n.endsWith(".webm")) {
                return "video/webm"
            }
            if (n.endsWith(".mkv")) {
                return "video/x-matroska"
            }
            if (n.endsWith(".mov")) {
                return "video/quicktime"
            }
            if (n.endsWith(".3gp")) {
                return "video/3gpp"
            }
            val ext: String = if (n.lastIndexOf('.') < 0) "" else n.substring(n.lastIndexOf('.') + 1)
            if (Arrays.asList("md", "txt", "log", "json", "xml", "yml", "yaml", "java", "kt", "kts", "gradle", "sh", "py", "js", "ts", "css", "html", "rs", "toml", "properties", "c", "h", "cpp", "gitignore").contains(ext) || n.equals("makefile") || n.equals("dockerfile")) {
                return "text/plain"
            }
            return "application/octet-stream"
        }

        @JvmStatic
        fun visible(e: Entry, filter: String?): Boolean {
            return e.directory || filter!!.equals(WorkspaceText.tr("Все")) || filter.equals(WorkspaceText.tr("Текст")) && mime(e.name).equals("text/plain") || filter.equals(WorkspaceText.tr("Изображения")) && mime(e.name).startsWith("image/") || filter.equals("APK") && (e.name as java.lang.String).toLowerCase(Locale.ROOT).endsWith(".apk")
        }

        @JvmStatic
        fun portableListScript(path: String?): String {
            return "cd -- " + target(path) + " || exit 2; printf 'JOBS_FILES_2\\0%s\\0' \"\$(pwd -P)\"; for p in ./* ./.[!.]* ./..?*; do if [ -d \"\$p\" ]; then kind=d; metadata=\$(stat -L -c '%Y' -- \"\$p\") || exit 2; size=0; modified=\$metadata; elif [ -f \"\$p\" ]; then kind=f; metadata=\$(stat -L -c '%s %Y' -- \"\$p\") || exit 2; size=\${metadata%% *}; modified=\${metadata##* }; else continue; fi; printf '%s\\0%s\\0%s\\0%s\\0' \"\$kind\" \"\${p#./}\" \"\$size\" \"\$modified\"; done"
        }

        @JvmStatic
        fun command(s: WorkspaceStore.Session, script: String): String {
            val e: String = environment(s)
            val bash: String = "bash --noprofile --norc -c " + SessionShell.quote(script)
            if (e.startsWith("proot:")) {
                val alias: String = e.substring(6)
                if (!WorkspaceLocalFilePicker.validAlias(alias)) {
                    throw IllegalArgumentException("INVALID_ENVIRONMENT")
                }
                return "proot-distro " + WorkspaceEnvironments.verb(alias) + " " + (if (s.prootIsolated) "--isolated " else "") + SessionShell.quote(alias) + " -- /bin/sh -c " + SessionShell.quote(script)
            }
            if (e.equals("ubuntu")) {
                return "proot-distro login ubuntu -- /bin/" + bash
            }
            if (!e.equals("ssh")) {
                return bash
            }
            if (!SshSessionShell.validHost(s.sshHost) || !SshSessionShell.validUser(s.sshUser) || !SshSessionShell.validPort(s.sshPort)) {
                throw IllegalArgumentException(WorkspaceText.tr("Укажите корректные параметры SSH"))
            }
            var options = " -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=2 -o StrictHostKeyChecking=yes -o ControlMaster=no -o ControlPath=none"
            if (s.sshAuth.equals("key")) {
                if (!SshSessionShell.validIdentity(s.sshIdentity)) {
                    throw IllegalArgumentException(WorkspaceText.tr("Некорректный путь SSH-ключа"))
                }
                options += " -o IdentitiesOnly=yes -i " + target(s.sshIdentity)
            }
            if (s.sshAuth.equals("key") && !s.sshEncryptedPassword.isEmpty()) {
                options += " -o PreferredAuthentications=publickey -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o NumberOfPasswordPrompts=1"
            }
            options += if (s.sshAuth.equals("key") && !s.sshEncryptedPassword.isEmpty()) " -o BatchMode=no" else if (s.sshAuth.equals("password")) " -o BatchMode=no -o PreferredAuthentications=password -o PubkeyAuthentication=no -o KbdInteractiveAuthentication=no -o NumberOfPasswordPrompts=1" else " -o BatchMode=yes"
            return "ssh -T" + options + " -p " + s.sshPort + " -l " + SessionShell.quote(s.sshUser) + " -- " + SessionShell.quote(s.sshHost) + " " + SessionShell.quote(bash)
        }
    }
}
