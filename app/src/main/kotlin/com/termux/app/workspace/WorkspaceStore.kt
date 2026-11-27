package com.termux.app.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Small versioned workspace, separate from Termux settings and shell processes.*/
class WorkspaceStore {
    class Command {
        @JvmField var id: String = UUID.randomUUID().toString()
        @JvmField var name: String = ""
        @JvmField var command: String? = ""
        @JvmField var category: String? = WorkspaceText.tr("Общее")
        @JvmField var directory: String = ""
        @JvmField var favorite: Boolean = false

        fun copy(): Command {
            val c = Command()
            c.name = name + WorkspaceText.tr(" — копия")
            c.command = command
            c.category = category
            c.directory = directory
            c.favorite = favorite
            return c
        }
    }

    class Alias {
        @JvmField var id: String? = UUID.randomUUID().toString()
        @JvmField var name: String = ""
        @JvmField var command: String? = ""
        @JvmField var environment: String = "termux"
        @JvmField var category: String? = ""
        @JvmField var favorite: Boolean = false

        fun copy(): Alias {
            val a = Alias()
            a.id = id
            a.name = name
            a.command = command
            a.environment = environment
            a.category = category
            a.favorite = favorite
            return a
        }
    }

    class Session {
        @JvmField var id: String = UUID.randomUUID().toString()
        @JvmField var name: String? = "Bash"
        @JvmField var directory: String? = ""
        @JvmField var startup: String = ""
        @JvmField var handle: String = ""
        @JvmField @Transient var packageRuntimeStartup: String = ""
        @JvmField @Transient var packageOperation: PackageOperationJournal.Intent? = null
        @JvmField var startup2: String = ""
        @JvmField var startup3: String = ""
        @JvmField var environment: String = "legacy"
        @JvmField var prootAlias: String = ""
        @JvmField var prootIsolated: Boolean = false
        @JvmField var sshHost: String = ""
        @JvmField var sshUser: String = "root"
        @JvmField var sshPort: Int = 22
        @JvmField var chatScale: Int = 100
        @JvmField var restoreReview: Boolean = false
        @JvmField var sshKeepOpen: Boolean = true
        @JvmField var sshAuth: String = "auto"
        @JvmField var sshIdentity: String = ""
        @JvmField var sshEncryptedPassword: String = ""
        @JvmField var codexEnvironment: String = "ubuntu"
        @JvmField var codexAccess: String = "full"
        @JvmField var codexChat: String = "new"
        @JvmField var codexThread: String = ""
        @JvmField var codexThreadName: String? = ""
        @JvmField var codexSocketId: String = UUID.randomUUID().toString()
        @JvmField var codexSourceDirectory: String? = ""
        /** Old live run must not overwrite an explicitly edited next-launch chat.*/
        @JvmField var codexBindingBlockedRun: String = ""
        @JvmField var codexConfirmActions: Boolean? = null // null preserves legacy approval mapping
        @JvmField var aiProjectOnly: Boolean = false
        @JvmField var aiAgent: String = ""
        @JvmField var aiConnection: String = "00000000-0000-4000-8000-000000000002"
        @JvmField var aiModel: String = ""
        @JvmField var aiVariant: String? = ""
        @JvmField var aiMode: String = "build"
        @JvmField var aiAccess: String = "ask"
        @JvmField var aiChat: String = "new"
        @JvmField var aiThread: String = "ses" + (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
        /** Empty preserves the pre-audit socket path until the next explicit launch.*/
        @JvmField var aiSocketId: String = ""
        /** Explicit user bookmark; current runtime thread is otherwise temporary.*/
        @JvmField var aiSavedThread: String = ""
        // Optional v8 fields: older readers retain a plain Ubuntu profile.
        @JvmField var claudeAgent: Boolean = false
        @JvmField var claudeEffort: String = "default"
        @JvmField var claudeModel: String? = ""
        @JvmField var claudeMode: String? = "manual"
        @JvmField var claudeChat: String = "new"
        @JvmField var claudeThread: String = UUID.randomUUID().toString()
        @JvmField var claudeSocketId: String = UUID.randomUUID().toString()
        @JvmField @Transient var agentSetupAllowed: Boolean = false
        @JvmField var geminiAgent: Boolean = false
        @JvmField var extendedDebug: Boolean = false
        @JvmField var agentConnectionCommand: String = ""
        @JvmField var keepHistory: Boolean = false
        @JvmField var geminiThinking: String = "default"
        @JvmField var geminiEncryptedKey: String = "" // Device/profile-bound; copies and imports require a new key.
        @JvmField var geminiCloudProject: String? = ""
        @JvmField var geminiCloudLocation: String? = "global"
        @JvmField var geminiAuth: String = "api"
        @JvmField var geminiModel: String = ""
        @JvmField var geminiMode: String = "default"
        @JvmField var geminiChat: String = "new"
        @JvmField var geminiThread: String = UUID.randomUUID().toString()
        @JvmField var geminiSocketId: String = UUID.randomUUID().toString()
        @JvmField var updated: Long = 0L
        @JvmField var saved: Boolean = false

        fun startupCommands(): MutableList<String> {
            val result: MutableList<String> = ArrayList<String>()
            for (command in arrayOf<String>(startup, startup2, startup3)) {
                if (!(command as java.lang.String).trim().isEmpty()) {
                    result.add(command)
                }
            }
            return result
        }

        fun copy(): Session {
            val s = Session()
            s.restoreReview = restoreReview
            s.keepHistory = keepHistory
            s.chatScale = chatScale
            s.extendedDebug = extendedDebug
            s.agentConnectionCommand = agentConnectionCommand
            s.name = name + WorkspaceText.tr(" — копия")
            s.directory = directory
            s.startup = startup
            s.startup2 = startup2
            s.startup3 = startup3
            s.environment = environment
            s.prootAlias = prootAlias
            s.prootIsolated = prootIsolated
            s.sshHost = sshHost
            s.sshUser = sshUser
            s.sshPort = sshPort
            s.sshKeepOpen = sshKeepOpen
            s.sshAuth = sshAuth
            s.sshIdentity = sshIdentity // Password is bound to the original profile ID.
            s.codexConfirmActions = codexConfirmActions
            s.aiProjectOnly = aiProjectOnly
            s.codexEnvironment = codexEnvironment
            s.codexAccess = codexAccess
            s.codexChat = codexChat
            s.codexThread = codexThread
            s.codexThreadName = codexThreadName
            s.codexSourceDirectory = codexSourceDirectory
            s.aiAgent = aiAgent
            s.aiConnection = aiConnection
            s.aiModel = aiModel
            s.aiVariant = aiVariant
            s.aiMode = aiMode
            s.aiAccess = aiAccess
            s.claudeEffort = claudeEffort
            s.claudeAgent = claudeAgent
            s.claudeModel = claudeModel
            s.claudeMode = claudeMode
            s.geminiCloudProject = geminiCloudProject
            s.geminiCloudLocation = geminiCloudLocation
            s.geminiThinking = geminiThinking
            s.geminiAgent = geminiAgent
            s.geminiAuth = geminiAuth
            s.geminiModel = geminiModel
            s.geminiMode = geminiMode
            // A duplicate starts with its own independent history.
            return s
        }
    }

