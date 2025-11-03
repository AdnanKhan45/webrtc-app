package com.example.webrtcapp

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import com.example.webrtcapp.webrtc.AudioController
import com.example.webrtcapp.webrtc.LocalPreviewController
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    // UI refs
    private lateinit var statusText: TextView
    private lateinit var localPreview: SurfaceViewRenderer

    private lateinit var btnToggleMic: AppCompatImageButton
    private lateinit var btnToggleCamera: AppCompatImageButton
    private lateinit var btnFlipCamera: AppCompatImageButton
    private lateinit var btnEnd: AppCompatImageButton

    // WebRTC
    private lateinit var previewController: LocalPreviewController
    private lateinit var audioController: AudioController

    // Simple UI state
    private var isFront = true
    private var isCameraEnabled = true
    private var isMicEnabled = true   // now backed by AudioController

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[CAMERA] == true
            val micGranted = grants[RECORD_AUDIO] == true

            if (cameraGranted) {
                startPreviewSafely()
            }
            if (micGranted && isMicEnabled) {
                // Start mic only if user intends mic ON
                ensureAudioStarted()
            }

            statusText.text = when {
                cameraGranted && micGranted -> "Permissions granted. Starting preview/audio…"
                cameraGranted && !micGranted -> "Camera granted (mic denied). Preview started."
                else -> "Camera permission denied. Cannot start preview."
            }
            refreshIcons()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ----- views -----
        statusText = findViewById(R.id.statusText)
        localPreview = findViewById(R.id.localPreview)
        btnToggleMic = findViewById(R.id.btnToggleMic)
        btnToggleCamera = findViewById(R.id.btnToggleCamera)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnEnd = findViewById(R.id.btnEnd)

        // ----- deps -----
        val rtcDeps = (applicationContext as App).rtcDeps
        previewController = LocalPreviewController(applicationContext, rtcDeps)
        previewController.attachRenderer(localPreview, mirror = true)
        audioController = AudioController(rtcDeps)

        // ----- listeners -----
        btnToggleMic.setOnClickListener {
            // If no permission, request it first
            if (!hasMicPermission()) {
                requestPermissions.launch(arrayOf(CAMERA, RECORD_AUDIO))
                statusText.text = "Requesting microphone permission…"
                return@setOnClickListener
            }

            // Ensure audio is started, then toggle enabled state
            ensureAudioStarted()
            isMicEnabled = audioController.toggle()
            statusText.text = if (isMicEnabled) "Mic: ON" else "Mic: OFF"
            refreshIcons()
        }

        btnToggleCamera.setOnClickListener {
            isCameraEnabled = !isCameraEnabled
            if (isCameraEnabled) {
                startPreviewSafely()
                statusText.text = "Camera: ON"
            } else {
                previewController.stop()
                statusText.text = "Camera: OFF"
            }
            refreshIcons()
        }

        btnFlipCamera.setOnClickListener {
            isFront = !isFront
            previewController.stop()
            previewController.attachRenderer(localPreview, mirror = isFront)
            startPreviewSafely()
            statusText.text = if (isFront) "Using FRONT camera" else "Using BACK camera"
        }

        btnEnd.setOnClickListener {
            // Treat as “end session” locally: stop video + audio
            previewController.stop()
            audioController.stop()
            isCameraEnabled = false
            isMicEnabled = false
            statusText.text = "Preview/audio ended."
            refreshIcons()
        }

        // Ask permissions / start preview + (optionally) audio
        ensurePermissionsThenStart()
        refreshIcons()
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission() && isCameraEnabled) {
            startPreviewSafely()
        }
        if (hasMicPermission() && isMicEnabled) {
            ensureAudioStarted()
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop capture when not visible; restart in onStart
        previewController.stop()
        audioController.stop()
        statusText.text = "Preview/audio stopped (app not visible)."
    }

    override fun onDestroy() {
        super.onDestroy()
        previewController.release()
        audioController.stop()
    }

    // ---------- helpers ----------

    private fun ensurePermissionsThenStart() {
        val needsCamera = !hasCameraPermission()
        val needsMic = !hasMicPermission()
        if (needsCamera || needsMic) {
            requestPermissions.launch(arrayOf(CAMERA, RECORD_AUDIO))
            statusText.text = "Requesting permissions…"
        } else {
            // Both granted already
            if (isCameraEnabled) startPreviewSafely()
            if (isMicEnabled) ensureAudioStarted()
            statusText.text = "Ready."
        }
    }

    private fun startPreviewSafely() {
        try {
            if (isCameraEnabled && hasCameraPermission()) {
                previewController.start(front = isFront, width = 1280, height = 720, fps = 30)
                statusText.text = "Local preview running."
            }
        } catch (t: Throwable) {
            statusText.text = "Failed to start preview: ${t.message}"
        }
    }

    private fun ensureAudioStarted() {
        try {
            // Start the track if it's not started yet; do NOT change enabled state here.
            if (audioController.trackOrNull() == null) {
                audioController.start()
            }
        } catch (t: Throwable) {
            statusText.text = "Failed to start audio: ${t.message}"
            isMicEnabled = false
        }
    }

    /**
     * Swap icons to reflect current state.
     * Make sure you have these drawables:
     *   ic_mic, ic_mic_off
     *   ic_videocam (or ic_video_cam), ic_videocam_off (or ic_video_cam_off)
     */
    private fun refreshIcons() {
        // Mic
        btnToggleMic.setImageResource(
            if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
        )

        // Camera (handle both naming variants)
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

    private fun hasCameraPermission(): Boolean =
        checkSelfPermissionCompat(CAMERA)

    private fun hasMicPermission(): Boolean =
        checkSelfPermissionCompat(RECORD_AUDIO)

    private fun checkSelfPermissionCompat(permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
}