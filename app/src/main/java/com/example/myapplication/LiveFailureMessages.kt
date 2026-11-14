package com.example.myapplication

import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class LiveStep(val label: String) {
    TOKEN("xin token từ backend"),
    CAMERA("mở camera trước"),
    CONNECT("kết nối video tới Decart"),
    GARMENT("gửi ảnh trang phục tới Decart")
}

/** Keeps useful provider errors without exposing credentials or reference image data. */
object LiveFailureMessages {
    fun redact(raw: String): String = raw
        .replace(Regex("(?i)([?&](?:api_key|apiKey|token|access_token)=)[^&\\s\\\"']+"), "$1[ẨN]")
        .replace(Regex("(?i)Bearer\\s+[^\\s\\\"',}]+"), "Bearer [ẨN]")
        .replace(Regex("(?i)((?:api_key|apiKey|token|authorization)[\\\"'\\s]*[:=][\\\"'\\s]*)[^\\s\\\"',}&]+"), "$1[ẨN]")
        .replace(Regex("(?:ek_|dct_|sk-)[A-Za-z0-9_.%=-]+"), "[ẨN]")
        .replace(Regex("[A-Za-z0-9_+/=-]{100,}"), "[DỮ LIỆU ĐÃ ẨN]")

    fun describe(step: LiveStep, error: Throwable): String {
        if (error is BackendException) return redact(error.message.orEmpty())
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        val detail = redact(causes.mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct().joinToString(" → ")).take(450)
        val raw = causes.joinToString(" ") { it.javaClass.simpleName + ": " + it.message.orEmpty() }
        val reason = when {
            causes.any { it is UnknownHostException } || raw.contains("Unable to resolve host", true) ->
                "Điện thoại không phân giải được máy chủ dịch vụ. Kiểm tra Internet/Wi-Fi/4G hoặc DNS trên điện thoại."
            causes.any { it is SSLException } ->
                "Kết nối TLS/chứng chỉ tới dịch vụ bị từ chối. Kiểm tra ngày giờ điện thoại và mạng đang dùng."
            raw.contains("429") || raw.contains("402") || raw.contains("credit", true) ||
                raw.contains("quota", true) || raw.contains("balance", true) ->
                "Decart hết hạn mức, số dư hoặc giới hạn phiên đồng thời. Kiểm tra tài khoản Decart."
            raw.contains("401") || raw.contains("403") || raw.contains("INVALID_API_KEY", true) ||
                raw.contains("unauthorized", true) || raw.contains("token expired", true) ->
                "Decart từ chối token hoặc quyền model. Lấy token mới và kiểm tra quyền lucy-vton-3.6."
            raw.contains("model", true) && (raw.contains("not found", true) || raw.contains("unsupported", true)) ->
                "Decart không chấp nhận model lucy-vton-3.6 cho phiên này. Kiểm tra quyền model và phiên bản SDK."
            causes.any { it is SecurityException } && raw.contains("RECORD_AUDIO", true) ->
                "SDK yêu cầu microphone khi mở phiên chỉ có video. Cần kiểm tra cấu hình SDK; không bật thu âm."
            causes.any { it is SecurityException } ->
                "Hệ thống từ chối quyền khi ${step.label}. Kiểm tra quyền camera của ứng dụng."
            raw.contains("timeout", true) || raw.contains("timed out", true) || raw.contains("Timed out", true) ->
                "Hết thời gian chờ tại bước ${step.label}. Kiểm tra mạng và thử lại."
            step == LiveStep.CAMERA ->
                "Không khởi tạo được camera trước. Đóng ứng dụng khác đang dùng camera rồi thử lại."
            else -> "Không hoàn tất được bước ${step.label}."
        }
        return "$reason\nChi tiết: ${detail.ifBlank { error.javaClass.simpleName }}"
    }

    fun diagnostic(step: LiveStep, error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(6)
        return redact("step=${step.name}\n" + causes.joinToString("\nCaused by: ") {
            it.javaClass.name + ": " + it.message.orEmpty() + "\n" +
                it.stackTrace.take(12).joinToString("\n")
        })
    }
}
