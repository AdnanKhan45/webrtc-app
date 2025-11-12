package com.example.webrtcapp.webrtc

import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class RtcClient(
    private val appContext: Context,
    private val deps: RtcDependencies,
    rtcConfig: PeerConnection.RTCConfiguration,
    private val listener: Listener
) {

    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onRemoteTrack(receiver: RtpReceiver, transceiver: RtpTransceiver)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
        fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState)
        fun onSignalingStateChange(state: PeerConnection.SignalingState) {}
    }

    private val peerConnection: PeerConnection

    // Keep local tracks + senders so we can replace tracks later.
    private var localAudioTrack: MediaStreamTrack? = null
    private var localVideoTrack: MediaStreamTrack? = null
    private var localAudioSender: RtpSender? = null
    private var localVideoSender: RtpSender? = null

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    private var audioTransceiver: RtpTransceiver? = null
    private var videoTransceiver: RtpTransceiver? = null

    private val streamIds = listOf("ARDAMS")

    init {
        peerConnection = deps.peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    listener.onLocalIceCandidate(candidate)
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    listener.onRemoteTrack(transceiver.receiver, transceiver)
                }

                @Deprecated("Use onTrack with Unified Plan")
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    // no-op
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    listener.onConnectionStateChange(newState)
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    listener.onIceConnectionStateChange(newState)
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                    listener.onSignalingStateChange(newState)
                }
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
            }
        ) ?: error("Failed to create PeerConnection")
    }

    // -------------------------
    // Local media (common step)
    // -------------------------

    fun attachLocalTracks(audio: MediaStreamTrack?, video: MediaStreamTrack?) {
        audio?.let { t ->
            // Prefer using the transceiver if we created one
            val tx = audioTransceiver
            if (tx != null) {
                tx.sender.setTrack(t, /*takeOwnership=*/true)
                tx.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                localAudioTrack = t
            } else {
                localAudioSender = peerConnection.addTrack(t, streamIds)
                localAudioTrack = t
            }
        }

        video?.let { t ->
            val tx = videoTransceiver
            if (tx != null) {
                tx.sender.setTrack(t, /*takeOwnership=*/true)
                tx.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                localVideoTrack = t
            } else {
                localVideoSender = peerConnection.addTrack(t, streamIds)
                localVideoTrack = t
            }
        }
    }

    // -------------------------
    // CALLER flow
    // -------------------------

    fun createLocalOfferAndSet(
        onSuccess: (SessionDescription) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        peerConnection.createOffer(
            object : SdpObserverAdapter(
                onCreate = { sdp ->
                    peerConnection.setLocalDescription(
                        object : SdpObserverAdapter(
                            onSet = { onSuccess(sdp) },
                            onError = onError
                        ) {},
                        sdp
                    )
                },
                onError = onError
            ) {},
            offerConstraints()
        )
    }

    fun setRemoteAnswer(
        answer: SessionDescription,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        peerConnection.setRemoteDescription(
            object : SdpObserverAdapter(
                onSet = {
                    remoteDescriptionSet = true
                    // flush anything that arrived early
                    pendingRemoteCandidates.forEach { peerConnection.addIceCandidate(it) }
                    pendingRemoteCandidates.clear()
                    onComplete()
                },
                onError = onError
            ) {},
            answer
        )
    }

    // -------------------------
    // CALLEE flow
    // -------------------------

    fun setRemoteOfferAndCreateLocalAnswer(
        offer: SessionDescription,
        onSuccess: (SessionDescription) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        peerConnection.setRemoteDescription(
            object : SdpObserverAdapter(
                onSet = {
                    remoteDescriptionSet = true
                    // now safe to create answer
                    peerConnection.createAnswer(
                        object : SdpObserverAdapter(
                            onCreate = { answer ->
                                peerConnection.setLocalDescription(
                                    object : SdpObserverAdapter(
                                        onSet = {
                                            // flush any early candidates (from caller) after we have remote set
                                            pendingRemoteCandidates.forEach { peerConnection.addIceCandidate(it) }
                                            pendingRemoteCandidates.clear()
                                            onSuccess(answer)
                                        },
                                        onError = onError
                                    ) {},
                                    answer
                                )
                            },
                            onError = onError
                        ) {},
                        answerConstraints()
                    )
                },
                onError = onError
            ) {},
            offer
        )
    }

    // -------------------------
    // Common during the call
    // -------------------------

    fun ensureRecvTransceivers() {
        if (audioTransceiver == null) {
            audioTransceiver = peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
        if (videoTransceiver == null) {
            videoTransceiver = peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates += candidate
            return
        }
        peerConnection.addIceCandidate(candidate)
    }

    fun setLocalVideoEnabled(enabled: Boolean) {
        try {
            localVideoTrack?.setEnabled(enabled)
        } catch (_: IllegalStateException) {
            // Track was disposed (e.g., camera stopped). Ignore.
        }
    }

    fun setLocalAudioEnabled(enabled: Boolean) {
        try {
            localAudioTrack?.setEnabled(enabled)
        } catch (_: IllegalStateException) {
            // Track disposed; ignore.
        }
    }

    fun close() {
        try { peerConnection.close() } catch (_: Throwable) {}
    }

    // -------------------------
    // Helpers
    // -------------------------

    private fun offerConstraints(): MediaConstraints = MediaConstraints()
    private fun answerConstraints(): MediaConstraints = MediaConstraints()

    private open class SdpObserverAdapter(
        private val onCreate: ((SessionDescription) -> Unit)? = null,
        private val onSet: (() -> Unit)? = null,
        private val onError: (Throwable) -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreate?.invoke(sdp) ?: Unit
        override fun onSetSuccess() = onSet?.invoke() ?: Unit
        override fun onCreateFailure(error: String) = onError(Throwable(error))
        override fun onSetFailure(error: String) = onError(Throwable(error))
    }

    fun getStats(onSummary: (String) -> Unit) {
        peerConnection.getStats { report ->
            val stats = report.statsMap

            // === 1) Selected transport & pair
            val transport = stats.values.firstOrNull { it.type == "transport" }
            val selPairId = (transport?.members?.get("selectedCandidatePairId") as? String)
                ?: stats.values.firstOrNull {
                    it.type == "candidate-pair" &&
                            ((it.members["nominated"] as? Boolean) == true) &&
                            ((it.members["state"] as? String) == "succeeded")
                }?.id

            var pairLine = ""
            var topoLine = ""
            if (selPairId != null) {
                val pair = stats[selPairId]
                val localId  = pair?.members?.get("localCandidateId")  as? String
                val remoteId = pair?.members?.get("remoteCandidateId") as? String
                val local  = localId?.let { stats[it]?.members } ?: emptyMap()
                val remote = remoteId?.let { stats[it]?.members } ?: emptyMap()

                val localType  = local["candidateType"] as? String ?: "?"
                val localProto = local["protocol"] as? String ?: "?"
                val remoteType  = remote["candidateType"] as? String ?: "?"
                val remoteProto = remote["protocol"] as? String ?: "?"

                val rttSec = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()
                val outBr  = (pair?.members?.get("availableOutgoingBitrate") as? Number)?.toDouble()

                pairLine = "PAIR=${selPairId} rtt=${if (rttSec != null) "%.3fs".format(rttSec) else "n/a"} " +
                        "outKbps=${if (outBr != null) ((outBr / 1000.0).toInt()) else -1}"
                topoLine = "LOCAL[$localType/$localProto] REMOTE[$remoteType/$remoteProto]"
            }

            // === 2) Media RTP stats
            var outVideoLine = ""
            var outAudioLine = ""
            var inVideoLine = ""
            var inAudioLine = ""

            stats.values.forEach { s ->
                when (s.type) {
                    "outbound-rtp" -> {
                        val kind = s.members["kind"] as? String
                        val br = (s.members["bytesSent"] as? Number)?.toDouble()
                        val ts = (s.members["timestamp"] as? Number)?.toDouble()
                        val fps = (s.members["framesPerSecond"] as? Number)?.toDouble()
                        val brKbps = if (br != null && ts != null) (br / 1000.0) else 0.0
                        if (kind == "video") outVideoLine = "OUT video≈${brKbps.toInt()} kB sent fps=${fps ?: "?"}"
                        if (kind == "audio") outAudioLine = "OUT audio≈${brKbps.toInt()} kB sent"
                    }
                    "inbound-rtp" -> {
                        val kind = s.members["kind"] as? String
                        val br = (s.members["bytesReceived"] as? Number)?.toDouble()
                        val loss = (s.members["packetsLost"] as? Number)?.toInt()
                        val jitter = (s.members["jitter"] as? Number)?.toDouble()
                        val brKbps = if (br != null) (br / 1000.0) else 0.0
                        if (kind == "video")
                            inVideoLine = "IN video≈${brKbps.toInt()} kB recv loss=${loss ?: 0} jitter=${"%.3f".format(jitter ?: 0.0)}"
                        if (kind == "audio")
                            inAudioLine = "IN audio≈${brKbps.toInt()} kB recv loss=${loss ?: 0} jitter=${"%.3f".format(jitter ?: 0.0)}"
                    }
                }
            }

            val summary = listOf(pairLine, topoLine, outVideoLine, outAudioLine, inVideoLine, inAudioLine)
                .filter { it.isNotBlank() }
                .joinToString(" | ")

            onSummary(summary)
        }
    }
}