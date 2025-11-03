package com.example.webrtcapp.webrtc

import org.webrtc.MediaConstraints
import org.webrtc.AudioSource
import org.webrtc.AudioTrack

/**
 * AudioController
 *
 * Responsibility:
 *  - Own the local microphone capture as a WebRTC AudioTrack.
 *  - Provide simple APIs to start/stop/mute/unmute.
 *  - Prepare for later: attaching this track to a PeerConnection.
 *
 * Notes:
 *  - Requires RECORD_AUDIO permission (you already request it in MainActivity).
 *  - Uses JavaAudioDeviceModule from RtcDependencies (AEC/AGC/NS enabled by default).
 *  - There is nothing to "render" for audio locally; we just manage the track lifecycle.
 */
class AudioController(
    private val rtc: RtcDependencies
) {
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    /** Returns true if the audio track exists and is currently enabled (i.e., "mic on"). */
    val isEnabled: Boolean
        get() = audioTrack?.enabled() == true

    /**
     * Start microphone capture.
     * Safe to call multiple times (no-ops if already started).
     */
    @Synchronized
    fun start() {
        if (audioTrack != null) return

        // Constraints placeholder; defaults are fine. You can add keys if needed.
        val constraints = MediaConstraints().apply {
            // Example: you can tune constraints if you like (usually unnecessary).
            // mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            // mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            // mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        val source = rtc.peerConnectionFactory.createAudioSource(constraints)
        val track = rtc.peerConnectionFactory.createAudioTrack("local_audio", source)

        // Enabled = sending mic audio (later this controls if it’s sent over a PeerConnection).
        track.setEnabled(true)

        audioSource = source
        audioTrack = track
    }

    /**
     * Stop microphone capture and release native resources.
     * Safe to call multiple times.
     */
    @Synchronized
    fun stop() {
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        audioTrack = null
        audioSource = null
    }

    /** Mute mic (keep the track alive but disabled). */
    fun mute() {
        audioTrack?.setEnabled(false)
    }

    /** Unmute mic. */
    fun unmute() {
        audioTrack?.setEnabled(true)
    }

    /** Toggle mic enabled state; returns new state. */
    fun toggle(): Boolean {
        val now = !(audioTrack?.enabled() ?: false)
        audioTrack?.setEnabled(now)
        return now
    }

    /** Expose the track for later (to add to PeerConnection senders). */
    fun trackOrNull(): AudioTrack? = audioTrack
}