    class Note {
        @JvmField var id: String = UUID.randomUUID().toString()
        @JvmField var name: String = ""
        @JvmField var text: String = ""
        @JvmField var category: String? = WorkspaceText.tr("Общее")
        @JvmField var favorite: Boolean = false
    }

    fun interface ErrorListener {
        fun onError(message: String?)
    }

    class RecentCommand {
        @JvmField val command: String
        @JvmField val sentAt: Long

        constructor(command: String, sentAt: Long) {
            this.command = command
            this.sentAt = sentAt
        }
    }

    @JvmField val commands: MutableList<Command> = ArrayList<Command>()
    @JvmField val aliases: MutableList<Alias> = ArrayList<Alias>()
    @JvmField val notes: MutableList<Note> = ArrayList<Note>()
    @JvmField val sessions: MutableList<Session?> = ArrayList<Session?>()
    @JvmField val recentCommands: MutableList<RecentCommand> = ArrayList<RecentCommand>()
    private val persistence: WorkspacePersistence
    private val main: Handler = Handler(Looper.getMainLooper())
    private val errors: ErrorListener
    private var writable: Boolean = true
    @Volatile private var closed: Boolean = false

    constructor(context: Context, errors: ErrorListener) : this(context, errors, AtomicFile(File(context.getFilesDir(), "workspace-v1.json")))

