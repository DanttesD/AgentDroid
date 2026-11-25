package com.termux.app.workspace

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Separate versioned store; never migrates or rewrites existing SSH sessions/library.*/
class ServerProfiles {
    class Profile {
        @JvmField var id: String = UUID.randomUUID().toString()
        @JvmField var name: String = ""
        @JvmField var host: String = ""
        @JvmField var user: String = ""
        @JvmField var identity: String = ""
        @JvmField var auth: String = "auto"
        @JvmField var encrypted: String = ""
        @JvmField var port: Int = 22
        @JvmField var automatic: Boolean = true
        @JvmField @Volatile var diagnosticsPaused: Boolean = false
        @JvmField var backgroundPolling: Boolean = false
        @JvmField val projects: MutableList<Project> = ArrayList<Project>()
        @JvmField val favorites: MutableList<String> = ArrayList<String>()
        @JvmField val favoriteFiles: MutableList<String> = ArrayList<String>()
        @JvmField val actions: MutableList<Action> = ArrayList<Action>()

        fun session(): WorkspaceStore.Session {
            val s = WorkspaceStore.Session()
            s.id = id
            s.name = name
            s.environment = "ssh"
            s.sshHost = host
            s.sshUser = user
            s.sshPort = port
            s.sshAuth = auth
            s.sshIdentity = identity
            s.sshEncryptedPassword = encrypted
            s.directory = "~"
            return s
        }

        @Throws(JSONException::class)
        fun json(): JSONObject {
            if (favorites.size > 100 || favoriteFiles.size > 100 || actions.size > 50) {
                throw JSONException("STORE_LIMIT")
            }
            for (path in favorites) {
                if (!path.startsWith("/") || path.length > 4096 || path.indexOf('\u0000') >= 0) {
                    throw JSONException("INVALID_FAVORITE")
                }
            }
            for (path in favoriteFiles) {
                if (!path.startsWith("/") || path.length > 4096 || path.indexOf('\u0000') >= 0) {
                    throw JSONException("INVALID_FAVORITE")
                }
            }
            for (action in actions) {
                if ((action.name as java.lang.String).trim().isEmpty() || action.name.length > 160 || action.path.isEmpty() || action.path.length > 4096 || action.path.indexOf('\u0000') >= 0 || (action.command as java.lang.String).trim().isEmpty() || action.command.length > 8192 || action.command.indexOf('\u0000') >= 0) {
                    throw JSONException("INVALID_ACTION")
                }
            }
            val files = JSONArray()
            for (path in favoriteFiles) {
                files.put(path)
            }
            val stars = JSONArray()
            for (path in favorites) {
                stars.put(path)
            }
            val acts = JSONArray()
            for (action in actions) {
                acts.put(JSONObject().put("name", action.name).put("path", action.path).put("command", action.command))
            }
            val a = JSONArray()
            for (p in projects) {
                a.put(JSONObject().put("name", p.name).put("path", p.path))
            }
            return JSONObject().put("id", id).put("name", name).put("host", host).put("user", user).put("port", port).put("identity", identity).put("auth", auth).put("encrypted", encrypted).put("automatic", automatic).put("diagnosticsPaused", diagnosticsPaused).put("backgroundPolling", backgroundPolling).put("projects", a).put("favorites", stars).put("favoriteFiles", files).put("actions", acts)
        }
    }

    class Action {
        @JvmField val name: String
        @JvmField val path: String
        @JvmField val command: String

        constructor(n: String, p: String, c: String) {
            name = n
            path = p
            command = c
        }
    }

    class Project {
        @JvmField val name: String
        @JvmField val path: String

        constructor(n: String, p: String) {
            name = n
            path = p
        }
    }

    @JvmField val profiles: MutableList<Profile> = ArrayList<Profile>()
    private val file: AtomicFile
    @JvmField var writable: Boolean = true

    constructor(c: Context) {
        file = AtomicFile(File(c.getFilesDir(), "workspace-servers-v1.json"))
        load()
    }

