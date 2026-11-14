package com.example.myapplication

import ai.decart.sdk.realtime.RealtimeMediaStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

// Adapted from Decart Android SDK 0.7.3 sample/LiveKitVideoView.kt (MIT).
// A renderer belongs to exactly one Room/EGL context; track and view are detached on disposal.
@Composable
fun LiveVideo(stream: RealtimeMediaStream, modifier: Modifier = Modifier, onFirstFrame: () -> Unit = {}) {
    val room = stream.room ?: return
    val track = stream.videoTrack ?: return
    val latest = rememberUpdatedState(onFirstFrame)
    key(room, track) {
        val holder = remember { VideoHolder() }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                TextureViewRenderer(context).also { renderer ->
                    renderer.init(room.lkObjects.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() { renderer.post { latest.value() } }
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
                    })
                    renderer.setMirror(false)
                    renderer.setEnableHardwareScaler(true)
                    renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    // For the mirror output, track is exactly onRemoteStream's stream.videoTrack.
                    track.addRenderer(renderer)
                    holder.track = track
                    holder.renderer = renderer
                }
            }
        )
        DisposableEffect(Unit) {
            onDispose {
                holder.renderer?.let { view ->
                    runCatching { holder.track?.removeRenderer(view) }
                    runCatching { view.release() }
                }
                holder.track = null; holder.renderer = null
            }
        }
    }
}
private class VideoHolder {
    var renderer: TextureViewRenderer? = null
    var track: VideoTrack? = null
}
