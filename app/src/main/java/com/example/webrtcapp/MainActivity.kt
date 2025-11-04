package com.example.webrtcapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.webrtcapp.CallActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var callIdInput: EditText
    private lateinit var btnStartCaller: Button
    private lateinit var btnStartCallee: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- views ----
        titleText = findViewById(R.id.titleText)
        callIdInput = findViewById(R.id.callIdInput)
        btnStartCaller = findViewById(R.id.btnStartCaller)
        btnStartCallee = findViewById(R.id.btnStartCallee)
        statusText = findViewById(R.id.statusText)

        // ---- Firebase (explicit init + anonymous auth for dev) ----
        FirebaseApp.initializeApp(this)
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener { statusText.text = "Signed in (anonymous)" }
            .addOnFailureListener { statusText.text = "Auth failed: ${it.message}" }

        // Pre-fill a callId for convenience
        if (callIdInput.text.isNullOrBlank()) {
            callIdInput.setText(defaultCallId())
        }

        btnStartCaller.setOnClickListener {
            val callId = normalizedCallId()
            startCall(callId, role = "caller")
        }

        btnStartCallee.setOnClickListener {
            val callId = normalizedCallId()
            startCall(callId, role = "callee")
        }
    }

    private fun normalizedCallId(): String {
        val raw = callIdInput.text?.toString()?.trim().orEmpty()
        return if (raw.isEmpty()) defaultCallId() else raw
    }

    private fun defaultCallId(): String {
        // Short, human-friendly ID based on UUID prefix
        return UUID.randomUUID().toString().substring(0, 8)
    }

    private fun startCall(callId: String, role: String) {
        statusText.text = "Launching Call ($role)…"
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_ROLE, role)
        }
        startActivity(intent)
    }
}