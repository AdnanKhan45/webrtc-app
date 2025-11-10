package com.example.webrtcapp.push

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.webrtcapp.call.CallRingingService
import com.example.webrtcapp.data.UserRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives FCM *data* messages.
 *
 * Responsibilities:
 *  - Parse payload (strictly data keys defined in PushKeys).
 *  - Drop expired/noisy messages (basic dedupe window via EXPIRES_AT_MS).
 *  - Start/stop CallRingingService with the right Intent action.
 *  - Persist refreshed FCM token via onNewToken().
 *
 * Never shows notifications itself; the foreground CallRingingService owns UI/notification.
 */
class IncomingPushService : FirebaseMessagingService() {

    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data ?: return

        // Type: accept either your constant or "incoming_call"
        val type = data[PushKeys.KEY_TYPE] ?: data["type"] ?: return
        val isInvite = (type == PushKeys.TYPE_CALL_INVITE) ||
                (type == PushKeys.TYPE_INCOMING)
        if (!isInvite) return

        // Keys: normalize both variants
        val callId     = data[PushKeys.KEY_CALL_ID] ?: data["callId"] ?: return
        val callerUid  = data[PushKeys.KEY_CALLER_UID] ?: data["callerUid"] ?: data["fromUid"] ?: ""
        val callerName = data[PushKeys.KEY_CALLER_NAME] ?: data["callerName"] ?: data["fromName"] ?: "Unknown"
        val calleeUid  = data[PushKeys.KEY_CALLEE_UID] ?: data["calleeUid"] ?: ""
        val ts         = data[PushKeys.KEY_TIMESTAMP]  ?: data["ts"] ?: ""

        val intent = Intent(this, com.example.webrtcapp.call.CallRingingService::class.java).apply {
            action = com.example.webrtcapp.call.CallRingingService.ACTION_START_RING
            putExtra(PushKeys.EXTRA_CALL_ID, callId)
            putExtra(PushKeys.EXTRA_CALLER_UID, callerUid)
            putExtra(PushKeys.EXTRA_CALLER_NAME, callerName)
            putExtra(PushKeys.EXTRA_CALLEE_UID, calleeUid)
            putExtra(PushKeys.EXTRA_TIMESTAMP, ts)
            putExtra(PushKeys.EXTRA_FROM_PUSH, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun handleIncomingCall(data: Map<String, String>) {
        val callId     = data[PushKeys.CALL_ID]
        val callerUid  = data[PushKeys.CALLER_UID] ?: ""
        val callerName = data[PushKeys.CALLER_NAME] ?: "Unknown"
        val ringMs     = data[PushKeys.RING_MS]?.toLongOrNull() ?: DEFAULT_RING_MS
        val expiresAt  = data[PushKeys.EXPIRES_AT_MS]?.toLongOrNull()

        // Discard stale
        if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
            Log.i(TAG, "Dropping expired incoming_call for callId=$callId")
            return
        }
        if (callId.isNullOrBlank()) {
            Log.w(TAG, "Missing callId in incoming_call payload")
            return
        }

        // Hand off to the foreground ringing service (creates high-priority notification + sound)
        val intent = Intent(this, CallRingingService::class.java).apply {
            action = PushKeys.ACTION_START_RING
            putExtra(PushKeys.EXTRA_CALL_ID, callId)
            putExtra(PushKeys.EXTRA_CALLER_UID, callerUid)
            putExtra(PushKeys.EXTRA_CALLER_NAME, callerName)
            putExtra(PushKeys.EXTRA_RING_MS, ringMs)
        }
        // Ensure the service is (re)started even if app process was dead
        ContextCompat.startForegroundService(this, intent)
        Log.i(TAG, "START_RING sent to CallRingingService for callId=$callId, from=$callerName/$callerUid")
    }

    private fun handleCancel(data: Map<String, String>) {
        val callId = data[PushKeys.CALL_ID]
        if (callId.isNullOrBlank()) {
            Log.w(TAG, "Missing callId in cancel_call payload")
            return
        }

        val intent = Intent(this, CallRingingService::class.java).apply {
            action = PushKeys.ACTION_STOP_RING
            putExtra(PushKeys.EXTRA_CALL_ID, callId)
        }
        ContextCompat.startForegroundService(this, intent)
        Log.i(TAG, "STOP_RING sent to CallRingingService for callId=$callId (remote cancelled)")
    }

    /**
     * FCM token rotated by Google Play services / FCM.
     * We upsert it into Firestore so the backend always targets the latest token.
     */
    override fun onNewToken(token: String) {
        Log.i(TAG, "onNewToken($token)")
        ioScope.launch {
            try {
                // Preserve existing displayName; only update the token.
                UserRepository().upsertCurrentUser(displayName = null, deviceToken = token)
                Log.i(TAG, "FCM token saved to Firestore")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to save FCM token", t)
            }
        }
    }

    companion object {
        private const val TAG = "IncomingPushService"
        private const val DEFAULT_RING_MS = 30_000L
    }
}