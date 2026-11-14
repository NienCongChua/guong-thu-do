package com.example.myapplication

/**
 * SDK 0.7.3 requires obsolete server_ip/server_port on the informational session_id
 * event. The service now omits them. Its independent livekit_room_info event is the
 * source of Room URL/token/session ID used by connect().
 *
 * Recognize only this exact optional-metadata parse error. Never suppress a missing
 * session_id, malformed LiveKit credentials, server rejection, or transport failure.
 */
object DecartCompatibility {
    fun isLegacySessionMetadataError(code: String, cause: Throwable?): Boolean {
        if (code != "WEBRTC_SIGNALING_ERROR" || cause == null) return false
        return generateSequence(cause) { it.cause }.take(6).any { error ->
            val message = error.message.orEmpty()
            if (!message.contains("ai.decart.sdk.realtime.SessionIdMessage")) return@any false
            val fields = Regex("Fields? \\[([^]]+)\\]").find(message)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('\'', '"') }?.toSet() ?: return@any false
            fields.isNotEmpty() && fields.all { it == "server_ip" || it == "server_port" }
        }
    }
}
