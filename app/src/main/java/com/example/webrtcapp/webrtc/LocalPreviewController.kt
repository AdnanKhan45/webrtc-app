package com.example.webrtcapp.webrtc

import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * LocalPreviewController
 *
 * Responsibility:
 *  - Initialize a SurfaceViewRenderer with the shared EGL context.
 *  - Start/stop a camera capturer and render the local video track.
 *  - Clean up native resources when no longer needed.
 *
 * Lifecycle:
 *  - call attachRenderer(renderer)
 *  - call start(front = true) AFTER camera permission is granted
 *  - call stop() when leaving the screen
 *  - call release() on final teardown
 */
class LocalPreviewController(
    private val appContext: Context,
    private val rtc: RtcDependencies
) {
    private var renderer: SurfaceViewRenderer? = null

    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private val eglContext: EglBase.Context get() = rtc.eglBase.eglBaseContext

    /**
     * Bind a SurfaceViewRenderer (from your XML) to WebRTC EGL.
     * Safe to call multiple times; re-inits if needed.
     */
    fun attachRenderer(view: SurfaceViewRenderer, mirror: Boolean = true) {
        // If we previously had a renderer, detach sinks first.
        videoTrack?.removeSink(renderer)
        renderer?.release()

        renderer = view.apply {
            init(eglContext, /* rendererEvents = */ null)
            setMirror(mirror)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(false)
        }

        // If a track already exists (e.g., attach after start), connect it.
        videoTrack?.addSink(renderer)
    }

    /**
     * Start local camera preview. Call only after CAMERA permission is granted.
     * If already started, this is a no-op.
     */
    @Synchronized
    fun start(front: Boolean = true, width: Int = 1280, height: Int = 720, fps: Int = 30) {
        if (videoCapturer != null) return

        // 1) Pick a camera
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = enumerator.deviceNames.firstOrNull { name ->
            if (front) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        } ?: enumerator.deviceNames.firstOrNull()
        requireNotNull(deviceName) { "No camera devices available." }

        val capturer = enumerator.createCapturer(deviceName, /* eventsHandler = */ null)
            ?: throw IllegalStateException("Failed to create VideoCapturer for $deviceName")

        // 2) Create capture thread + video source
        val helper = SurfaceTextureHelper.create("CaptureThread", eglContext)
        val source = rtc.peerConnectionFactory.createVideoSource(/* isScreencast = */ false)

        // 3) Initialize the capturer
        capturer.initialize(helper, appContext, source.capturerObserver)

        // 4) Start capture
        capturer.startCapture(width, height, fps)

        // 5) Build a local video track and attach to renderer (if present)
        val track = rtc.peerConnectionFactory.createVideoTrack("local_video", source)
        renderer?.let { track.addSink(it) }

        // 6) Keep references for stop/release
        surfaceHelper = helper
        videoCapturer = capturer
        videoSource = source
        videoTrack = track
    }

    /**
     * Stop preview but keep the renderer initialized (so you can start again quickly).
     */
    @Synchronized
    fun stop() {
        val capturer = videoCapturer ?: return
        try {
            // Detach sinks first
            videoTrack?.removeSink(renderer)
            // Stop capture synchronously
            capturer.stopCapture()
        } catch (_: Throwable) {
            // Some devices throw if already stopped — ignore gracefully.
        }

        // Dispose media chain in reverse order
        try { capturer.dispose() } catch (_: Throwable) { }
        try { surfaceHelper?.dispose() } catch (_: Throwable) { }

        videoCapturer = null
        surfaceHelper = null

        // Release track/source (native)
        try { videoTrack?.dispose() } catch (_: Throwable) { }
        try { videoSource?.dispose() } catch (_: Throwable) { }

        videoTrack = null
        videoSource = null
    }

    /**
     * Full teardown. Call when the screen is destroyed permanently.
     * (Does NOT release the renderer View itself; Activity/Fragment owns that.)
     */
    fun release() {
        stop()
        // Keep renderer lifecycle with the View; we only clear our reference
        renderer = null
    }
}