    constructor(context: Context, errors: ErrorListener, storage: AtomicFile) {
        this.errors = errors
        persistence = WorkspacePersistence(storage, Runnable {
            main.post(Runnable {
                if (!closed) {
                    errors.onError(WorkspaceText.tr("Ошибка записи библиотеки. Проверьте свободное место."))
                }
            })
        })
        try {
            val data: ByteArray? = persistence.read()
            if (data != null) {
                decode(String(data, StandardCharsets.UTF_8))
            }
        } catch (e: IOException) {
            // Keep the original file; never overwrite an unreadable user's library.
            writable = false
            main.post(Runnable {
                if (!closed) {
                    errors.onError(WorkspaceText.tr("Не удалось прочитать библиотеку. Исходный файл сохранён."))
                }
            })
        } catch (e: JSONException) {
            writable = false
            main.post(Runnable {
                if (!closed) {
                    errors.onError(WorkspaceText.tr("Не удалось прочитать библиотеку. Исходный файл сохранён."))
                }
            })
        }
    }

    fun isWritable(): Boolean {
        return writable && !closed
    }

    fun recordCommand(command: String) {
        if (!isWritable() || (command as java.lang.String).trim().isEmpty()) {
            return
        }
        recentCommands.add(0, RecentCommand(command, System.currentTimeMillis()))
        while (recentCommands.size > MAX_RECENT_COMMANDS) {
            recentCommands.removeAt(recentCommands.size - 1)
        }
        save()
    }

    fun clearRecentCommands() {
        if (!isWritable()) {
            return
        }
        recentCommands.clear()
        save()
    }

    fun findByHandle(handle: String): Session? {
        for (s in sessions) {
            if (!handle.isEmpty() && handle.equals(s!!.handle)) {
                return s
            }
        }
        return null
    }

    fun applyGeminiMode(profile: Session, run: String, before: String, mode: String): Boolean {
        if (!isWritable() || !sessions.contains(profile) || !profile.geminiAgent || !profile.geminiSocketId.equals(run) || !profile.geminiMode.equals(before) || !Arrays.asList("default", "auto_edit", "plan").contains(mode)) {
            return false
        }
        profile.geminiMode = mode
        profile.updated = System.currentTimeMillis()
        save()
        return true
    }

    fun save() {
        save(null)
    }

    fun save(completed: Runnable?) {
        if (closed) {
            return
        }
        if (!writable) {
            errors.onError(WorkspaceText.tr("Библиотека недоступна для записи; исходные данные сохранены."))
            return
        }
        var snapshot: ByteArray
        try {
            snapshot = (encode().toString() as java.lang.String).getBytes(StandardCharsets.UTF_8)
        } catch (e: JSONException) {
            errors.onError(WorkspaceText.tr("Не удалось сохранить библиотеку."))
            return
        }
        persistence.write(snapshot, Runnable {
            if (completed != null) {
                main.post(Runnable {
                    if (!closed) {
                        completed.run()
                    }
                })
            }
        })
    }

    @Throws(Exception::class)
    fun awaitWrites() {
        persistence.awaitWrites()
    }

    fun close() {
        closed = true
        persistence.close()
    } // Queued atomic writes finish after Activity destruction.

