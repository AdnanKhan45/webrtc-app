package com.example.webrtcapp.webrtc

import android.content.Context
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * App-scoped WebRTC infrastructure.
 *
 * This class wires together the “engine room” pieces we’ll need across the app:
 *  - EGL context for hardware-accelerated video pipelines.
 *  - AudioDeviceModule (mic/speaker with AEC/AGC/NS).
 *  - Video encoder/decoder factories.
 *  - PeerConnectionFactory (creates tracks, sources, peer connections).
 *
 * It does NOT start camera/mic capture and does NOT need runtime permissions yet.
 *
 * Lifetime: create once (e.g., in Application / DI graph) and reuse.
 */
class RtcDependencies(
    appContext: Context
) {
    /** Shared EGL context used by encoders/decoders and renderers. */
    val eglBase: EglBase = EglBase.create()

    /** Audio path (mic/speaker). We’ll keep defaults for now (AEC/AGC/NS on). */
    val audioDeviceModule: AudioDeviceModule =
        JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()

    /** Hardware-accelerated video encoder/decoder factories (backed by MediaCodec via EGL). */
    val videoEncoderFactory = HardwareVideoEncoderFactory(
        eglBase.eglBaseContext,
        /* enableIntelVp8Encoder = */ true,
        /* enableH264HighProfile = */ true
    )
    val videoDecoderFactory = HardwareVideoDecoderFactory(eglBase.eglBaseContext)

    /** The factory that manufactures tracks/sources/peer connections. */
    val peerConnectionFactory: PeerConnectionFactory

    init {
        // One-time global init (logging, field trials, internal threads).
        PeerConnectionFactory.initialize(
            PeerConnectionFactory
                .InitializationOptions
                .builder(appContext)
                .setEnableInternalTracer(true) // we can use this later for debugging
                .createInitializationOptions()
        )

        // Build the per-app factory using our audio + video components.
        peerConnectionFactory = PeerConnectionFactory
            .builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Release native resources when the app is shutting down.
     * (We’ll call this from the hosting scope later, not now.)
     */
    fun dispose() {
        // Order: modules first, then EGL. Tracer stop is optional.
        try { audioDeviceModule.release() } catch (_: Throwable) { }
        try { eglBase.release() } catch (_: Throwable) { }
        try { PeerConnectionFactory.shutdownInternalTracer() } catch (_: Throwable) { }
    }
}