package com.termux.app.workspace

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Reports allowlisted codes/types, never exception messages or capability URLs.*/
object ServerMediaDiagnostics {
    @JvmStatic
    fun describe(error: PlaybackException, ru: Boolean): String {
        var reason: String = if (ru) "Не удалось воспроизвести файл." else "Could not play the file."
        var detail = ""
        var cause: Throwable? = error
        val seen: MutableSet<Throwable> = HashSet<Throwable>()
        var depth = 0
        while (cause != null && depth < 10 && seen.add(cause)) {
            if (cause is HttpDataSource.CleartextNotPermittedException) {
                reason = if (ru) "Android заблокировал локальный HTTP. Проверьте версию приложения." else "Android blocked local HTTP. Check the app version."
            } else if (cause is HttpDataSource.InvalidResponseCodeException) {
                val code: Int = (cause as HttpDataSource.InvalidResponseCodeException).responseCode
                detail = "HTTP " + code
                reason = if (code == 404) if (ru) "Временная ссылка недоступна или истекла. Откройте файл заново." else "Temporary link unavailable or expired. Open the file again." else if (code == 409) if (ru) "Файл изменился на сервере. Откройте его заново." else "The server file changed. Open it again." else if (code == 416) if (ru) "Сервер отклонил диапазон чтения файла." else "The server rejected the requested byte range." else if (ru) "Сервер отклонил чтение файла." else "Server rejected file access."
            } else if (cause is ConnectException) {
                reason = if (ru) "Нет соединения с локальным SSH-туннелем. Откройте файл заново." else "Cannot connect to the local SSH tunnel. Open the file again."
            } else if (cause is SocketTimeoutException) {
                reason = if (ru) "Истекло время чтения. Проверьте сеть и SSH-туннель." else "Read timed out. Check network and SSH tunnel."
            }
            depth++
            cause = cause.cause
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            reason = if (ru) "Плеер не распознал контейнер видео/аудио." else "Player could not recognize the media container."
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            reason = if (ru) "Декодер устройства не смог открыть этот формат." else "The device decoder could not open this format."
        }
        var code: String = error.getErrorCodeName()
        if (!(code as java.lang.String).matches("[A-Z0-9_]{1,100}")) {
            code = "UNKNOWN"
        }
        return reason + "\n" + (if (ru) "Код: " else "Code: ") + code + (if (detail.isEmpty()) "" else " · " + detail) + "\n" + (if (ru) "Попробуйте внешний плеер или скачайте файл." else "Try an external player or download the file.")
    }
}
