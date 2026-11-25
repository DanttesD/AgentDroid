package com.termux.app.workspace

import android.text.TextUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Account limits are shared; thread model/effort belong to this dedicated Codex terminal.*/
class CodexStatus {
    @JvmField var cwd: String = ""
    @JvmField var title: String = ""
    @JvmField var planType: String = ""
    @JvmField var accountType: String = ""
    @JvmField var thread: String = ""
    @JvmField var model: String? = ""
    @JvmField var effort: String? = ""
    @JvmField var state: String = ""
    @JvmField var error: String? = ""
    @JvmField var updated: Long = 0L
    @JvmField var modelPending: Boolean = false
    @JvmField var materialized: Boolean? = null
    @JvmField var models: JSONArray = JSONArray()
    @JvmField var limits: JSONObject? = JSONObject()

    fun planLabel(): String {
        if (accountType.equals("apiKey")) {
            return tr("Вход: API-ключ", "Sign-in: API key")
        }
        var name: String
        when (planType) {
            "free" -> {
                name = "Free"
            }
            "go" -> {
                name = "Go"
            }
            "plus" -> {
                name = "Plus"
            }
            "pro" -> {
                name = "Pro"
            }
            "team" -> {
                name = "Team"
            }
            "business" -> {
                name = "Business"
            }
            "enterprise" -> {
                name = "Enterprise"
            }
            "edu" -> {
                name = "Edu"
            }
            "", "unknown", "none" -> {
                return tr("Подписка: нет данных", "Subscription: unavailable")
            }
            else -> {
                name = planType
            }
        }
        return tr("Подписка: ChatGPT ", "Subscription: ChatGPT ") + name
    }

    fun windows(): MutableList<String> {
        val out: MutableList<String> = ArrayList<String>()
        for (key in arrayOf<String>("primary", "secondary")) {
            if (limits!!.optJSONObject(key) != null) {
                out.add(key)
            }
        }
        return out
    }

    fun summary(): String {
        val values: MutableList<String> = ArrayList<String>()
        for (key in windows()) {
            values.add(limit(limits, key, windowLabel(limits, key, true)))
        }
        return if (values.isEmpty()) tr("Лимиты: нет данных", "Limits: unavailable") else TextUtils.join(" · ", values)
    }

