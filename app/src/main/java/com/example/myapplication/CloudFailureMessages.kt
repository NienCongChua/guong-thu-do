package com.example.myapplication

object CloudFailureMessages {
    fun describe(code: String, status: String): String = when {
        status == "cancelled" -> "Lần thử đã được hủy. Bạn có thể tạo một lần thử mới."
        status in setOf("expired", "deleted") -> "Ảnh đã bị xóa hoặc hết thời gian lưu."
        code.endsWith("_REJECTED") -> "Dịch vụ không xử lý được ảnh này. Hãy chọn ảnh rõ người và trang phục."
        code.endsWith("_QUOTA") -> "Dịch vụ ảnh đang giới hạn lượt sử dụng. Hãy thử lại sau."
        code == "PROCESSING_INTERRUPTED" -> "Quá trình xử lý bị gián đoạn. Không tự tạo lại để tránh dùng thêm lượt; hãy liên hệ người quản lý."
        code == "IMAGE_TIMEOUT" -> "Dịch vụ ảnh xử lý quá lâu. Yêu cầu không được tự gửi lại."
        code in setOf("UPLOAD_FAILED", "INPUT_EXPIRED") -> "Ảnh tải lên chưa hoàn tất hoặc đã hết hạn. Hãy chọn ảnh và tạo lại."
        code == "QUEUE_TIMEOUT" -> "Hàng đợi chưa xử lý được lần thử này. Hãy thử lại sau."
        code == "ACCESS_REVOKED" -> "Quyền tham gia beta đã hết hạn hoặc bị thu hồi."
        else -> "Lần thử chưa hoàn tất. Hãy kiểm tra lịch sử hoặc liên hệ người quản lý."
    }
}
