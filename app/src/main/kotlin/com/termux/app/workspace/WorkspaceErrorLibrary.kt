package com.termux.app.workspace

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.text.Normalizer
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Offline, curated documentation. Never reads a terminal, runs a command or requests a URL.*/
class WorkspaceErrorLibrary {
    class Entry {
        @JvmField val data: JSONObject
        @JvmField val id: String
        @JvmField val category: String
        @JvmField val context: String
        @JvmField val command: String
        @JvmField val index: String
        @JvmField val environments: MutableSet<String> = HashSet<String>()

        @Throws(JSONException::class)
        constructor(item: JSONObject) {
            data = item
            id = item.getString("id")
            category = item.getString("category")
            context = item.getString("context")
            command = item.getString("command")
            if (!(id as java.lang.String).matches("[a-z0-9-]{1,80}") || !Arrays.asList(*CATEGORIES).contains(category) || !Arrays.asList(*CONTEXTS).contains(context) || command.length > 4096 || command.indexOf('\u0000') >= 0) {
                throw JSONException("INVALID_ENTRY")
            }
            val env: JSONArray = item.getJSONArray("environments")
            if (env.length() == 0) {
                throw JSONException("MISSING_ENVIRONMENT")
            }
            var i = 0
            while (i < env.length()) {
                val key: String = env.getString(i)
                if (key.equals("all") || !Arrays.asList(*ENV).contains(key)) {
                    throw JSONException("INVALID_ENVIRONMENT")
                }
                environments.add(key)
                i++
            }
            val searchable: StringBuilder = StringBuilder(id).append(' ')
            for (key in arrayOf<String>("title", "symptom", "check", "solution", "caution")) {
                for (lang in arrayOf<String>("ru", "en")) {
                    val value: String = item.getJSONObject(key).getString(lang)
                    if ((value as java.lang.String).trim().isEmpty() || value.length > 6000) {
                        throw JSONException("INVALID_TEXT")
                    }
                    searchable.append(value).append(' ')
                }
            }
            if (!(item.getString("reviewed") as java.lang.String).matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw JSONException("INVALID_DATE")
            }
            val sources: JSONArray = item.getJSONArray("sources")
            if (sources.length() == 0 || sources.length() > 8) {
                throw JSONException("INVALID_SOURCES")
            }
            run {
                var i = 0
                while (i < sources.length()) {
                    val source: JSONObject = sources.getJSONObject(i)
                    if (!safeUrl(source.getString("url")) || !Arrays.asList("official", "community", "project").contains(source.getString("kind"))) {
                        throw JSONException("INVALID_SOURCE")
                    }
                    i++
                }
            }
            var project = false
            run {
                var i = 0
                while (i < sources.length()) {
                    project = project or sources.getJSONObject(i).getString("kind").equals("project")
                    i++
                }
            }
            if (project || item.has("projectStatus") || item.has("projectEvidence")) {
                if (!Arrays.asList("resolved", "verified-code", "open", "guidance").contains(item.getString("projectStatus"))) {
                    throw JSONException("INVALID_PROJECT_STATUS")
                }
                for (lang in arrayOf<String>("ru", "en")) {
                    val evidence: String = item.getJSONObject("projectEvidence").getString(lang)
                    if ((evidence as java.lang.String).trim().isEmpty() || evidence.length > 6000) {
                        throw JSONException("INVALID_PROJECT_EVIDENCE")
                    }
                    searchable.append(evidence).append(' ')
                }
                searchable.append(" Termux Job's опыт разработки project experience ")
            }
            index = normalize(searchable.toString())
        }

        fun text(key: String, ru: Boolean): String {
            return data.optJSONObject(key).optString(if (ru) "ru" else "en")
        }

        fun matches(query: String, env: String, cat: String, mode: String): Boolean {
            if (!env.equals("all") && !environments.contains(env) || !cat.equals("all") && !category.equals(cat) || !mode.equals("all") && !context.equals("all") && !context.equals(mode)) {
                return false
            }
            for (token in ((normalize(query) as java.lang.String).trim() as java.lang.String).split("\\s+")) {
                if (!index.contains(token)) {
                    return false
                }
            }
            return true
        }
    }

    @JvmField val version: String
    @JvmField val entries: MutableList<Entry>

    private constructor(version: String, entries: MutableList<Entry>) {
        this.version = version
        this.entries = Collections.unmodifiableList(entries)
    }

    fun search(query: String, env: String, category: String, mode: String): MutableList<Entry> {
        val result: MutableList<Entry> = ArrayList<Entry>()
        for (e in entries) {
            if (e.matches(query, env, category, mode)) {
                result.add(e)
            }
        }
        return result
    }

    companion object {
        const val ASSET: String = "workspace-error-library-v1.json"
        @JvmField val ENV: Array<String> = arrayOf<String>("all", "termux", "ubuntu", "debian", "arch", "alpine", "fedora", "opensuse", "other")
        @JvmField val CATEGORIES: Array<String> = arrayOf<String>("all", "packages", "files", "launch", "network", "build", "git", "environment", "python", "node")
        @JvmField val CONTEXTS: Array<String> = arrayOf<String>("all", "proot", "native", "ssh")

        @JvmStatic
        fun safeUrl(value: String): Boolean {
            try {
                val uri = URI(value)
                return value.length <= 2048 && "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null && uri.getPort() == -1
            } catch (e: Exception) {
                return false
            }
        }

        @JvmStatic
        fun normalize(value: String): String {
            return ((Normalizer.normalize(value, Normalizer.Form.NFKC) as java.lang.String).toLowerCase(Locale.ROOT) as java.lang.String).replace('ё', 'е')
        }

        @Throws(IOException::class, JSONException::class)
        @JvmStatic
        fun load(context: Context): WorkspaceErrorLibrary {
            (context.getAssets().open(ASSET)).use { stream ->
                return parse(stream)
            }
        }

        @Throws(IOException::class, JSONException::class)
        @JvmStatic
        fun parse(stream: InputStream): WorkspaceErrorLibrary {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var n: Int = 0
            while (true) {
                n = stream.read(buffer)
                if (n == -1) break
                if (out.size() + n > 512 * 1024) {
                    throw IOException("CATALOG_TOO_LARGE")
                }
                out.write(buffer, 0, n)
            }
            val root = JSONObject(out.toString("UTF-8"))
            if (root.getInt("schema") != 1) {
                throw JSONException("UNSUPPORTED_SCHEMA")
            }
            val rows: JSONArray = root.getJSONArray("entries")
            if (rows.length() > 3000) {
                throw JSONException("TOO_MANY_ENTRIES")
            }
            val entries: MutableList<Entry> = ArrayList<Entry>()
            val ids: MutableSet<String> = HashSet<String>()
            var i = 0
            while (i < rows.length()) {
                val entry = Entry(rows.getJSONObject(i))
                if (!ids.add(entry.id)) {
                    throw JSONException("DUPLICATE_ID")
                }
                entries.add(entry)
                i++
            }
            return WorkspaceErrorLibrary(root.getString("version"), entries)
        }
    }
}