    companion object {
        @Throws(Exception::class)
        @JvmStatic
        fun fromFile(file: File): CodexStatus {
            if (!file.isFile()) {
                throw IOException("Status helper has not started")
            }
            if (file.length() > 65536) {
                throw IOException("Invalid status size")
            }
            val bytes = ByteArrayOutputStream()
            (FileInputStream(file)).use { input ->
                val buffer = ByteArray(4096)
                var count: Int = 0
                while (true) {
                    count = input.read(buffer)
                    if (count == -1) break
                    if (bytes.size() + count > 65536) {
                        throw IOException("Invalid status size")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            val value = JSONObject(String(bytes.toByteArray(), StandardCharsets.UTF_8))
            if (value.optInt("schema") != 1) {
                throw IOException("Unsupported status format")
            }
            val result = CodexStatus()
            result.updated = value.optLong("updated")
            if (System.currentTimeMillis() - result.updated > 90000 || result.updated > System.currentTimeMillis() + 5000) {
                throw IOException("Status helper stopped responding")
            }
            if (value.opt("materialized") is Boolean) {
                result.materialized = value.getBoolean("materialized")
            }
            result.cwd = value.optString("cwd")
            result.title = value.optString("title")
            result.thread = value.optString("thread")
            result.model = value.optString("model")
            result.effort = value.optString("effort")
            result.state = value.optString("state")
            result.error = value.optString("error")
            val models: JSONArray? = value.optJSONArray("models")
            if (models != null) {
                result.models = models
            }
            val limits: JSONObject? = value.optJSONObject("limits")
            if (limits != null) {
                result.limits = limits
            }
            result.planType = plan(value.optString("planType", result.limits!!.optString("planType")))
            result.accountType = accountType(value.optString("accountType"))
            return result
        }

        @Throws(Exception::class)
        @JvmStatic
        fun local(files: File, profile: WorkspaceStore.Session?): CodexStatus {
            val socket: File = CodexSessionShell.socket(files, profile)
            val state = File(socket.getAbsolutePath() + ".state.json")
            val previous: Long = state.lastModified()
            (FileOutputStream(socket.getAbsolutePath() + ".refresh")).use { request ->
                request.write(1)
            }
            val until: Long = System.currentTimeMillis() + 3000
            while (state.lastModified() == previous && System.currentTimeMillis() < until) {
                Thread.sleep(50L)
            }
            if (!state.isFile()) {
                val pending = CodexStatus()
                pending.error = WorkspaceText.tr("Помощник статуса ещё не запущен. Для сессии, созданной до v0.2.13, откройте новый терминал Codex с тем же ID чата.")
                return pending
            }
            val result: CodexStatus = fromFile(state)
            if (CodexChatControl.enabled(files, profile)) {
                try {
                    val choice: JSONObject = CodexChatControl.request(files, profile, JSONObject().put("op", "status"))
                    if (choice.optString("thread").equals(result.thread) && !result.thread.isEmpty()) {
                        result.model = choice.optString("model", result.model)
                        result.effort = choice.optString("effort", result.effort)
                        result.modelPending = choice.optBoolean("pending")
                    }
                } catch (ignored: Exception) {
                }
            }
            return result
        }

        @Throws(Exception::class)
        @JvmStatic
        fun read(rpc: CodexRpc, preferredThread: String): CodexStatus {
            val result = CodexStatus()
            try {
                result.limits = rpc.call("account/rateLimits/read", JSONObject()).optJSONObject("rateLimits")
            } catch (unavailable: IOException) {
                result.limits = JSONObject()
            }
            if (result.limits == null) {
                result.limits = JSONObject()
            }
            result.planType = plan(result.limits!!.optString("planType"))
            try {
                val account: JSONObject? = rpc.call("account/read", JSONObject().put("refreshToken", false)).optJSONObject("account")
                if (account != null) {
                    result.accountType = accountType(account.optString("type"))
                    if (result.accountType.equals("chatgpt")) {
                        result.planType = plan(account.optString("planType", result.planType))
                    }
                }
            } catch (unavailable: IOException) {
            }
            val listed: JSONObject = rpc.call("thread/loaded/list", JSONObject())
            val ids: JSONArray? = listed.optJSONArray("data")
            if (ids != null) {
                var i = 0
                while (i < ids.length()) {
                    val id: String = ids.getString(i)
                    val thread: JSONObject = rpc.call("thread/read", JSONObject().put("threadId", id).put("includeTurns", false)).getJSONObject("thread")
                    // Ignore agent descendants. One root chat is owned by this server/TUI.
                    if (!thread.isNull("parentThreadId") && !thread.optString("parentThreadId").isEmpty()) {
                        i++
                        continue
                    }
                    if (!preferredThread.isEmpty() && !id.equals(preferredThread)) {
                        i++
                        continue
                    }
                    if (!result.thread.isEmpty()) {
                        result.thread = ""
                        result.error = WorkspaceText.tr("Несколько чатов: выберите чат в терминале")
                        break
                    }
                    result.thread = id
                    result.model = thread.optString("model", "")
                    result.effort = thread.optString("reasoningEffort", "")
                    val status: JSONObject? = thread.optJSONObject("status")
                    result.state = if (status == null) "" else status.optString("type")
                    i++
                }
            }
            result.updated = System.currentTimeMillis()
            return result
        }

        @JvmStatic
        private fun tr(ru: String, en: String): String {
            return if (WorkspaceText.tr("нет данных")!!.equals("нет данных")) ru else en
        }

        @JvmStatic
        fun plan(value: String?): String {
            return if (value != null && (value as java.lang.String).matches("[a-z][a-z0-9_-]{0,47}")) value else ""
        }

        @JvmStatic
        private fun accountType(value: String): String {
            return if (value.equals("chatgpt") || value.equals("apiKey")) value else ""
        }

        @JvmStatic
        fun remaining(value: JSONObject?): Int? {
            val used: Any? = if (value == null) null else value.opt("usedPercent")
            if (!(used is Number)) {
                return null
            }
            val percent: Double = (used as Number).toDouble()
            if (!java.lang.Double.isFinite(percent) || percent < 0) {
                return null
            }
            return Math.max(0, Math.min(100, Math.round(100 - percent).toInt()))
        }

        @JvmStatic
        fun windowLabel(limits: JSONObject?, key: String, compact: Boolean): String? {
            val value: JSONObject? = if (limits == null) null else limits.optJSONObject(key)
            val durationValue: Any? = if (value == null) null else value.opt("windowDurationMins")
            var minutes: Long = if (durationValue is Number) (durationValue as Number).toLong() else 0L
            if (durationValue is Number && (durationValue as Number).toDouble() != minutes.toDouble()) {
                minutes = 0L
            }
            if (minutes == 300L) {
                return if (compact) WorkspaceText.tr("5ч") else WorkspaceText.tr("Пятичасовой лимит")
            }
            if (minutes == 10080L) {
                return if (compact) WorkspaceText.tr("7д") else WorkspaceText.tr("Недельный лимит")
            }
            if (minutes > 0) {
                val duration: String = if (minutes % 1440 == 0L) (minutes / 1440).toString() + tr("д", "d") else if (minutes % 60 == 0L) (minutes / 60).toString() + tr("ч", "h") else minutes.toString() + tr("мин", "min")
                return if (compact) duration else tr("Лимит за ", "Limit over ") + duration
            }
            return if (compact) tr("Лимит ", "Limit ") + (if (key.equals("primary")) "1" else "2") else tr("Лимит · период не указан", "Limit · period unspecified")
        }

        @JvmStatic
        fun limit(limits: JSONObject?, window: String, label: String?): String {
            val remaining: Int? = remaining(if (limits == null) null else limits.optJSONObject(window))
            return label + (if (remaining == null) WorkspaceText.tr(": нет данных") else ": " + remaining + "%")
        }
    }
}
