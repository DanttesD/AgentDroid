package com.termux.app.workspace

import org.json.JSONObject

/** UI names only: a tool call is never presented as proof that a skill was used.*/
object CodexActionLabels {
    @JvmStatic
    fun title(row: JSONObject?, ru: Boolean): String {
        val type: String = row!!.optString("toolType")
        if (row.optString("skillSource").equals("read")) {
            return (if (ru) "Чтение инструкций: " else "Read instructions: ") + row.optString("skillName")
        }
        if (type.equals("skill")) {
            return (if (ru) "Подключён навык: " else "Skill attached: ") + row.optString("skillName")
        }
        val original: String = row.optString("title")
        var name: String = row.optString("toolName")
        if (name.isEmpty()) {
            name = if (type.isEmpty()) original else type
        }
        if (name.equals("mcpToolCall") || name.equals("dynamicToolCall") || name.equals("functionCallOutput")) {
            name = row.optString("toolName", original)
        }
        val parts: Array<String> = (name as java.lang.String).split("[./]|__")
        val last: String = if (parts.size == 0) name else parts[parts.size - 1]
        when (last) {
            "imageView", "image_view", "view_image" -> {
                return if (ru) "Просмотр изображения" else "View image"
            }
            "imageGeneration", "image_generation", "imagegen", "generate_image" -> {
                return if (ru) "Создание изображения" else "Generate image"
            }
            "WebSearch", "webSearch", "web_search", "google_web_search" -> {
                return if (ru) "Поиск в интернете" else "Search the web"
            }
            "Tool result" -> {
                return if (ru) "Результат действия" else "Action result"
            }
            "Tool error" -> {
                return if (ru) "Ошибка действия" else "Action error"
            }
            "Read", "read", "read_file", "read_many_files" -> {
                return if (ru) "Чтение файлов" else "Read files"
            }
            "Write", "write", "write_file" -> {
                return if (ru) "Запись файла" else "Write file"
            }
            "Edit", "edit", "replace", "apply_patch" -> {
                return if (ru) "Изменение файлов" else "Edit files"
            }
            "Glob", "glob", "glob_files" -> {
                return if (ru) "Поиск файлов" else "Find files"
            }
            "Grep", "grep", "grep_search", "search_file_content" -> {
                return if (ru) "Поиск в файлах" else "Search file contents"
            }
            "Bash", "bash", "run_shell_command" -> {
                return if (ru) "Команда shell" else "Shell command"
            }
            "WebFetch", "webfetch", "web_fetch" -> {
                return if (ru) "Чтение веб-страницы" else "Read web page"
            }
            else -> {
                return original
            }
        }
    }
}