    private fun load() {
        try {
            (file.openRead()).use { `in` ->
                val out = ByteArrayOutputStream()
                val b = ByteArray(8192)
                var n: Int = 0
                while (true) {
                    n = `in`.read(b)
                    if (n == -1) break
                    if (out.size() + n > 1024 * 1024) {
                        throw IOException("STORE_LIMIT")
                    }
                    out.write(b, 0, n)
                }
                val root = JSONObject(out.toString("UTF-8"))
                if (root.getInt("version") != 1) {
                    throw IOException("STORE_VERSION")
                }
                val items: JSONArray = root.getJSONArray("servers")
                val ids: MutableSet<String> = HashSet<String>()
                if (items.length() > 30) {
                    throw IOException("STORE_LIMIT")
                }
                var i = 0
                while (i < items.length()) {
                    val o: JSONObject = items.getJSONObject(i)
                    val p = Profile()
                    p.id = o.getString("id")
                    p.name = o.getString("name")
                    p.host = o.getString("host")
                    p.user = o.getString("user")
                    p.port = o.getInt("port")
                    p.identity = o.optString("identity")
                    p.auth = o.optString("auth", "auto")
                    p.encrypted = o.optString("encrypted")
                    p.automatic = o.optBoolean("automatic", true)
                    p.diagnosticsPaused = o.optBoolean("diagnosticsPaused", false)
                    p.backgroundPolling = o.optBoolean("backgroundPolling", false)
                    if (!valid(p) || !ids.add(p.id)) {
                        throw IOException("INVALID_PROFILE")
                    }
                    val ps: JSONArray? = o.optJSONArray("projects")
                    if (ps != null) {
                        var j = 0
                        while (j < Math.min(ps.length(), 100)) {
                            val v: JSONObject = ps.getJSONObject(j)
                            p.projects.add(Project(v.getString("name"), v.getString("path")))
                            j++
                        }
                    }
                    val favorites: JSONArray? = o.optJSONArray("favorites")
                    if (favorites != null) {
                        var j = 0
                        while (j < Math.min(favorites.length(), 100)) {
                            val path: String = favorites.getString(j)
                            if (path.startsWith("/") && path.length <= 4096 && path.indexOf('\u0000') < 0 && !p.favorites.contains(path)) {
                                p.favorites.add(path)
                            }
                            j++
                        }
                    }
                    val favoriteFiles: JSONArray? = o.optJSONArray("favoriteFiles")
                    if (favoriteFiles != null) {
                        var j = 0
                        while (j < Math.min(favoriteFiles.length(), 100)) {
                            val path: String = favoriteFiles.getString(j)
                            if (path.startsWith("/") && path.length <= 4096 && path.indexOf('\u0000') < 0 && !p.favoriteFiles.contains(path)) {
                                p.favoriteFiles.add(path)
                            }
                            j++
                        }
                    }
                    val actions: JSONArray? = o.optJSONArray("actions")
                    if (actions != null) {
                        var j = 0
                        while (j < Math.min(actions.length(), 50)) {
                            val action: JSONObject = actions.getJSONObject(j)
                            val actionName: String = action.getString("name")
                            val path: String = action.getString("path")
                            val cmd: String = action.getString("command")
                            if (!(actionName as java.lang.String).trim().isEmpty() && actionName.length <= 160 && !path.isEmpty() && path.length <= 4096 && path.indexOf('\u0000') < 0 && !(cmd as java.lang.String).trim().isEmpty() && cmd.length <= 8192 && cmd.indexOf('\u0000') < 0) {
                                p.actions.add(Action(actionName, path, cmd))
                            }
                            j++
                        }
                    }
                    profiles.add(p)
                    i++
                }
            }
        } catch (ignored: FileNotFoundException) {
        } catch (e: Exception) {
            profiles.clear()
            writable = false
        }
    }

    @Throws(Exception::class)
    @Synchronized
    fun save() {
        if (!writable) {
            throw IOException("STORE_READ_ONLY")
        }
        if (profiles.size > 30) {
            throw IOException("STORE_LIMIT")
        }
        val a = JSONArray()
        for (p in profiles) {
            if (!valid(p)) {
                throw IOException("INVALID_PROFILE")
            }
            a.put(p.json())
        }
        val data: ByteArray = (JSONObject().put("version", 1).put("servers", a).toString() as java.lang.String).getBytes(StandardCharsets.UTF_8)
        if (data.size > 1024 * 1024) {
            throw IOException("STORE_LIMIT")
        }
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out!!.write(data)
            file.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) {
                file.failWrite(out)
            }
            throw e
        }
    }

    companion object {
        /** Freeze connection settings for one explicit operation; do not copy editable UI collections.*/
        @JvmStatic
        fun connectionSnapshot(source: Profile?): Profile {
            val p = Profile()
            p.id = source!!.id
            p.name = source.name
            p.host = source.host
            p.port = source.port
            p.user = source.user
            p.auth = source.auth
            p.identity = source.identity
            p.encrypted = source.encrypted
            p.diagnosticsPaused = source.diagnosticsPaused
            return p
        }

        @JvmStatic
        fun valid(p: Profile?): Boolean {
            return (p!!.id as java.lang.String).matches("[a-f0-9-]{36}") && !(p.name as java.lang.String).trim().isEmpty() && p.name.length <= 100 && SshSessionShell.validHost(p.host) && SshSessionShell.validUser(p.user) && SshSessionShell.validPort(p.port) && Arrays.asList("auto", "key", "password").contains(p.auth) && (!p.auth.equals("key") || SshSessionShell.validIdentity(p.identity))
        }
    }
}
