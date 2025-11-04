package com.example.webrtcapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.content.Context
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import com.google.android.material.appbar.MaterialToolbar




class MainActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var callIdLayout: TextInputLayout
    private lateinit var callIdInput: TextInputEditText
    private lateinit var btnStartCaller: Button
    private lateinit var btnStartCallee: Button
    private lateinit var statusText: TextView

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- views ----
        titleText = findViewById(R.id.titleText)
        callIdLayout = findViewById(R.id.callIdLayout)
        callIdInput = findViewById(R.id.callIdInput)
        btnStartCaller = findViewById(R.id.btnStartCaller)
        btnStartCallee = findViewById(R.id.btnStartCallee)
        statusText = findViewById(R.id.statusText)
        nameLayout = findViewById(R.id.nameLayout)
        nameInput = findViewById(R.id.nameInput)

        // Add a custom end icon to randomize Call ID
        callIdLayout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        callIdLayout.setEndIconDrawable(R.drawable.ic_dice) // add a dice vector; or swap for any icon
        callIdLayout.setEndIconOnClickListener {
            callIdInput.setText(defaultCallId())
            callIdInput.setSelection(callIdInput.text?.length ?: 0)
        }

        // Prefill name from SharedPreferences
        val prefs = getSharedPreferences("webrtc_prefs", Context.MODE_PRIVATE)
        nameInput.setText(prefs.getString("display_name", ""))

        // (Optional) end icon on name to clear
        nameLayout.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT

        // ---- Firebase (explicit init + anonymous auth for dev) ----
        FirebaseApp.initializeApp(this)
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener { statusText.text = "Signed in" }
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

        val topBar = findViewById<MaterialToolbar>(R.id.topBar)
        topBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_random -> {
                    callIdInput.setText(defaultCallId())
                    true
                }
                R.id.action_about -> {
                    statusText.text = "WebRTC demo • Material 3 UI"
                    true
                }
                else -> false
            }
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
        val displayName = nameInput.text?.toString()?.trim().orEmpty()

        // Save name for next time
        getSharedPreferences("webrtc_prefs", Context.MODE_PRIVATE)
            .edit().putString("display_name", displayName).apply()

        statusText.text = if (role == "caller") "Starting…" else "Joining…"
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_ROLE, role)
            putExtra("displayName", displayName)
        }
        startActivity(intent)
    }
}