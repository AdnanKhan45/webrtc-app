// CallActionActivity.kt
package com.example.webrtcapp.call

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class CallActionActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "extra_action" // "accept" | "decline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Forward the exact extras to the service
        val action = intent.getStringExtra(EXTRA_ACTION)
        val svc = Intent(this, CallRingingService::class.java).apply {
            this.action = when (action) {
                "accept"  -> CallRingingService.ACTION_ACCEPT
                "decline" -> CallRingingService.ACTION_DECLINE
                else      -> CallRingingService.ACTION_DECLINE
            }
            putExtras(intent) // forward callId, names, timestamps, etc.
        }
        startService(svc)

        // 2) Do NOT start CallActivity here — the service is the sole launcher.
        finish()
    }
}