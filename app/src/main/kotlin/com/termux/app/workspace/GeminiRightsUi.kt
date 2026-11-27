package com.termux.app.workspace

import java.util.Arrays

/** Same native approval modes in launch settings and the live card. No OS sandbox claims.*/
object GeminiRightsUi {
    @JvmField val MODES: Array<String> = arrayOf<String>("default", "auto_edit", "plan")

    @JvmStatic
    fun normalize(mode: String?): String {
        return if ("autoEdit".equals(mode)) "auto_edit" else if (mode == null) "" else mode
    }

    @JvmStatic
    fun valid(mode: String): Boolean {
        return Arrays.asList(*MODES).contains(mode)
    }

    @JvmStatic
    fun label(mode: String): String? {
        when (normalize(mode)) {
            "default" -> {
                return WorkspaceText.tr("Подтверждать действия")
            }
            "auto_edit" -> {
                return WorkspaceText.tr("Разрешать редактирование")
            }
            "plan" -> {
                return WorkspaceText.tr("Планирование")
            }
            "yolo" -> {
                return "YOLO"
            }
            else -> {
                return WorkspaceText.tr("нет данных")
            }
        }
    }

    @JvmStatic
    fun description(mode: String): String? {
        when (normalize(mode)) {
            "default" -> {
                return WorkspaceText.tr("Gemini запрашивает разрешения на действия в терминале.")
            }
            "auto_edit" -> {
                return WorkspaceText.tr("Gemini автоматически разрешает инструменты редактирования; остальные действия требуют подтверждения.")
            }
            "plan" -> {
                return WorkspaceText.tr("Анализ и план без выполнения изменений. Это режим Gemini, а не системная песочница.")
            }
            else -> {
                return ""
            }
        }
    }
}
