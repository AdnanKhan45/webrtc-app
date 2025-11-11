package com.example.webrtcapp

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageButton
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
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.max
import android.widget.Chronometer
import com.google.firebase.firestore.FirebaseFirestore
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.example.webrtcapp.App



class CallActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_ROLE = "role" // "caller" or "callee"
        const val EXTRA_CALLEE_UID = "extra_callee_uid" // "caller" or "callee"
        const val EXTRA_CALLEE_NAME = "extra_callee_name"
        private const val TAG = "CallActivity"
    }


    // UI
    private lateinit var btnToggleMic: AppCompatImageButton
    private lateinit var btnToggleCamera: AppCompatImageButton
    private lateinit var btnFlipCamera: AppCompatImageButton
    private lateinit var btnEnd: AppCompatImageButton
    private lateinit var remoteNameText: TextView
    private lateinit var callTimer: Chronometer
    private lateinit var qualityDot: View

    private var remoteDisplayName: String = "Unknown"
    private var localDisplayName: String = ""

    private var timerStarted = false

    private lateinit var topOverlay: android.widget.LinearLayout
    private var topSafePx: Int = 0
    // Renderers
    private lateinit var primaryRenderer: SurfaceViewRenderer
    private lateinit var pipCard: com.google.android.material.card.MaterialCardView
    private lateinit var pipRenderer: SurfaceViewRenderer
    private var remoteVideoTrack: VideoTrack? = null

    // State for which feed is primary
    private var primaryShowsRemote = true

    private var lastIceState: PeerConnection.IceConnectionState? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var finishOnRedRunnable: Runnable? = null

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

        // Views
        btnToggleMic = findViewById(R.id.btnToggleMic)
        btnToggleCamera = findViewById(R.id.btnToggleCamera)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnEnd = findViewById(R.id.btnEnd)
        primaryRenderer = findViewById(R.id.primaryRenderer)
        pipCard = findViewById(R.id.pipCard)
        pipRenderer = findViewById(R.id.pipRenderer)

        remoteNameText = findViewById(R.id.remoteNameText)
        callTimer = findViewById(R.id.callTimer)
        qualityDot = findViewById(R.id.qualityDot)

        localDisplayName = intent.getStringExtra("displayName") ?: "Me"

        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: error("Missing callId")
        role = intent.getStringExtra(EXTRA_ROLE) ?: error("Missing role (caller|callee)")
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_UID) // may be null
        val calleeNameFromIntent = intent.getStringExtra(EXTRA_CALLEE_NAME) // may be null

        if (role == "caller") {
            val seed = calleeNameFromIntent?.takeIf { it.isNotBlank() } ?: "Callee"
            remoteNameText.text = seed
        }

        FirebaseFirestore.getInstance()
            .collection("calls")
            .document(callId)
            .addSnapshotListener { snap, _ ->
                val caller = (snap?.get("caller") as? Map<*, *>)
                val callee = (snap?.get("callee") as? Map<*, *>)
                val callerName = caller?.get("displayName") as? String
                val calleeName = callee?.get("displayName") as? String

                remoteDisplayName = when (role) {
                    "caller" -> calleeName ?: "Callee"
                    "callee" -> callerName ?: "Caller"
                    else -> "Peer"
                }
                remoteNameText.text = remoteDisplayName
            }

        val controls = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.callControls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val minBottom = (20 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = max(sys.bottom, minBottom))
            insets
        }

        val rtcDeps = (applicationContext as App).rtcDeps


        topOverlay = findViewById(R.id.topOverlay)
        ViewCompat.setOnApplyWindowInsetsListener(topOverlay) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val extra = (8 * resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, sys.top + extra, v.paddingRight, v.paddingBottom)
            insets
        }

        topOverlay.post {
            val statusTop = ViewCompat.getRootWindowInsets(topOverlay)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())
                ?.top ?: 0
            val margin = (12 * resources.displayMetrics.density).toInt()
            topSafePx = statusTop + topOverlay.height + margin
        }

        // PRIMARY (fullscreen) usually shows remote
        primaryRenderer.init(rtcDeps.eglBase.eglBaseContext, null)
        primaryRenderer.setMirror(false)

        // Local preview controller targets the PIP by default
        previewController = LocalPreviewController(applicationContext, rtcDeps)
        previewController.attachRenderer(pipRenderer, mirror = true, overlay = true)
        // Audio
        audioController = AudioController(rtcDeps)

        enablePipDragAndTap()

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

                override fun onRemoteTrack(receiver: org.webrtc.RtpReceiver, transceiver: org.webrtc.RtpTransceiver) {
                    val track = receiver.track()
                    if (track != null && track.kind() == "video") {
                        val vt = track as? VideoTrack ?: return
                        runOnUiThread {
                            remoteVideoTrack = vt

                            // Always (re)bind to the right target immediately
                            vt.removeSink(primaryRenderer)
                            vt.removeSink(pipRenderer)
                            if (primaryShowsRemote) {
                                vt.addSink(primaryRenderer)
                            } else {
                                vt.addSink(pipRenderer)
                            }
                            maybeStartTimer()
                        }
                    }
                }

                override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                    runOnUiThread {
                        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                            maybeStartTimer()
                        }
                        if (state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                            state == PeerConnection.PeerConnectionState.FAILED ||
                            state == PeerConnection.PeerConnectionState.CLOSED) {
                            stopTimerIfRunning()
                        }
                    }
                }

                // Colors: grey=idle, amber=connecting, green=connected, red=problem
                override fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
                    runOnUiThread {
                        lastIceState = state

                        // color the dot (keep your colors)
                        when (state) {
                            PeerConnection.IceConnectionState.NEW,
                            PeerConnection.IceConnectionState.CHECKING  -> setQualityColor(R.color.md_amber_500)
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> setQualityColor(R.color.md_green_500)
                            PeerConnection.IceConnectionState.DISCONNECTED -> setQualityColor(R.color.md_red_400)
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.CLOSED -> setQualityColor(R.color.md_red_500)
                            else -> setQualityColor(R.color.md_grey_500)
                        }

                        // start/stop the timer as you already do
                        if (state == PeerConnection.IceConnectionState.CONNECTED ||
                            state == PeerConnection.IceConnectionState.COMPLETED) {
                            cancelFinishOnRed()
                            maybeStartTimer()
                        }

                        // When we turn "red", schedule finishing the Activity
                        if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                            state == PeerConnection.IceConnectionState.FAILED ||
                            state == PeerConnection.IceConnectionState.CLOSED) {
                            stopTimerIfRunning()
                            scheduleFinishOnRed() // 1.2s grace; tweak if you want instant: scheduleFinishOnRed(0)
                        }
                    }
                }
            }
        )

        // Controls
        btnToggleMic.setOnClickListener {
            if (!hasPermission(RECORD_AUDIO)) {
                requestPerms.launch(arrayOf(CAMERA, RECORD_AUDIO))
                return@setOnClickListener
            }
            ensureAudioStarted()
            val newEnabled = audioController.toggle()
            isMicEnabled = newEnabled
            rtcClient.setLocalAudioEnabled(newEnabled)
            refreshIcons()
        }

        btnToggleCamera.setOnClickListener {
            isCameraEnabled = !isCameraEnabled
            if (isCameraEnabled) {
                previewController.resume()
            } else {
                previewController.pause()
            }
            rtcClient.setLocalVideoEnabled(isCameraEnabled)
            refreshIcons()
        }

        btnFlipCamera.setOnClickListener {
            isFront = !isFront
            previewController.switchCamera(isFront)
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
        cancelFinishOnRed()
        offerListener?.remove(); offerListener = null
        answerListener?.remove(); answerListener = null
        remoteCandsListener?.remove(); remoteCandsListener = null
        rtcClient.close()
        previewController.release()
        try { pipRenderer.release() } catch (_: Throwable) {}
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
            isCameraEnabled = false
        }
    }

    private fun startMic() {
        try {
            if (isMicEnabled && hasPermission(RECORD_AUDIO)) {
                if (audioController.trackOrNull() == null) audioController.start()
            }
        } catch (t: Throwable) {
            isMicEnabled = false
        }
    }

    private fun ensureAudioStarted() {
        try {
            if (audioController.trackOrNull() == null) audioController.start()
        } catch (t: Throwable) {
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
            ?: run { return }

        val me = CallRepository.Participant(
            uid = uid,
            displayName = localDisplayName.ifBlank { null }
        )

        when (role) {
            "caller" -> startAsCaller(me)
            "callee" -> startAsCallee(me)
        }
    }

    private fun startAsCaller(me: CallRepository.Participant) {
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_UID)
        val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME)

        // 1) Create the call with caller info
        repo.createCall(callId, me)

        // 2) Pre-fill callee info (so backend/push can use it)
        if (!calleeId.isNullOrBlank()) {
            repo.joinCall(
                callId,
                CallRepository.Participant(
                    uid = calleeId,
                    displayName = calleeName?.takeIf { it.isNotBlank() }
                )
            )
        }

        // 3) WebRTC offer flow
        rtcClient.ensureRecvTransceivers()
        rtcClient.createLocalOfferAndSet(
            onSuccess = { offer ->
                repo.setOffer(callId, offer)

                answerListener = repo.listenAnswer(
                    callId,
                    onAnswer = { answer ->
                        if (didSetRemoteAnswer) return@listenAnswer
                        didSetRemoteAnswer = true

                        rtcClient.setRemoteAnswer(
                            answer,
                            onComplete = {
                                answerListener?.remove(); answerListener = null
                            },
                            onError = { /* log if needed */ }
                        )
                    },
                    onError = { e -> logIndexHint(e) }
                )
            },
            onError = { e -> runOnUiThread { logIndexHint(e) } }
        )

        // 4) Remote ICE from callee
        remoteCandsListener = repo.listenRemoteCandidates(
            callId,
            from = "callee",
            onCandidate = { rtcClient.addRemoteIceCandidate(it) },
            onError = { e -> runOnUiThread { logIndexHint(e) } }
        )
    }

    private fun startAsCallee(me: CallRepository.Participant) {
        repo.joinCall(callId, me)

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
                        offerListener?.remove(); offerListener = null
                    },
                    onError = { e ->
                    }
                )
            },
            onError = { e -> logIndexHint(e) }
        )

        remoteCandsListener = repo.listenRemoteCandidates(
            callId,
            from = "caller",
            onCandidate = { rtcClient.addRemoteIceCandidate(it) },
            onError = { e -> runOnUiThread {

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

        stopTimerIfRunning()

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
        } else {
            Log.e(TAG, "Firestore error: ${t.message}", t)
        }
    }

    private fun enablePipDragAndTap() {
        var downX = 0f
        var downY = 0f
        var dX = 0f
        var dY = 0f
        var dragged = false
        val touchSlop = 8 * resources.displayMetrics.density

        pipCard.setOnTouchListener { v, event ->
            val parent = findViewById<FrameLayout>(R.id.callRoot)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    dragged = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val newX = event.rawX + dX
                    val newY = event.rawY + dY
                    if (!dragged && (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                                kotlin.math.abs(event.rawY - downY) > touchSlop)) {
                        dragged = true
                    }
                    // clamp within parent
                    val controlsH = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.callControls).height
                    val maxX = parent.width - v.width
                    val maxY = parent.height - v.height - controlsH
// keep PIP below top overlay safe area
                    v.x = newX.coerceIn(0f, maxX.toFloat())
                    v.y = newY.coerceIn(topSafePx.toFloat(), maxY.coerceAtLeast(topSafePx).toFloat())
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        // Treat as click -> swap
                        swapPrimaryAndPip()
                    } else {
                        snapPipToNearestCorner()
                    }
                    true
                }
                else -> false
            }
        }
        // Also allow tapping the card (for accessibility / keyboard)
        pipCard.setOnClickListener { swapPrimaryAndPip() }
    }

    private fun snapPipToNearestCorner() {
        val parent = findViewById<FrameLayout>(R.id.callRoot)
        val v = pipCard
        val margin = 12 * resources.displayMetrics.density
        val controlsH = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.callControls).height
        val topY = topSafePx.toFloat()
        val bottomY = parent.height - v.height - margin - controlsH

        val corners = listOf(
            margin to topY,                                 // top-start
            parent.width - v.width - margin to topY,       // top-end
            margin to bottomY,                              // bottom-start
            parent.width - v.width - margin to bottomY     // bottom-end
        )
        val current = android.graphics.PointF(v.x, v.y)
        val nearest = corners.minByOrNull { (cx, cy) ->
            val dx = current.x - cx
            val dy = current.y - cy
            dx*dx + dy*dy
        } ?: return
        v.animate()
            .x(nearest.first.coerceAtLeast(0f))
            .y(nearest.second.coerceAtLeast(0f))
            .setDuration(180)
            .start()
    }

    private fun swapPrimaryAndPip() {
        primaryShowsRemote = !primaryShowsRemote

        // 1) Remote: move sinks now
        remoteVideoTrack?.let { vt ->
            vt.removeSink(primaryRenderer)
            vt.removeSink(pipRenderer)
            if (primaryShowsRemote) vt.addSink(primaryRenderer) else vt.addSink(pipRenderer)
        }

        // 2) Local: move preview to the other renderer now
        if (primaryShowsRemote) {
            // Remote -> primary, Local -> pip
            previewController.movePreviewTo(pipRenderer, mirror = true)
            primaryRenderer.setMirror(false)
            pipRenderer.setMirror(true)
        } else {
            // Remote -> pip, Local -> primary
            previewController.movePreviewTo(primaryRenderer, mirror = isFront)
            primaryRenderer.setMirror(isFront)
            pipRenderer.setMirror(false)
        }
    }

    private fun maybeStartTimer() {
        if (!timerStarted) {
            callTimer.base = android.os.SystemClock.elapsedRealtime()
            callTimer.start()
            timerStarted = true
        }
    }

    private fun stopTimerIfRunning() {
        if (timerStarted) {
            try { callTimer.stop() } catch (_: Throwable) {}
            timerStarted = false
        }
    }

    private fun setQualityColor(@androidx.annotation.ColorRes colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        ViewCompat.setBackgroundTintList(qualityDot, ColorStateList.valueOf(color))
    }

    private fun scheduleFinishOnRed(delayMs: Long = 1200L) {
        cancelFinishOnRed()
        finishOnRedRunnable = Runnable {
            // Only finish if we're still "red"
            if (lastIceState == PeerConnection.IceConnectionState.DISCONNECTED ||
                lastIceState == PeerConnection.IceConnectionState.FAILED ||
                lastIceState == PeerConnection.IceConnectionState.CLOSED) {
                // Do not write anything to Firestore; just close locally
                stopTimerIfRunning()
                try { offerListener?.remove() } catch (_: Throwable) {}
                try { answerListener?.remove() } catch (_: Throwable) {}
                try { remoteCandsListener?.remove() } catch (_: Throwable) {}
                rtcClient.close()
                previewController.stop()
                audioController.stop()
                if (!isFinishing && !isDestroyed) finish()
            }
        }.also { uiHandler.postDelayed(it, delayMs) }
    }

    private fun cancelFinishOnRed() {
        finishOnRedRunnable?.let { uiHandler.removeCallbacks(it) }
        finishOnRedRunnable = null
    }
}