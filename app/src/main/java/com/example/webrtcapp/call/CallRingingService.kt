package com.example.webrtcapp.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.webrtcapp.CallActivity
import com.example.webrtcapp.R
import com.example.webrtcapp.push.PushKeys

class CallRingingService : Service() {

    companion object {
        const val ACTION_START_RING = "webrtcapp.ring.START"
        const val ACTION_ACCEPT     = "webrtcapp.ring.ACCEPT"
        const val ACTION_DECLINE    = "webrtcapp.ring.DECLINE"

        private const val WAKELOCK_TAG = "webrtcapp:IncomingCallWakelock"
        private const val TIMEOUT_MS   = 40_000L
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var callId: String? = null
    private var callerUid: String? = null
    private var callerName: String? = null
    private var calleeUid: String? = null
    private var timestamp: String? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RING -> {
                extractExtras(intent)
                startAsForeground()
                startRingingStack()
                scheduleTimeout()
            }
            ACTION_ACCEPT -> {
                extractExtras(intent) // ensure we still have the payload
                stopRingingStack()
                launchCallAsCallee()
                stopSelfSafely()
            }
            ACTION_DECLINE -> {
                extractExtras(intent)
                stopRingingStack()
                stopSelfSafely()
            }
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelTimeout()
        stopRingingStack()
        super.onDestroy()
    }

    private fun startAsForeground() {
        fun pack(i: Intent) = i.apply {
            putExtra(PushKeys.EXTRA_CALL_ID, callId)
            putExtra(PushKeys.EXTRA_CALLER_UID, callerUid)
            putExtra(PushKeys.EXTRA_CALLER_NAME, callerName)
            putExtra(PushKeys.EXTRA_CALLEE_UID, calleeUid)
            putExtra(PushKeys.EXTRA_TIMESTAMP, timestamp)
        }

        val fullScreenPi = PendingIntent.getActivity(
            this, 1001,
            pack(Intent(this, com.example.webrtcapp.IncomingCallActivity::class.java)),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        // Accept goes to CallActionActivity (Activity, not Service)
        val acceptPi = PendingIntent.getActivity(
            this, 1002,
            pack(Intent(this, CallActionActivity::class.java).apply {
                putExtra(CallActionActivity.EXTRA_ACTION, "accept")
            }),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        // Decline goes to CallActionActivity too
        val declinePi = PendingIntent.getActivity(
            this, 1003,
            pack(Intent(this, CallActionActivity::class.java).apply {
                putExtra(CallActionActivity.EXTRA_ACTION, "decline")
            }),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        val notif: Notification = NotificationCompat.Builder(this, PushKeys.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.incoming_call_from, callerName ?: "Unknown"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_call_answer, getString(R.string.action_accept), acceptPi)
            .addAction(R.drawable.ic_call_end, getString(R.string.action_decline), declinePi)
            .setFullScreenIntent(fullScreenPi, true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                PushKeys.NOTIF_ID_RING, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(PushKeys.NOTIF_ID_RING, notif)
        }
        acquireWakelock()
    }

    private fun updateForegroundText(extraLine: String) {
        val text = getString(R.string.incoming_call_from, callerName ?: "Unknown") + "  •  " + extraLine

        // keep extras in update actions too
        fun pack(i: Intent) = i.apply {
            putExtra(PushKeys.EXTRA_CALL_ID, callId)
            putExtra(PushKeys.EXTRA_CALLER_UID, callerUid)
            putExtra(PushKeys.EXTRA_CALLER_NAME, callerName)
            putExtra(PushKeys.EXTRA_CALLEE_UID, calleeUid)
            putExtra(PushKeys.EXTRA_TIMESTAMP, timestamp)
        }

        val replayPi = PendingIntent.getService(
            this, 1002, pack(Intent(this, CallRingingService::class.java).apply { action = ACTION_ACCEPT }),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val declinePi = PendingIntent.getService(
            this, 1003, pack(Intent(this, CallRingingService::class.java).apply { action = ACTION_DECLINE }),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        val fullPi = PendingIntent.getActivity(
            this, 1001, pack(Intent(this, com.example.webrtcapp.IncomingCallActivity::class.java)),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )

        val notif = NotificationCompat.Builder(this, PushKeys.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullPi, true)
            .addAction(R.drawable.ic_call_answer, getString(R.string.action_accept), replayPi)
            .addAction(R.drawable.ic_call_end, getString(R.string.action_decline), declinePi)
            .build()

        try { NotificationManagerCompat.from(this).notify(PushKeys.NOTIF_ID_RING, notif) } catch (_: SecurityException) {}
    }

    private fun startRingingStack() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()

        focusRequest = if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
        } else null

        if (Build.VERSION.SDK_INT >= 26) {
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= 21) audioAttributes = attrs
            play()
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val effect = if (Build.VERSION.SDK_INT >= 26) {
            VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), 0)
        } else null

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 500, 300, 500), 0)
        }

        updateForegroundText(getString(R.string.status_ringing))
    }

    private fun stopRingingStack() {
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Throwable) {}
        vibrator = null
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
        audioManager = null
        releaseWakelock()
    }

    private fun acquireWakelock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val level = PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 34) level else level or PowerManager.ACQUIRE_CAUSES_WAKEUP
            wakeLock = pm.newWakeLock(flags, WAKELOCK_TAG).apply {
                setReferenceCounted(false); acquire(60_000L)
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakelock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun launchCallAsCallee() {
        val id = callId ?: return
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_ID, id)
            putExtra(CallActivity.EXTRA_ROLE, "callee")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun stopSelfSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    private fun extractExtras(intent: Intent) {
        callId     = intent.getStringExtra(PushKeys.EXTRA_CALL_ID) ?: callId
        callerUid  = intent.getStringExtra(PushKeys.EXTRA_CALLER_UID) ?: callerUid
        callerName = intent.getStringExtra(PushKeys.EXTRA_CALLER_NAME) ?: callerName
        calleeUid  = intent.getStringExtra(PushKeys.EXTRA_CALLEE_UID) ?: calleeUid
        timestamp  = intent.getStringExtra(PushKeys.EXTRA_TIMESTAMP) ?: timestamp
    }

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun scheduleTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            updateForegroundText(getString(R.string.status_missed))
            stopRingingStack()
            stopSelfSafely()
        }.also { handler.postDelayed(it, TIMEOUT_MS) }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}