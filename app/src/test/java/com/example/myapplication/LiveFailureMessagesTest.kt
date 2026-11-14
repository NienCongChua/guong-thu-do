package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class LiveFailureMessagesTest {
    @Test fun preservesBackendActionableError() {
        val message = "Đã hết hạn mức demo hôm nay cho chế độ này (ngày UTC)."
        assertEquals(message, LiveFailureMessages.describe(LiveStep.TOKEN, BackendException(message)))
    }
    @Test fun classifiesNestedDnsFailure() {
        val message = LiveFailureMessages.describe(LiveStep.CONNECT,
            IllegalStateException("connection failed", UnknownHostException("api.decart.ai")))
        assertTrue(message.contains("DNS"))
    }
    @Test fun redactsUrlJsonAndBearerSecrets() {
        val secrets = listOf("ek_private123", "plainSensitiveValue", "privateBearerValue", "sk-private123")
        val source = "url=wss://api.decart.ai/v1/stream?api_key=ek_private123&model=lucy-vton-3.6 " +
            """{"apiKey":"plainSensitiveValue"} Authorization: Bearer privateBearerValue sk-private123"""
        val safe = LiveFailureMessages.redact(source)
        secrets.forEach { assertFalse("Exposed credential: $it", safe.contains(it)) }
        assertTrue(safe.contains("lucy-vton-3.6"))
    }
    @Test fun retainsUnknownSdkFailureAndStep() {
        val message = LiveFailureMessages.describe(LiveStep.CAMERA, IllegalStateException("Camera device unavailable"))
        assertTrue(message.contains("camera"))
        assertTrue(message.contains("Camera device unavailable"))
    }
    @Test fun quotaIsNotMaskedAsGenericVideoError() {
        val message = LiveFailureMessages.describe(LiveStep.CONNECT, IllegalStateException("HTTP 429: insufficient credits"))
        assertTrue(message.contains("hạn mức"))
    }
    @Test fun diagnosticsRedactCauseChain() {
        val error = IllegalStateException("Failed", IllegalArgumentException("api_key=secretWithoutPrefix"))
        assertFalse(LiveFailureMessages.diagnostic(LiveStep.CONNECT, error).contains("secretWithoutPrefix"))
    }
}
