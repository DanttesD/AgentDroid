package com.termux.app.workspace

import android.app.Activity
import java.io.IOException
import java.util.UUID
import java.util.function.Predicate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Explicit managed-library transfer. Never discovers or evaluates shell definitions.*/
object WorkspaceAliasImport {
    class Plan {
        @JvmField val before: MutableList<WorkspaceStore.Alias?> = ArrayList<WorkspaceStore.Alias?>()
        @JvmField val after: MutableList<WorkspaceStore.Alias> = ArrayList<WorkspaceStore.Alias>()

        fun replacements(): Int {
            var count = 0
            for (a in before) {
                if (a != null) {
                    count++
                }
            }
            return count
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun read(activity: Activity, library: JSONObject?): MutableList<WorkspaceStore.Alias> {
        if (library == null) {
            throw IOException("ALIAS_LIBRARY_MISSING")
        }
        val incoming = WorkspaceStore(activity, WorkspaceStore.ErrorListener { error ->  })
        try {
            incoming.decode(library.toString())
            val out: MutableList<WorkspaceStore.Alias> = ArrayList<WorkspaceStore.Alias>()
            for (alias in incoming.aliases) {
                out.add(alias.copy())
            }
            return out
        } finally {
            incoming.close()
        }
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun export(selected: MutableList<WorkspaceStore.Alias>): JSONObject {
        val aliases = JSONArray()
        val names: MutableSet<String> = HashSet<String>()
        val ids: MutableSet<String?> = HashSet<String?>()
        for (a in selected) {
            if (!WorkspaceAliases.valid(a) || !names.add(a.environment + ":" + a.name) || !ids.add(a.id)) {
                throw JSONException("ALIAS_INVALID_DEFINITION")
            }
            aliases.put(JSONObject().put("id", a.id).put("name", a.name).put("command", a.command).put("environment", a.environment).put("category", a.category).put("favorite", a.favorite))
        }
        return JSONObject().put("version", 8).put("commands", JSONArray()).put("notes", JSONArray()).put("sessions", JSONArray()).put("recentCommands", JSONArray()).put("aliases", aliases)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun plan(incoming: MutableList<WorkspaceStore.Alias>, selected: MutableSet<String?>, existing: MutableList<WorkspaceStore.Alias>, general: String?): Plan {
        val out = Plan()
        val found: MutableSet<String?> = HashSet<String?>()
        val keys: MutableSet<String> = HashSet<String>()
        val ids: MutableSet<String?> = HashSet<String?>()
        for (a in existing) {
            ids.add(a.id)
        }
        for (source in incoming) {
            if (!WorkspaceAliases.valid(source) || !keys.add(source.environment + ":" + source.name)) {
                throw IOException("ALIAS_INVALID_DEFINITION")
            }
            if (!selected.contains(source.id)) {
                continue
            }
            if (!found.add(source.id)) {
                throw IOException("ALIAS_INVALID_DEFINITION")
            }
            var previous: WorkspaceStore.Alias? = null
            for (a in existing) {
                if (a.environment.equals(source.environment) && a.name.equals(source.name)) {
                    previous = a.copy()
                    break
                }
            }
            val next: WorkspaceStore.Alias = source.copy()
            if (previous != null) {
                next.id = previous.id
            } else {
                do {
                    next.id = UUID.randomUUID().toString()
                } while (!ids.add(next.id))
            }
            if ((next.category!! as java.lang.String).trim().isEmpty()) {
                next.category = general
            }
            out.before.add(previous)
            out.after.add(next)
        }
        if (found.size != selected.size || found.isEmpty()) {
            throw IOException("ALIAS_INVALID_SELECTION")
        }
        return out
    }

    @Throws(IOException::class)
    @JvmStatic
    fun verify(plan: Plan, current: MutableList<WorkspaceStore.Alias>) {
        var i = 0
        while (i < plan.after.size) {
            val after: WorkspaceStore.Alias = plan.after.get(i)
            val before: WorkspaceStore.Alias? = plan.before.get(i)
            var found: WorkspaceStore.Alias? = null
            for (a in current) {
                if (a.environment.equals(after.environment) && a.name.equals(after.name) || a.id!!.equals(after.id)) {
                    found = a
                    break
                }
            }
            if (!same(before, found)) {
                throw IOException("ALIAS_LIBRARY_CHANGED")
            }
            i++
        }
    }

    @JvmStatic
    private fun same(a: WorkspaceStore.Alias?, b: WorkspaceStore.Alias?): Boolean {
        return if (a == null) b == null else b != null && a.id!!.equals(b.id) && a.name.equals(b.name) && a.command!!.equals(b.command) && a.environment.equals(b.environment) && a.category!!.equals(b.category) && a.favorite == b.favorite
    }

    @Throws(Exception::class)
    @JvmStatic
    fun install(plan: Plan, installer: WorkspaceAliases.Installer) {
        var done = 0
        try {
            while (done < plan.after.size) {
                WorkspaceAliases.change(plan.before.get(done), plan.after.get(done), null, installer)
                done++
            }
        } catch (failure: Exception) {
            var failed = false
            for (i in done - 1 downTo 0) {
                try {
                    WorkspaceAliases.change(plan.after.get(i), plan.before.get(i), null, installer)
                } catch (rollback: Exception) {
                    failed = true
                }
            }
            if (failed) {
                throw IOException("ALIAS_ROLLBACK_FAILED", failure)
            }
            throw failure
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun commit(plan: Plan, store: WorkspaceStore) {
        if (!store.isWritable()) {
            throw IOException("LIBRARY_READ_ONLY")
        }
        verify(plan, store.aliases)
        for (a in plan.after) {
            store.aliases.removeIf(Predicate<WorkspaceStore.Alias> { old -> old.id!!.equals(a.id) })
            store.aliases.add(a.copy())
        }
        store.save()
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun persisted(plan: Plan, saved: JSONObject): Boolean {
        val aliases: JSONArray? = saved.optJSONArray("aliases")
        if (aliases == null) {
            return false
        }
        for (a in plan.after) {
            var found = false
            var i = 0
            while (i < aliases.length()) {
                val b: JSONObject = aliases.getJSONObject(i)
                if (a.id!!.equals(b.optString("id"))) {
                    found = a.name.equals(b.optString("name")) && a.command!!.equals(b.optString("command")) && a.environment.equals(b.optString("environment")) && a.category!!.equals(b.optString("category")) && a.favorite == b.optBoolean("favorite")
                    break
                }
                i++
            }
            if (!found) {
                return false
            }
        }
        return true
    }
}
