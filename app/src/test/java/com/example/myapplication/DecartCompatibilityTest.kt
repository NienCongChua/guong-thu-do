package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test

class DecartCompatibilityTest {
    private fun missing(fields: String, type: String = "SessionIdMessage") =
        IllegalStateException("Fields [$fields] are required for type with serial name 'ai.decart.sdk.realtime.$type', but they were missing")

    @Test fun acceptsObservedObsoleteAddressMetadata() {
        assertTrue(DecartCompatibility.isLegacySessionMetadataError("WEBRTC_SIGNALING_ERROR",
            missing("server_ip, server_port")))
    }
    @Test fun acceptsSingleMissingObsoleteFieldInCauseChain() {
        assertTrue(DecartCompatibility.isLegacySessionMetadataError("WEBRTC_SIGNALING_ERROR",
            RuntimeException("Signaling error", missing("server_port"))))
    }
    @Test fun missingSessionIdRemainsFatal() {
        assertFalse(DecartCompatibility.isLegacySessionMetadataError("WEBRTC_SIGNALING_ERROR",
            missing("session_id, server_ip, server_port")))
    }
    @Test fun malformedLiveKitRoomInfoRemainsFatal() {
        assertFalse(DecartCompatibility.isLegacySessionMetadataError("WEBRTC_SIGNALING_ERROR",
            missing("server_ip, server_port", "LiveKitRoomInfoMessage")))
    }
    @Test fun transportAndServerErrorsRemainFatal() {
        for (code in listOf("WEBRTC_WEBSOCKET_ERROR", "WEBRTC_SERVER_ERROR", "INVALID_API_KEY")) {
            assertFalse(DecartCompatibility.isLegacySessionMetadataError(code, missing("server_ip, server_port")))
        }
        assertFalse(DecartCompatibility.isLegacySessionMetadataError("WEBRTC_SIGNALING_ERROR",
            IllegalStateException("HTTP 403 forbidden")))
        assertFalse(DecartCompatibility.isLegacySessionMetadataError("WEBRTC_SIGNALING_ERROR", null))
    }
}
