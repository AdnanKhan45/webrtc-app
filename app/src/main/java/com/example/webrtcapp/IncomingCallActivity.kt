package com.example.webrtcapp

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.webrtcapp.call.CallRingingService
import com.example.webrtcapp.push.PushKeys

class IncomingCallActivity : AppCompatActivity() {

    private lateinit var callerNameText: TextView
    private lateinit var callIdText: TextView
    private lateinit var btnAccept: Button
    private lateinit var btnDecline: Button
    private lateinit var avatar: ImageView

    private var callId: String? = null
    private var callerUid: String? = null
    private var callerName: String? = null
    private var calleeUid: String? = null
    private var timestamp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        makeItFullScreenOnLock()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming_call)

        callerNameText = findViewById(R.id.callerNameText)
        callIdText     = findViewById(R.id.callIdText)
        btnAccept      = findViewById(R.id.btnAccept)
        btnDecline     = findViewById(R.id.btnDecline)
        avatar         = findViewById(R.id.avatar)

        readExtras(intent)

        callerNameText.text = callerName ?: getString(R.string.caller_unknown)
        callIdText.text = getString(R.string.call_id_fmt, callId ?: "—")

        btnAccept.setOnClickListener { onAccept() }
        btnDecline.setOnClickListener { onDecline() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            setIntent(it)
            readExtras(it)
            callerNameText.text = callerName ?: getString(R.string.caller_unknown)
            callIdText.text = getString(R.string.call_id_fmt, callId ?: "—")
        }
    }

    private fun onAccept() {
        val svc = Intent(this, CallRingingService::class.java).apply {
            action = CallRingingService.ACTION_ACCEPT
            // forward ALL the extras we received so the service has callId/callerName
            putExtras(intent)
        }
        startService(svc)
        finish()
    }

    private fun onDecline() {
        val svc = Intent(this, CallRingingService::class.java).apply {
            action = CallRingingService.ACTION_DECLINE
            putExtras(intent)
        }
        startService(svc)
        finish()
    }

    private fun readExtras(i: Intent) {
        callId     = i.getStringExtra(PushKeys.EXTRA_CALL_ID)
        callerUid  = i.getStringExtra(PushKeys.EXTRA_CALLER_UID)
        callerName = i.getStringExtra(PushKeys.EXTRA_CALLER_NAME)
        calleeUid  = i.getStringExtra(PushKeys.EXTRA_CALLEE_UID)
        timestamp  = i.getStringExtra(PushKeys.EXTRA_TIMESTAMP)
    }

    private fun makeItFullScreenOnLock() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            try { km.requestDismissKeyguard(this, null) } catch (_: Throwable) {}
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}