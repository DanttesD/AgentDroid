package com.termux.app.workspace

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONArray
import org.json.JSONObject

/** Sanitized, local scalar snapshot from one owned Gemini run. Never reads its transcript/auth.*/
class GeminiLiveStatus {
    @JvmField var model: String = ""
    @JvmField var selectedModel: String = ""
    @JvmField var mode: String = ""
    @JvmField var auth: String = ""
    @JvmField var state: String = "starting"
    @JvmField var error: String = ""
    @JvmField var changeId: String = ""
    @JvmField var changeModel: String = ""
    @JvmField var changeState: String = ""
    @JvmField var available: Boolean = false
    @JvmField var canChangeMode: Boolean = false
    @JvmField var canPlan: Boolean = false
    @JvmField var canEdit: Boolean = false
    @JvmField var thinking: String = ""
    @JvmField var thinkingModel: String = ""
    @JvmField var canChangeThinking: Boolean = false
    @JvmField var thinkingEffective: Boolean = false
    @JvmField var thinkingOptions: MutableList<String> = ArrayList<String>()
    @JvmField var permissionId: String = ""
    @JvmField var modeChangeId: String = ""
    @JvmField var modeChangeMode: String = ""
    @JvmField var modeChangeState: String = ""
    @JvmField var updated: Long = 0L
    @JvmField var startedAt: Long = 0L
    @JvmField var requests: Long = 0L
    @JvmField var input: Long = 0L
    @JvmField var output: Long = 0L
    @JvmField var cache: Long = 0L
    @JvmField var thoughts: Long = 0L
    @JvmField var total: Long = 0L
    @JvmField var apiMs: Long = 0L

    companion object {
        @Throws(Exception::class)
        @JvmStatic
        fun parse(v: JSONObject, run: String, now: Long): GeminiLiveStatus {
            if (v.getInt("schema") != 1 || !run.equals(v.getString("run"))) {
                throw IOException("RUN_MISMATCH")
            }
            val s = GeminiLiveStatus()
            s.updated = v.getLong("updated")
            s.startedAt = v.getLong("startedAt")
            if (s.updated > now + 5000 || now - s.updated > 15000) {
                throw IOException("STALE_STATUS")
            }
            s.model = model(v.optString("model"))
            s.selectedModel = model(v.optString("selectedModel"))
            s.mode = one(v.optString("mode"), "default|autoEdit|auto_edit|plan|yolo")
            s.auth = one(v.optString("auth"), "gemini-api-key|oauth-personal|vertex-ai|gateway|compute-default-credentials")
            s.state = one(v.optString("state"), "starting|idle|responding|waiting_for_confirmation|unsupported|ended")
            s.available = v.optBoolean("available") && !s.state.equals("unsupported") && !s.state.equals("ended")
            s.requests = count(v, "requests")
            s.input = count(v, "input")
            s.output = count(v, "output")
            s.cache = count(v, "cache")
            s.thoughts = count(v, "thoughts")
            s.total = count(v, "total")
            s.apiMs = count(v, "apiMs")
            val change: JSONObject? = v.optJSONObject("change")
            if (change != null) {
                s.changeId = one(change.optString("id"), "[-a-zA-Z0-9]{1,64}")
                s.changeModel = model(change.optString("model"))
                s.changeState = one(change.optString("state"), "queued|applied|failed|cancelled")
            }
            val thinking: JSONObject? = v.optJSONObject("thinking")
            if (thinking != null) {
                s.thinking = one(thinking.optString("value"), "default|minimal|low|medium|high")
                s.thinkingModel = model(thinking.optString("model"))
                s.canChangeThinking = thinking.optBoolean("available")
                s.thinkingEffective = thinking.optBoolean("effective")
                val options: JSONArray? = thinking.optJSONArray("options")
                if (options != null) {
                    var i = 0
                    while (i < Math.min(options.length(), 5)) {
                        val option: String = one(options.optString(i), "default|minimal|low|medium|high")
                        if (!option.isEmpty() && !s.thinkingOptions.contains(option)) {
                            s.thinkingOptions.add(option)
                        }
                        i++
                    }
                }
            }
            s.permissionId = if (GeminiCli.validThread(v.optString("permissionId"))) v.optString("permissionId") else ""
            s.canChangeMode = v.optBoolean("canChangeMode")
            s.canPlan = v.optBoolean("canPlan")
            s.canEdit = v.optBoolean("canEdit")
            val m: JSONObject? = v.optJSONObject("modeChange")
            if (m != null) {
                s.modeChangeId = one(m.optString("id"), "[-a-zA-Z0-9]{1,64}")
                s.modeChangeMode = one(m.optString("mode"), "default|auto_edit|plan")
                s.modeChangeState = one(m.optString("state"), "queued|applied|failed|cancelled")
            }
            return s
        }

        @JvmStatic
        private fun one(value: String, pattern: String): String {
            return if ((value as java.lang.String).matches(pattern)) value else ""
        }

        @JvmStatic
        private fun model(value: String): String {
            return if (GeminiLiveControl.validModel(value)) value else ""
        }

        @JvmStatic
        private fun count(o: JSONObject, key: String): Long {
            val n: Long = o.optLong(key)
            return if (n >= 0 && n <= 9007199254740991L) n else 0L
        }

        @JvmStatic
        fun read(files: File, s: WorkspaceStore.Session): GeminiLiveStatus {
            try {
                if (!s.geminiAgent || !GeminiCli.validThread(s.geminiSocketId)) {
                    throw IOException("INVALID_RUN")
                }
                val f = File(GeminiCli.directory(files), s.geminiSocketId + ".status.json")
                if (Files.isSymbolicLink(f.toPath()) || !Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) || f.length() > 16384) {
                    throw IOException("NO_STATUS")
                }
                return parse(JSONObject(String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)), s.geminiSocketId, System.currentTimeMillis())
            } catch (e: Exception) {
                val out = GeminiLiveStatus()
                out.error = "UNAVAILABLE"
                return out
            }
        }
    }
}
