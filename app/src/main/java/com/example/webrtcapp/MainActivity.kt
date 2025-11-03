package com.example.webrtcapp

import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
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

    // Simple UI state
    private var isFront = true
    private var isCameraEnabled = true
    private var isMicEnabled = true // stub until we add real audio capture

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraGranted = grants[CAMERA] == true
            val micGranted = grants[RECORD_AUDIO] == true
            if (cameraGranted) {
                statusText.text = if (micGranted) {
                    "Permissions granted. Starting preview…"
                } else {
                    "Camera granted (mic denied). Starting preview…"
                }
                startPreviewSafely()
                refreshIcons()
            } else {
                statusText.text = "Camera permission denied. Cannot start preview."
            }
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

        // ----- listeners -----
        btnToggleMic.setOnClickListener {
            // UI-only for now; we’ll wire to real audio track later.
            isMicEnabled = !isMicEnabled
            statusText.text = if (isMicEnabled) "Mic: ON (stub)" else "Mic: OFF (stub)"
            refreshIcons()
        }

        btnToggleCamera.setOnClickListener {
            // Toggle camera: stop/start the capturer.
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
            // Flip: stop capture then restart with opposite facing, and adjust mirroring.
            isFront = !isFront
            previewController.stop()
            previewController.attachRenderer(localPreview, mirror = isFront)
            startPreviewSafely()
            statusText.text = if (isFront) "Using FRONT camera" else "Using BACK camera"
        }

        btnEnd.setOnClickListener {
            // For now: end preview (later this will end a call).
            previewController.stop()
            statusText.text = "Preview ended."
            isCameraEnabled = false
            refreshIcons()
        }

        // Ask permissions / start preview
        ensurePermissionsThenStart()
        refreshIcons()
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission() && isCameraEnabled) {
            startPreviewSafely()
        }
    }

    override fun onStop() {
        super.onStop()
        previewController.stop()
        statusText.text = "Preview stopped (app not visible)."
    }

    override fun onDestroy() {
        super.onDestroy()
        previewController.release()
    }

    // ---------- helpers ----------

    private fun ensurePermissionsThenStart() {
        if (hasCameraPermission() && hasMicPermission()) {
            statusText.text = "Permissions already granted. Starting preview…"
            startPreviewSafely()
        } else {
            requestPermissions.launch(arrayOf(CAMERA, RECORD_AUDIO))
            statusText.text = "Requesting permissions…"
        }
    }

    private fun startPreviewSafely() {
        try {
            if (isCameraEnabled) {
                previewController.start(front = isFront, width = 1280, height = 720, fps = 30)
                statusText.text = "Local preview running."
            }
        } catch (t: Throwable) {
            statusText.text = "Failed to start preview: ${t.message}"
        }
    }

    /**
     * Swap icons to reflect current state.
     * Make sure you have these drawables imported:
     *  - ic_mic, ic_mic_off
     *  - ic_videocam (or ic_video_cam), ic_videocam_off (or ic_video_cam_off)
     */
    private fun refreshIcons() {
        // Mic
        btnToggleMic.setImageResource(
            if (isMicEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off
        )

        // Camera
        // If your asset is ic_video_cam(_off) instead of ic_videocam(_off), change references accordingly.
        val camOn = resources.getIdentifier("ic_videocam", "drawable", packageName)
        val camOff = resources.getIdentifier("ic_videocam_off", "drawable", packageName)
        if (camOn != 0 && camOff != 0) {
            btnToggleCamera.setImageResource(if (isCameraEnabled) camOn else camOff)
        } else {
            // Fallback to alternative naming
            val altOn = resources.getIdentifier("ic_video_cam", "drawable", packageName)
            val altOff = resources.getIdentifier("ic_video_cam_off", "drawable", packageName)
            if (altOn != 0 && altOff != 0) {
                btnToggleCamera.setImageResource(if (isCameraEnabled) altOn else altOff)
            }
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