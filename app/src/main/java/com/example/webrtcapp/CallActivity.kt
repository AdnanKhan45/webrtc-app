package com.example.webrtcapp

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import com.example.webrtcapp.App
import com.example.webrtcapp.R
import com.example.webrtcapp.signaling.CallRepository
import com.example.webrtcapp.webrtc.AudioController
import com.example.webrtcapp.webrtc.LocalPreviewController
import com.example.webrtcapp.webrtc.RtcClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import android.util.Log

class CallActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_ROLE = "role" // "caller" or "callee"
        private const val TAG = "CallActivity"
    }


    // UI
    private lateinit var statusText: TextView
    private lateinit var btnToggleMic: AppCompatImageButton
    private lateinit var btnToggleCamera: AppCompatImageButton
    private lateinit var btnFlipCamera: AppCompatImageButton
    private lateinit var btnEnd: AppCompatImageButton
    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var remoteRenderer: SurfaceViewRenderer

    // Media & RTC
    private lateinit var previewController: LocalPreviewController
    private lateinit var audioController: AudioController
    private lateinit var rtcClient: RtcClient

    // Signaling
    private val repo = CallRepository()
    private var offerListener: ListenerRegistration? = null
    private var answerListener: ListenerRegistration? = null
    private var remoteCandsListener: ListenerRegistration? = null

    private var didSetRemoteOffer = false
    private var didSetRemoteAnswer = false

    // Args + state
    private lateinit var callId: String
    private lateinit var role: String
    private var isFront = true
    private var isCameraEnabled = true
    private var isMicEnabled = true

    // Permissions
    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[CAMERA] == true
            val micGranted = grants[RECORD_AUDIO] == true
            if (cameraGranted) startCamera()
            if (micGranted && isMicEnabled) startMic()
            attachTracksIfReady()
            if (cameraGranted || micGranted) startSignalingIfReady()
            refreshIcons()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: error("Missing callId")
        role = intent.getStringExtra(EXTRA_ROLE) ?: error("Missing role (caller|callee)")

        // Views
        statusText = findViewById(R.id.statusText)
        btnToggleMic = findViewById(R.id.btnToggleMic)
        btnToggleCamera = findViewById(R.id.btnToggleCamera)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnEnd = findViewById(R.id.btnEnd)
        localRenderer = findViewById(R.id.localRenderer)
        remoteRenderer = findViewById(R.id.remoteRenderer)

        val rtcDeps = (applicationContext as App).rtcDeps

        // Local preview controller
        previewController = LocalPreviewController(applicationContext, rtcDeps)
        previewController.attachRenderer(localRenderer, mirror = true)

        // Prepare remote renderer explicitly
        remoteRenderer.init(rtcDeps.eglBase.eglBaseContext, null)
        remoteRenderer.setMirror(false)

        // Audio
        audioController = AudioController(rtcDeps)

        // PeerConnection
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        rtcClient = RtcClient(
            appContext = this,
            deps = rtcDeps,
            rtcConfig = rtcConfig,
            listener = object : RtcClient.Listener {
                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    repo.addLocalCandidate(callId, role, candidate)
                }

                override fun onRemoteTrack(
                    receiver: org.webrtc.RtpReceiver,
                    transceiver: org.webrtc.RtpTransceiver
                ) {
                    val track = receiver.track()
                    if (track != null && track.kind() == "video") {
                        (track as? VideoTrack)?.addSink(remoteRenderer)
                        runOnUiThread { statusText.text = "Remote video" }
                    } else {
                        runOnUiThread { statusText.text = "Remote ${track?.kind() ?: "track"}" }
                    }
                }

                override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                    runOnUiThread { statusText.text = "PC: $state" }
                }

                override fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
                    runOnUiThread { statusText.text = "ICE: $state" }
                }
            }
        )

        // Controls
        btnToggleMic.setOnClickListener {
            if (!hasPermission(RECORD_AUDIO)) {
                requestPerms.launch(arrayOf(CAMERA, RECORD_AUDIO))
                statusText.text = "Requesting microphone permission…"
                return@setOnClickListener
            }
            ensureAudioStarted()
            val newEnabled = audioController.toggle()
            isMicEnabled = newEnabled
            rtcClient.setLocalAudioEnabled(newEnabled)
            statusText.text = if (newEnabled) "Mic: ON" else "Mic: OFF"
            refreshIcons()
        }

        btnToggleCamera.setOnClickListener {
            isCameraEnabled = !isCameraEnabled
            if (isCameraEnabled) {
                previewController.resume()
                statusText.text = "Camera: ON"
            } else {
                previewController.pause()
                statusText.text = "Camera: OFF"
            }
            rtcClient.setLocalVideoEnabled(isCameraEnabled)
            refreshIcons()
        }

        btnFlipCamera.setOnClickListener {
            isFront = !isFront
            previewController.switchCamera(isFront)
            statusText.text = if (isFront) "Using FRONT camera" else "Using BACK camera"
        }

        btnEnd.setOnClickListener { hangup("local_hangup") }

        // Start
        ensurePermissionsAndStart()
        refreshIcons()
    }

    override fun onStop() {
        super.onStop()
        previewController.stop()
        audioController.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        offerListener?.remove(); offerListener = null
        answerListener?.remove(); answerListener = null
        remoteCandsListener?.remove(); remoteCandsListener = null
        rtcClient.close()
        previewController.release()
        try { remoteRenderer.release() } catch (_: Throwable) {}
    }

    // ---------- permissions & media ----------

    private fun ensurePermissionsAndStart() {
        val needsCamera = !hasPermission(CAMERA)
        val needsMic = !hasPermission(RECORD_AUDIO)
        if (needsCamera || needsMic) {
            requestPerms.launch(arrayOf(CAMERA, RECORD_AUDIO))
        } else {
            startCamera()
            startMic()
            attachTracksIfReady()
            startSignalingIfReady()
        }
    }

    private fun startCamera() {
        try {
            if (isCameraEnabled && hasPermission(CAMERA)) {
                previewController.start(front = isFront, width = 1280, height = 720, fps = 30)
            }
        } catch (t: Throwable) {
            statusText.text = "Camera error: ${t.message}"
        }
    }

    private fun startMic() {
        try {
            if (isMicEnabled && hasPermission(RECORD_AUDIO)) {
                if (audioController.trackOrNull() == null) audioController.start()
            }
        } catch (t: Throwable) {
            statusText.text = "Mic error: ${t.message}"
            isMicEnabled = false
        }
    }

    private fun ensureAudioStarted() {
        try {
            if (audioController.trackOrNull() == null) audioController.start()
        } catch (t: Throwable) {
            statusText.text = "Mic error: ${t.message}"
            isMicEnabled = false
        }
    }

    private fun attachTracksIfReady() {
        val audio = audioController.trackOrNull()
        val video = previewController.videoTrackOrNull()
        if (audio != null || video != null) {
            rtcClient.attachLocalTracks(audio = audio, video = video)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    // ---------- signaling flow ----------

    private fun startSignalingIfReady() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: run { statusText.text = "Not signed in"; return }

        val me = CallRepository.Participant(uid)

        when (role) {
            "caller" -> startAsCaller(me)
            "callee" -> startAsCallee(me)
            else -> statusText.text = "Invalid role: $role"
        }
    }

    private fun startAsCaller(me: CallRepository.Participant) {
        repo.createCall(callId, me)
        statusText.text = "Calling… ($callId)"

        rtcClient.ensureRecvTransceivers()
        rtcClient.createLocalOfferAndSet(
            onSuccess = { offer: SessionDescription ->
                repo.setOffer(callId, offer)
                answerListener = repo.listenAnswer(
                    callId,
                    onAnswer = { answer ->
                        if (didSetRemoteAnswer) return@listenAnswer  // guard duplicate
                        didSetRemoteAnswer = true

                        rtcClient.setRemoteAnswer(
                            answer,
                            onComplete = {
                                runOnUiThread { statusText.text = "Answered" }
                                answerListener?.remove(); answerListener = null
                            },
                            onError = { e ->
                                runOnUiThread { statusText.text = "setRemoteAnswer error: ${e.message}" }
                            }
                        )
                    },
                    onError = { e -> logIndexHint(e) }
                )
            },
            onError = { e -> runOnUiThread {
                statusText.text = "Offer error: ${e.message}"
                logIndexHint(e)

            } }
        )

        remoteCandsListener = repo.listenRemoteCandidates(
            callId,
            from = "callee",
            onCandidate = { rtcClient.addRemoteIceCandidate(it) },
            onError = { e -> runOnUiThread { statusText.text = "Cand listen err: ${e.message}"

                logIndexHint(e)} }
        )
    }

    private fun startAsCallee(me: CallRepository.Participant) {
        repo.joinCall(callId, me)
        statusText.text = "Waiting for offer… ($callId)"

        offerListener = repo.listenOffer(
            callId,
            onOffer = { offer ->
                if (didSetRemoteOffer) return@listenOffer  // guard duplicate
                didSetRemoteOffer = true

                rtcClient.ensureRecvTransceivers()
                rtcClient.setRemoteOfferAndCreateLocalAnswer(
                    offer = offer,
                    onSuccess = { answer ->
                        repo.setAnswer(callId, answer)
                        runOnUiThread { statusText.text = "Answered" }
                        offerListener?.remove(); offerListener = null
                    },
                    onError = { e ->
                        runOnUiThread { statusText.text = "Answer error: ${e.message}" }
                    }
                )
            },
            onError = { e -> logIndexHint(e) }
        )

        remoteCandsListener = repo.listenRemoteCandidates(
            callId,
            from = "caller",
            onCandidate = { rtcClient.addRemoteIceCandidate(it) },
            onError = { e -> runOnUiThread { statusText.text = "Cand listen err: ${e.message}"

                logIndexHint(e)} }
        )
    }

    // ---------- icons ----------

    private fun refreshIcons() {
        // Mic
        btnToggleMic.setImageResource(
            if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
        )
        // Camera (support your two naming variants)
        val on1 = resources.getIdentifier("ic_videocam", "drawable", packageName)
        val off1 = resources.getIdentifier("ic_videocam_off", "drawable", packageName)
        val onAlt = resources.getIdentifier("ic_video_cam", "drawable", packageName)
        val offAlt = resources.getIdentifier("ic_video_cam_off", "drawable", packageName)
        when {
            on1 != 0 && off1 != 0 ->
                btnToggleCamera.setImageResource(if (isCameraEnabled) on1 else off1)
            onAlt != 0 && offAlt != 0 ->
                btnToggleCamera.setImageResource(if (isCameraEnabled) onAlt else offAlt)
        }
    }

    // ---------- hangup ----------

    private fun hangup(reason: String?) {
        offerListener?.remove(); offerListener = null
        answerListener?.remove(); answerListener = null
        remoteCandsListener?.remove(); remoteCandsListener = null

        repo.endCall(callId, reason)
        rtcClient.close()
        previewController.stop()
        audioController.stop()
        finish()
    }

    // Extract and print the console URL Firestore includes in FAILED_PRECONDITION errors
    private fun logIndexHint(t: Throwable) {
        val msg = t.message ?: return
        // The message Firestore throws contains a console URL you can click to create the index.
        val url = Regex("(https://console\\.firebase\\.google\\.com/\\S+)").find(msg)?.value
        if (url != null) {
            Log.e(TAG, "Firestore needs a composite index. Tap this: $url")
            runOnUiThread { statusText.text = "Firestore index required (see Logcat)" }
        } else {
            Log.e(TAG, "Firestore error: ${t.message}", t)
            runOnUiThread { statusText.text = "Firestore error: ${t.message}" }
        }
    }
}