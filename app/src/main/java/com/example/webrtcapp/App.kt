package com.example.webrtcapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.getSystemService
import com.example.webrtcapp.push.PushKeys
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.example.webrtcapp.webrtc.RtcDependencies


class App : Application() {

    lateinit var rtcDeps: RtcDependencies
        private set

    override fun onCreate() {
        super.onCreate()

        rtcDeps = RtcDependencies(applicationContext)

        // 1) Firebase init (safe if already initialized)
        try { FirebaseApp.initializeApp(this) } catch (_: Throwable) { /* no-op */ }

        // 2) Ensure FCM auto-init stays on (so token refresh events arrive)
        try { FirebaseMessaging.getInstance().isAutoInitEnabled = true } catch (_: Throwable) {}

        // 3) Create notification channels used by our ringing + ongoing-call flows
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm: NotificationManager = getSystemService() ?: return

        // Fresh silent ring channel (heads-up + fullscreen; app plays sound itself)
        val ring = NotificationChannel(
            PushKeys.CHANNEL_RING,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call alerts (fullscreen; app controls ringtone)"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableLights(true)
            enableVibration(false)     // vibration handled by our service
            setSound(null, null)       // <- SILENT to avoid double audio
            if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
            if (Build.VERSION.SDK_INT >= 28) setBypassDnd(true)
        }

        val ongoing = NotificationChannel(
            PushKeys.CHANNEL_ONGOING,
            "Ongoing Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "While an active call is in progress"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannel(ring)
        nm.createNotificationChannel(ongoing)
    }
}