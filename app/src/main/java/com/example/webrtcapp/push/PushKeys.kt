package com.example.webrtcapp.push

/**
 * Single source of truth for FCM data payload keys, types, intent actions and extras.
 * Keep client, services and Cloud Functions in sync with these constants.
 *
 * We also keep a few legacy aliases so older code (or test messages) won’t break.
 */
object PushKeys {

    // -----------------------------
    // Payload keys (top-level data)
    // -----------------------------
    // Unified names (preferred)
    const val KEY_TYPE         = "type"            // e.g. "call.invite", "call.cancel"
    const val KEY_CALL_ID      = "callId"
    const val KEY_CALLER_UID   = "callerUid"
    const val KEY_CALLER_NAME  = "callerName"
    const val KEY_CALLEE_UID   = "calleeUid"
    const val KEY_TIMESTAMP    = "timestamp"       // string or millis you send from CF (optional)
    const val KEY_RING_MS      = "ringMs"          // optional, how long to ring
    const val KEY_EXPIRES_AT   = "expiresAtMs"     // optional, drop if now > this

    // ---- Legacy aliases (kept for compatibility) ----
    // If any old code/tests still send these, they map to the same fields.
    const val TYPE             = KEY_TYPE          // alias
    const val CALL_ID          = KEY_CALL_ID
    const val CALLER_UID       = KEY_CALLER_UID
    const val CALLER_NAME      = KEY_CALLER_NAME
    const val SERVER_TS_MS     = KEY_TIMESTAMP     // older name used in a draft
    const val EXPIRES_AT_MS    = KEY_EXPIRES_AT
    const val RING_MS          = KEY_RING_MS

    // -----------------------------
    // Message types / “verbs”
    // -----------------------------
    // Current (preferred)
    const val TYPE_CALL_INVITE = "call.invite"
    const val TYPE_CALL_CANCEL = "call.cancel"

    // Legacy (older examples)
    const val TYPE_INCOMING    = "incoming"   // == invite
    const val TYPE_CANCEL      = "cancel_call"     // == cancel

    // -----------------------------
    // Intent actions between components
    // -----------------------------
    const val ACTION_START_RING = "com.example.webrtcapp.action.START_RING"
    const val ACTION_STOP_RING  = "com.example.webrtcapp.action.STOP_RING"
    const val ACTION_DECLINE    = "com.example.webrtcapp.action.DECLINE"
    const val ACTION_CANCELLED  = "com.example.webrtcapp.action.CANCELLED"

    // -----------------------------
    // Intent extras (Activity/Service)
    // -----------------------------
    const val EXTRA_CALL_ID      = "extra_call_id"
    const val EXTRA_CALLER_UID   = "extra_caller_uid"
    const val EXTRA_CALLER_NAME  = "extra_caller_name"
    const val EXTRA_CALLEE_UID   = "extra_callee_uid"
    const val EXTRA_TIMESTAMP    = "extra_timestamp"
    const val EXTRA_RING_MS      = "extra_ring_ms"
    const val EXTRA_ACCEPT       = "extra_accept"
    const val EXTRA_FROM_PUSH    = "extra_from_push"

    // -----------------------------
    // Notification channels / IDs
    // -----------------------------
    /** Foreground service channel for ringing UI */
    const val CHANNEL_RING = "ring"
    const val CHANNEL_ONGOING = "ongoing"

    /** Optional: description text when creating the channel */
    const val CHANNEL_DESC_RING = "Incoming call ringing and full-screen notifications"

    /** Stable ID for the ringing foreground notification */
    const val NOTIF_ID_RING = 4001
}