    @Throws(JSONException::class)
    fun encode(): JSONObject {
        val root: JSONObject = JSONObject().put("version", 8)
        val cs = JSONArray()
        val ss = JSONArray()
        val commandIds: MutableSet<String> = HashSet<String>()
        val sessionIds: MutableSet<String> = HashSet<String>()
        val noteIds: MutableSet<String> = HashSet<String>()
        for (c in commands) {
            uniqueId(c.id, commandIds)
            cs.put(JSONObject().put("id", c.id).put("name", c.name).put("command", c.command).put("category", c.category).put("directory", c.directory).put("favorite", c.favorite))
        }
        for (s in sessions) {
            uniqueId(s!!.id, sessionIds)
            ss.put(sessionJson(s))
        }
        val ns = JSONArray()
        for (n in notes) {
            uniqueId(n.id, noteIds)
            ns.put(JSONObject().put("id", n.id).put("name", n.name).put("text", n.text).put("category", n.category).put("favorite", n.favorite))
        }
        val recent = JSONArray()
        var i = 0
        while (i < Math.min(recentCommands.size, MAX_RECENT_COMMANDS)) {
            val entry: RecentCommand = recentCommands.get(i)
            recent.put(JSONObject().put("command", entry.command).put("sentAt", entry.sentAt))
            i++
        }
        val als = JSONArray()
        val aliasIds: MutableSet<String> = HashSet<String>()
        val aliasNames: MutableSet<String> = HashSet<String>()
        for (a in aliases) {
            uniqueId(a.id, aliasIds)
            if (!WorkspaceAliases.valid(a) || !aliasNames.add(a.environment + ":" + a.name)) {
                throw JSONException("Invalid alias")
            }
            als.put(JSONObject().put("id", a.id).put("name", a.name).put("command", a.command).put("environment", a.environment).put("category", a.category).put("favorite", a.favorite))
        }
        return root.put("aliases", als).put("commands", cs).put("sessions", ss).put("notes", ns).put("recentCommands", recent)
    }

