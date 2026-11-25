package com.termux.app.workspace

import java.io.File
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Presentation and fixed dependency commands; file/server text is never shell syntax.*/
object ServerFileInfo {
    @JvmStatic
    fun media(path: String): Boolean {
        val name: String = (path as java.lang.String).toLowerCase(Locale.ROOT)
        val dot: Int = name.lastIndexOf('.')
        return dot >= 0 && Arrays.asList("mp4", "mkv", "webm", "avi", "mov", "m4v", "mpeg", "mpg", "m2ts", "3gp", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff").contains(name.substring(dot + 1))
    }

    @JvmStatic
    fun size(value: Long, ru: Boolean): String {
        if (value < 0) {
            return "—"
        }
        val units: Array<String> = if (ru) arrayOf<String>("Б", "КБ", "МБ", "ГБ", "ТБ") else arrayOf<String>("B", "KB", "MB", "GB", "TB")
        if (value < 1024) {
            return value.toString() + " " + units[0]
        }
        var size: Double = value.toDouble()
        var i = 0
        while (size >= 1024 && i < units.size - 1) {
            size /= 1024
            i++
        }
        return java.lang.String.format(if (ru) Locale("ru") else Locale.US, "%.1f %s", size, units[i])
    }

    @JvmStatic
    fun installCommand(root: Boolean): String {
        val prefix: String = if (root) "env DEBIAN_FRONTEND=noninteractive " else "sudo -n env DEBIAN_FRONTEND=noninteractive "
        return prefix + "apt-get update && " + prefix + "apt-get install --no-install-recommends -y ffmpeg"
    }

    @JvmStatic
    fun properties(data: JSONObject?, ru: Boolean, zone: TimeZone): String {
        val path: String = data!!.optString("path")
        val out = StringBuilder()
        out.append(if (ru) "Имя: " else "Name: ").append(File(path).getName()).append('\n')
        out.append(if (ru) "Путь: " else "Path: ").append(path).append('\n')
        out.append(if (ru) "Размер: " else "Size: ").append(size(data.optLong("size", (-1).toLong()), ru)).append('\n')
        out.append(if (ru) "Изменён: " else "Modified: ")
        val seconds: Double = data.optDouble("modified", java.lang.Double.NaN)
        if (java.lang.Double.isFinite(seconds) && seconds >= 0 && seconds < 253402300800.0) {
            val format = SimpleDateFormat("dd.MM.yyyy HH:mm:ss z", if (ru) Locale("ru") else Locale.US)
            format.setTimeZone(zone)
            out.append(format.format(Date((seconds * 1000).toLong())))
        } else {
            out.append('—')
        }
        if (media(path)) {
            val info: JSONObject? = data.optJSONObject("media")
            if (info != null) {
                val format: JSONObject? = info.optJSONObject("format")
                val duration: Double = if (format == null) java.lang.Double.NaN else format.optDouble("duration", java.lang.Double.NaN)
                if (java.lang.Double.isFinite(duration) && duration >= 0) {
                    val s: Long = duration.toLong()
                    out.append('\n').append(if (ru) "Длительность: " else "Duration: ").append(java.lang.String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60))
                }
                val streams: JSONArray? = info.optJSONArray("streams")
                if (streams != null) {
                    var i = 0
                    while (i < Math.min(streams.length(), 20)) {
                        val stream: JSONObject? = streams.optJSONObject(i)
                        if (stream == null) {
                            i++
                            continue
                        }
                        out.append('\n').append(if (ru) "Дорожка " else "Stream ").append(i + 1).append(": ").append(stream.optString("codec_name", "—"))
                        if (stream.optInt("width") > 0 && stream.optInt("height") > 0) {
                            out.append(" · ").append(stream.optInt("width")).append('×').append(stream.optInt("height"))
                        }
                        i++
                    }
                }
            } else if (data.optJSONObject("components") != null && !data.optJSONObject("components").optBoolean("ffprobe")) {
                out.append("\n\n").append(if (ru) "Для длительности, разрешения и кодеков установите FFmpeg на сервер. Сам просмотр видео не требует ffprobe." else "Install FFmpeg on the server for duration, resolution and codecs. Video streaming itself does not require ffprobe.")
            } else {
                out.append("\n\n").append(if (ru) "Медиаданные не прочитаны: формат может не поддерживаться или файл недоступен для анализа." else "Media details unavailable: unsupported format or analysis access failure.")
            }
        }
        return out.toString()
    }
}
