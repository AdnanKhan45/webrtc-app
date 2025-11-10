package com.example.webrtcapp.call

import android.os.Bundle
import com.example.webrtcapp.push.PushKeys

/**
 * Process-local store for the *current* incoming call.
 * - Keeps only one call at a time (latest wins).
 * - Thread-safe (synchronized + @Volatile).
 * - In-memory only; survives while the app process is alive.
 *
 * Typical flow:
 *  1) IncomingPushService receives FCM -> CurrentCallStore.updateFromExtras(extras)
 *  2) CallRingingService queries CurrentCallStore.get() to show notification/FSI
 *  3) IncomingCallActivity reads again (for UI), then Accept/Decline -> CurrentCallStore.clear()
 */
object CurrentCallStore {

    data class IncomingCall(
        val callId: String,
        val callerUid: String? = null,
        val callerName: String? = null,
        /** When this record was created (ms since epoch). Used to resolve racing updates. */
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var current: IncomingCall? = null

    /** Optional state flag the ringing service can set/clear for sanity checks. */
    @Volatile
    var isRinging: Boolean = false
        private set

    /** Set/replace with the given call (newest wins by timestamp). */
    @Synchronized
    fun set(call: IncomingCall) {
        val prev = current
        current = when {
            prev == null -> call
            call.timestampMs >= prev.timestampMs -> call
            else -> prev // older event; ignore
        }
    }

    /** Convenience: build and set from FCM extras bundle. Returns the stored value. */
    @Synchronized
    fun updateFromExtras(extras: Bundle): IncomingCall? {
        val callId = extras.getString(PushKeys.EXTRA_CALL_ID)?.trim().orEmpty()
        if (callId.isEmpty()) return null

        val call = IncomingCall(
            callId = callId,
            callerUid = extras.getString(PushKeys.EXTRA_CALLER_UID),
            callerName = extras.getString(PushKeys.EXTRA_CALLER_NAME),
            timestampMs = System.currentTimeMillis()
        )
        set(call)
        return current
    }

    /** Read the current incoming call, if any. */
    fun get(): IncomingCall? = current

    /** True if the given callId matches the one we’re holding. */
    @Synchronized
    fun isActive(callId: String?): Boolean {
        if (callId.isNullOrBlank()) return false
        return current?.callId == callId
    }

    /**
     * Clear the store.
     * If callId is provided, only clears if it matches the stored one (idempotent safety).
     */
    @Synchronized
    fun clear(callId: String? = null) {
        current?.let { cur ->
            if (callId == null || callId == cur.callId) {
                current = null
                isRinging = false
            }
        }
    }

    /** Mark ringing state (used by CallRingingService to avoid double-start). */
    @Synchronized
    fun markRinging(started: Boolean) {
        isRinging = started
    }
}