    @Throws(JSONException::class)
    fun decode(json: String) {
        val root = JSONObject(json)
        val version: Int = root.getInt("version")
        if (version != 1 && version != 2 && version != 3 && version != 4 && version != 5 && version != 6 && version != 7 && version != 8) {
            throw JSONException("Unsupported workspace version")
        }
        val cs: MutableList<Command> = ArrayList<Command>()
        val ss: MutableList<Session?> = ArrayList<Session?>()
        val commandIds: MutableSet<String> = HashSet<String>()
        val sessionIds: MutableSet<String> = HashSet<String>()
        val noteIds: MutableSet<String> = HashSet<String>()
        val ca: JSONArray = root.getJSONArray("commands")
        val sa: JSONArray = root.getJSONArray("sessions")
        var i = 0
        while (i < ca.length()) {
            val o: JSONObject = ca.getJSONObject(i)
            val c = Command()
            c.id = uniqueId(o.getString("id"), commandIds)
            c.name = o.getString("name")
            c.command = o.getString("command")
            c.category = o.optString("category", WorkspaceText.tr("Общее"))
            c.directory = o.optString("directory")
            c.favorite = o.optBoolean("favorite")
            cs.add(c)
            i++
        }
        run {
            var i = 0
            while (i < sa.length()) {
                val o: JSONObject = sa.getJSONObject(i)
                // Retired local-AI profiles must never be reinterpreted as shell sessions.
                if (o.optBoolean("localAi", false)) {
                    i++
                    continue
                }
                val s = Session()
                if (o.has("chatScale") && (!(o.get("chatScale") is Number) || o.getDouble("chatScale") != o.getInt("chatScale").toDouble() || o.getInt("chatScale") < 50 || o.getInt("chatScale") > 150)) {
                    throw JSONException("Invalid chat scale")
                }
                s.chatScale = o.optInt("chatScale", 100)
                if (o.has("agentConnectionCommand") && !(o.get("agentConnectionCommand") is String)) {
                    throw JSONException("Invalid agent connection command type")
                }
                s.agentConnectionCommand = o.optString("agentConnectionCommand", "")
                if (!AgentConnection.valid(s.agentConnectionCommand)) {
                    throw JSONException("Invalid agent connection executable")
                }
                s.id = uniqueId(o.getString("id"), sessionIds)
                s.name = o.getString("name")
                s.directory = o.optString("directory")
                s.startup = o.optString("startup")
                s.startup2 = o.optString("startup2")
                s.startup3 = o.optString("startup3")
                s.environment = o.optString("environment", "legacy")
                if (!s.environment.equals("legacy") && !s.environment.equals("termux") && !s.environment.equals("ubuntu") && !s.environment.equals("ssh") && !s.environment.equals("codex")) {
                    throw JSONException("Unknown environment")
                }
                s.handle = o.optString("handle")
                if (o.has("prootAlias") && !(o.get("prootAlias") is String)) {
                    throw JSONException("Invalid environment alias")
                }
                s.prootAlias = o.optString("prootAlias", "")
                if (o.has("prootIsolated") && !(o.get("prootIsolated") is Boolean)) {
                    throw JSONException("Invalid environment isolation")
                }
                s.prootIsolated = o.optBoolean("prootIsolated")
                if (s.prootIsolated && s.prootAlias.isEmpty()) {
                    throw JSONException("Missing isolated environment alias")
                }
                if (!s.prootAlias.isEmpty()) {
                    if (!s.environment.equals("termux") || !WorkspaceLocalFilePicker.validAlias(s.prootAlias) || o.optBoolean("geminiAgent") || o.optBoolean("claudeAgent") || !o.optString("aiAgent").isEmpty()) {
                        throw JSONException("Invalid environment binding")
                    }
                    val commands: JSONArray? = o.optJSONArray("prootStartup")
                    if (commands == null || commands.length() != 3) {
                        throw JSONException("Invalid environment startup")
                    }
                    for (k in 0 until (3)) {
                        if (!(commands.get(k) is String)) {
                            throw JSONException("Invalid environment command")
                        }
                    }
                    s.startup = commands.getString(0)
                    s.startup2 = commands.getString(1)
                    s.startup3 = commands.getString(2)
                }

                s.sshHost = o.optString("sshHost", "")
                s.sshUser = o.optString("sshUser", "root")
                s.sshAuth = o.optString("sshAuth", "auto")
                s.sshIdentity = o.optString("sshIdentity", "")
                s.sshEncryptedPassword = o.optString("sshEncryptedPassword", "")
                if (!s.sshAuth.equals("auto") && !s.sshAuth.equals("key") && !s.sshAuth.equals("password")) {
                    throw JSONException("Unknown SSH authentication")
                }
                if (s.environment.equals("ssh") && s.sshAuth.equals("key") && !SshSessionShell.validIdentity(s.sshIdentity)) {
                    throw JSONException("Invalid SSH identity")
                }
                s.sshPort = o.optInt("sshPort", 22)
                s.sshKeepOpen = o.optBoolean("sshKeepOpen", true)
                if (s.environment.equals("ssh") && (version < 6 || !SshSessionShell.validHost(s.sshHost) || !SshSessionShell.validUser(s.sshUser) || !SshSessionShell.validPort(s.sshPort))) {
                    throw JSONException("Invalid SSH settings")
                }
                if (o.has("codexConfirmActions") && !o.isNull("codexConfirmActions") && !(o.get("codexConfirmActions") is Boolean)) {
                    throw JSONException("Invalid Codex approval policy")
                }
                if (o.has("aiProjectOnly") && !(o.get("aiProjectOnly") is Boolean)) {
                    throw JSONException("Invalid agent project scope")
                }
                s.codexConfirmActions = if (o.has("codexConfirmActions") && !o.isNull("codexConfirmActions")) o.getBoolean("codexConfirmActions") else null
                s.aiProjectOnly = o.optBoolean("aiProjectOnly", false)
                s.codexEnvironment = o.optString("codexEnvironment", "ubuntu")
                if (!AgentEnvironments.ubuntu(s.codexEnvironment) && !s.codexEnvironment.equals("termux")) {
                    throw JSONException("Invalid agent environment")
                }
                s.codexAccess = o.optString("codexAccess", "full")
                s.codexBindingBlockedRun = o.optString("codexBindingBlockedRun", "")
                s.codexChat = o.optString("codexChat", "new")
                s.codexThread = o.optString("codexThread", "")
                s.codexThreadName = o.optString("codexThreadName", "")
                s.codexSocketId = validId(o.optString("codexSocketId", s.codexSocketId))
                if (s.environment.equals("codex") && (version < 8 || !(AgentEnvironments.ubuntu(s.codexEnvironment) || s.codexEnvironment.equals("termux")) || !(s.codexAccess.equals("full") || s.codexAccess.equals("project") || s.codexAccess.equals("read")) || !(s.codexChat.equals("new") || s.codexChat.equals("last") || s.codexChat.equals("choose") || s.codexChat.equals("id") && CodexSessionShell.validThread(s.codexThread)))) {
                    throw JSONException("Invalid Codex profile")
                }
                s.codexSourceDirectory = o.optString("codexSourceDirectory", "")
                if (!s.codexSourceDirectory!!.isEmpty() && (!s.codexSourceDirectory!!.startsWith("/") || CodexProjects.directory(s.codexEnvironment, s.codexSourceDirectory).isEmpty())) {
                    throw JSONException("Invalid confirmed chat directory")
                }
                if (o.has("keepHistory") && !(o.get("keepHistory") is Boolean)) {
                    throw JSONException("Invalid history flag")
                }
                s.keepHistory = o.optBoolean("keepHistory", false)
                s.aiAgent = o.optString("aiAgent", "")
                s.aiConnection = validId(o.optString("aiConnection", s.aiConnection))
                s.aiModel = o.optString("aiModel", "")
                s.aiVariant = o.optString("aiVariant", "")
                s.aiMode = o.optString("aiMode", "build")
                s.aiAccess = o.optString("aiAccess", "ask")
                s.aiChat = o.optString("aiChat", "new")
                s.aiThread = o.optString("aiThread", s.aiThread)
                if (o.has("aiSocketId") && !(o.get("aiSocketId") is String)) {
                    throw JSONException("Invalid AI run")
                }
                s.aiSocketId = o.optString("aiSocketId", "")
                if (!s.aiSocketId.isEmpty()) {
                    s.aiSocketId = validId(s.aiSocketId)
                }
                s.aiSavedThread = o.optString("aiSavedThread", "")
                if (!s.aiSavedThread.isEmpty() && !AiAgents.validThread(s.aiSavedThread)) {
                    throw JSONException("Invalid saved AI thread")
                }
                if (!s.aiAgent.isEmpty() && (!s.aiAgent.equals("opencode") || !s.environment.equals("ubuntu") || !AgentEnvironments.ubuntu(s.codexEnvironment) || !AiAgents.validThread(s.aiThread) || !AiAgents.validModel(s.aiModel) || !(s.aiVariant!! as java.lang.String).matches("[A-Za-z0-9_.-]{0,80}") || !(s.aiAccess.equals("full") || s.aiAccess.equals("ask") || s.aiAccess.equals("read")) || !(s.aiMode.equals("build") || s.aiMode.equals("plan")) || !(s.aiChat.equals("new") || s.aiChat.equals("resume")))) {
                    throw JSONException("Invalid AI profile")
                }
                s.claudeEffort = o.optString("claudeEffort", "default")
                if (!Arrays.asList("default", "low", "medium", "high", "xhigh", "max").contains(s.claudeEffort)) {
                    s.claudeEffort = "default"
                }
                s.claudeAgent = o.optBoolean("claudeAgent", false)
                s.claudeModel = o.optString("claudeModel", "")
                s.claudeMode = o.optString("claudeMode", "manual")
                s.claudeChat = o.optString("claudeChat", "new")
                s.claudeThread = o.optString("claudeThread", s.claudeThread)
                s.claudeSocketId = o.optString("claudeSocketId", s.claudeSocketId)
                if (s.claudeAgent && !ClaudeCode.valid(s)) {
                    throw JSONException("Invalid Claude profile")
                }
                if (o.has("geminiEncryptedKey") && !(o.get("geminiEncryptedKey") is String)) {
                    throw JSONException("Invalid Gemini key record")
                }
                s.geminiEncryptedKey = o.optString("geminiEncryptedKey", "")
                if (!GeminiApiKeyVault.validRecord(s.geminiEncryptedKey)) {
                    throw JSONException("Invalid Gemini key record")
                }
                s.geminiAuth = o.optString("geminiAuth", "auto")
                s.geminiCloudProject = o.optString("geminiCloudProject", "")
                s.geminiCloudLocation = o.optString("geminiCloudLocation", "global")
                s.geminiThinking = o.optString("geminiThinking", "default")
                if (!Arrays.asList("default", "minimal", "low", "medium", "high").contains(s.geminiThinking)) {
                    s.geminiThinking = "default"
                }
                s.geminiAgent = o.optBoolean("geminiAgent", false)
                s.geminiModel = o.optString("geminiModel", "")
                s.geminiMode = o.optString("geminiMode", "default")
                s.geminiChat = o.optString("geminiChat", "new")
                s.geminiThread = o.optString("geminiThread", s.geminiThread)
                s.geminiSocketId = o.optString("geminiSocketId", s.geminiSocketId)
                if (s.geminiAgent && !GeminiCli.valid(s)) {
                    throw JSONException("Invalid Gemini profile")
                }
                s.restoreReview = o.optBoolean("restoreReview", false)
                s.extendedDebug = o.optBoolean("extendedDebug", false)
                s.updated = o.optLong("updated")
                s.saved = o.optBoolean("saved", true)
                ss.add(s)
                i++
            }
        }
        val ns: MutableList<Note> = ArrayList<Note>()
        val na: JSONArray? = root.optJSONArray("notes")
        if (version >= 5 && na == null) {
            throw JSONException("Missing notes")
        }
        if (na != null) {
            var i = 0
            while (i < na.length()) {
                val o: JSONObject = na.getJSONObject(i)
                val n = Note()
                n.id = uniqueId(o.getString("id"), noteIds)
                n.name = o.getString("name")
                n.text = o.getString("text")
                n.category = o.optString("category", WorkspaceText.tr("Общее"))
                n.favorite = o.optBoolean("favorite")
                ns.add(n)
                i++
            }
        }
        val recent: MutableList<RecentCommand> = ArrayList<RecentCommand>()
        val history: JSONArray? = root.optJSONArray("recentCommands")
        if (history != null) {
            var i = 0
            while (i < Math.min(history.length(), MAX_RECENT_COMMANDS)) {
                val item: Any = history.get(i)
                if (item is String) {
                    recent.add(RecentCommand(item as String, 0L))
                } else {
                    val entry: JSONObject = history.getJSONObject(i)
                    recent.add(RecentCommand(entry.getString("command"), entry.optLong("sentAt")))
                }
                i++
            }
        }
        val als: MutableList<Alias> = ArrayList<Alias>()
        val aa: JSONArray? = root.optJSONArray("aliases")
        val aliasIds: MutableSet<String> = HashSet<String>()
        val aliasNames: MutableSet<String> = HashSet<String>()
        if (root.has("aliases") && aa == null) {
            throw JSONException("Invalid aliases")
        }
        if (aa != null) {
            var i = 0
            while (i < aa.length()) {
                val o: JSONObject = aa.getJSONObject(i)
                val a = Alias()
                a.id = uniqueId(o.getString("id"), aliasIds)
                a.name = o.getString("name")
                a.command = o.getString("command")
                a.environment = o.getString("environment")
                a.category = o.optString("category")
                a.favorite = o.optBoolean("favorite")
                if (!WorkspaceAliases.valid(a) || !aliasNames.add(a.environment + ":" + a.name)) {
                    throw JSONException("Invalid alias")
                }
                als.add(a)
                i++
            }
        }
        aliases.clear()
        aliases.addAll(als)
        commands.clear()
        commands.addAll(cs)
        sessions.clear()
        sessions.addAll(ss)
        notes.clear()
        notes.addAll(ns)
        recentCommands.clear()
        recentCommands.addAll(recent)
    }

