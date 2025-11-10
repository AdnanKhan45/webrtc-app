
package com.example.webrtcapp.call

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.webrtcapp.CallActivity
import com.example.webrtcapp.push.PushKeys

/**
 * Launched directly from the notification action to avoid "notification trampoline" block.
 * - If action=="accept": stop ringer and open CallActivity(callee)
 * - If action=="decline": just stop ringer
 */
class CallActionActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "extra_action" // "accept" | "decline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra(EXTRA_ACTION)
        val callId = intent.getStringExtra(PushKeys.EXTRA_CALL_ID)

        // Always tell the ringer service first
        val svc = Intent(this, CallRingingService::class.java).apply {
            when (action) {
                "accept"  -> this.action = CallRingingService.ACTION_ACCEPT
                "decline" -> this.action = CallRingingService.ACTION_DECLINE
                else      -> this.action = CallRingingService.ACTION_DECLINE
            }
            putExtras(intent) // forward all call extras
        }
        startService(svc)

        if (action == "accept" && !callId.isNullOrBlank()) {
            // Launch the call as callee, now we're in the foreground (allowed)
            val call = Intent(this, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_CALL_ID, callId)
                putExtra(CallActivity.EXTRA_ROLE, "callee")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(call)
        }

        finish()
    }
}