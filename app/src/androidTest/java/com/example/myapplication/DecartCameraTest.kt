package com.example.myapplication

import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealTimeClient
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.VideoSink
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-camera smoke test. Does not mint a token, upload an image, or open a paid session. */
@RunWith(AndroidJUnit4::class)
class DecartCameraTest {
    @Test fun frontCameraProducesFramesWithoutMicrophone(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use {
            var stream: RealtimeMediaStream? = null
            val firstFrame = CompletableDeferred<Unit>()
            val sink = VideoSink { frame -> if (frame.rotatedWidth > 0 && frame.rotatedHeight > 0) firstFrame.complete(Unit) }
            try {
                withContext(Dispatchers.Main) {
                    stream = RealTimeClient.createLocalVideoStream(context,
                        RealtimeModel("lucy-vton-3.6", "/v1/stream", 30, 1280, 720),
                        facing = FacingMode.FRONT, mirror = MirrorMode.OFF)
                    assertNotNull(stream?.videoTrack)
                    stream?.videoTrack?.addRenderer(sink)
                }
                withTimeout(10000) { firstFrame.await() }
            } finally {
                withContext(Dispatchers.Main) {
                    stream?.videoTrack?.removeRenderer(sink)
                    stream?.dispose()
                }
            }
        }
    }
}