    companion object {
        const val BASE_TERMINAL_ID: String = "00000000-0000-4000-8000-000000000001"

        @JvmStatic
        fun isBase(session: Session?): Boolean {
            return session != null && BASE_TERMINAL_ID.equals(session.id)
        }

        const val MAX_RECENT_COMMANDS: Int = 50

        @Throws(JSONException::class)
        @JvmStatic
        fun sessionJson(s: Session?): JSONObject {
            if (s!!.chatScale < 50 || s.chatScale > 150) {
                throw JSONException("Invalid chat scale")
            }
            return JSONObject().put("id", s.id).put("name", s.name).put("chatScale", s.chatScale).put("directory", s.directory).put("prootAlias", s.prootAlias).put("prootIsolated", s.prootIsolated).put("prootStartup", if (s.prootAlias.isEmpty()) JSONObject.NULL else JSONArray(Arrays.asList(s.startup, s.startup2, s.startup3))).put("startup", if (s.prootAlias.isEmpty()) s.startup else WorkspaceEnvironments.OLD_READER_GUARD).put("startup2", if (s.prootAlias.isEmpty()) s.startup2 else "").put("startup3", if (s.prootAlias.isEmpty()) s.startup3 else "").put("sshAuth", s.sshAuth).put("sshIdentity", s.sshIdentity).put("sshEncryptedPassword", s.sshEncryptedPassword).put("sshHost", s.sshHost).put("sshUser", s.sshUser).put("sshPort", s.sshPort).put("sshKeepOpen", s.sshKeepOpen).put("environment", s.environment).put("handle", s.handle).put("codexConfirmActions", if (s.codexConfirmActions == null) JSONObject.NULL else s.codexConfirmActions).put("aiProjectOnly", s.aiProjectOnly).put("codexEnvironment", s.codexEnvironment).put("codexAccess", s.codexAccess).put("codexChat", s.codexChat).put("codexThread", s.codexThread).put("codexThreadName", s.codexThreadName).put("codexSocketId", s.codexSocketId).put("codexSourceDirectory", s.codexSourceDirectory).put("codexBindingBlockedRun", s.codexBindingBlockedRun).put("aiAgent", s.aiAgent).put("aiConnection", s.aiConnection).put("aiSocketId", s.aiSocketId).put("aiModel", s.aiModel).put("aiVariant", s.aiVariant).put("aiMode", s.aiMode).put("aiAccess", s.aiAccess).put("aiChat", s.aiChat).put("aiThread", s.aiThread).put("aiSavedThread", s.aiSavedThread).put("claudeEffort", s.claudeEffort).put("claudeAgent", s.claudeAgent).put("claudeModel", s.claudeModel).put("claudeMode", s.claudeMode).put("claudeChat", s.claudeChat).put("claudeThread", s.claudeThread).put("claudeSocketId", s.claudeSocketId).put("keepHistory", s.keepHistory).put("geminiEncryptedKey", s.geminiEncryptedKey).put("geminiCloudProject", s.geminiCloudProject).put("geminiCloudLocation", s.geminiCloudLocation).put("geminiThinking", s.geminiThinking).put("geminiAuth", s.geminiAuth).put("geminiAgent", s.geminiAgent).put("geminiModel", s.geminiModel).put("geminiMode", s.geminiMode).put("geminiChat", s.geminiChat).put("geminiThread", s.geminiThread).put("geminiSocketId", s.geminiSocketId).put("agentConnectionCommand", s.agentConnectionCommand).put("restoreReview", s.restoreReview).put("extendedDebug", s.extendedDebug).put("updated", s.updated).put("saved", s.saved)
        }

        @Throws(JSONException::class)
        @JvmStatic
        private fun validId(id: String?): String {
            try {
                return UUID.fromString(id).toString()
            } catch (e: IllegalArgumentException) {
                throw JSONException("Invalid id")
            }
        }

        @Throws(JSONException::class)
        @JvmStatic
        private fun uniqueId(id: String?, ids: MutableSet<String>): String {
            val normalized: String = validId(id)
            if (!ids.add(normalized)) {
                throw JSONException("Duplicate workspace id")
            }
            return normalized
        }

        @JvmStatic
        fun tail(text: String, max: Int): String {
            if (text.length <= max) {
                return text
            }
            var start: Int = text.length - max
            if (Character.isLowSurrogate(text[start])) {
                start++
            }
            return text.substring(start)
        }
    }
}
