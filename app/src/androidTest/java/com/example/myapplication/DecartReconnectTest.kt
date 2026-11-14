package com.example.myapplication

import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealTimeClient
import ai.decart.sdk.realtime.RealTimeClientConfig
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.VideoSink
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/** Exercises SDK-owned camera retries against localhost; no provider, token, or photo upload. */
@RunWith(AndroidJUnit4::class)
class DecartReconnectTest {
    @Test fun sdkOwnedCameraGetsFreshRoomOnConnectRetry(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use {
            val server = MockWebServer()
            // Fail the first WebSocket handshake; hold the second open without a media room.
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val sdk = RealTimeClient(context, RealTimeClientConfig(
                apiKey = "unused-local-test-key",
                baseUrl = "ws://127.0.0.1:${server.port}"
            ))
            val firstStream = CompletableDeferred<RealtimeMediaStream>()
            val replacementStream = CompletableDeferred<RealtimeMediaStream>()
            val firstReplacementFrame = CompletableDeferred<Unit>()
            var observedReplacement: RealtimeMediaStream? = null
            val sink = VideoSink { frame ->
                if (frame.rotatedWidth > 0 && frame.rotatedHeight > 0) firstReplacementFrame.complete(Unit)
            }
            val streams = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                sdk.localStreamUpdates.collect { stream ->
                    if (!firstStream.isCompleted) firstStream.complete(stream)
                    else if (!replacementStream.isCompleted) {
                        observedReplacement = stream
                        stream.videoTrack?.addRenderer(sink)
                        replacementStream.complete(stream)
                    }
                }
            }
            val connection = launch(Dispatchers.Main) {
                sdk.connect(ConnectOptions(
                    model = RealtimeModel("lucy-vton-3.6", "/v1/stream", 30, 1280, 720),
                    publishCamera = true, facing = FacingMode.FRONT, mirror = MirrorMode.OFF
                ))
            }
            try {
                withTimeout(10000) {
                    val first = firstStream.await()
                    val replacement = replacementStream.await()
                    assertNotNull(first.room)
                    assertNotNull(first.videoTrack)
                    assertNotNull(replacement.room)
                    assertNotNull(replacement.videoTrack)
                    assertNotSame(first.room, replacement.room)
                    assertNotSame(first.videoTrack, replacement.videoTrack)
                    firstReplacementFrame.await()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    connection.cancelAndJoin()
                    streams.cancelAndJoin()
                    observedReplacement?.videoTrack?.removeRenderer(sink)
                    sdk.release()
                }
                server.shutdown()
            }
        }
    }
}
