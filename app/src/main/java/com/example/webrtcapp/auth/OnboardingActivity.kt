// app/src/main/java/com/example/webrtcapp/auth/OnboardingActivity.kt
package com.example.webrtcapp.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.webrtcapp.MainActivity
import com.example.webrtcapp.R
import com.example.webrtcapp.util.NotificationPermission
import com.example.webrtcapp.push.TokenRefreshService
import com.example.webrtcapp.data.UserRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var btnContinue: Button
    private lateinit var statusText: TextView

    private lateinit var notifPerm: NotificationPermission
    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        nameLayout = findViewById(R.id.nameLayout)
        nameInput  = findViewById(R.id.nameInput)
        btnContinue = findViewById(R.id.btnContinue)
        statusText  = findViewById(R.id.statusText)

        // Pre-fill from prefs if user came back
        val prefs = getSharedPreferences("webrtc_prefs", Context.MODE_PRIVATE)
        nameInput.setText(prefs.getString("display_name", ""))

        // Ask notification permission once (for later ringing UX)
        notifPerm = NotificationPermission(this).also {
            it.register { /* we don’t block here */ }
            it.request()
        }

        btnContinue.setOnClickListener {
            val displayName = nameInput.text?.toString()?.trim().orEmpty()
            if (displayName.isBlank()) {
                nameLayout.error = "Please enter a name"
                return@setOnClickListener
            } else {
                nameLayout.error = null
            }

            // Persist chosen name locally for MainActivity
            prefs.edit().putString("display_name", displayName).apply()

            val auth = FirebaseAuth.getInstance()
            val existing = auth.currentUser

            statusText.text = if (existing == null) "Signing in…" else "Preparing…"

            // Only sign in if no current user. This prevents multiple anonymous accounts.
            if (existing == null) {
                auth.signInAnonymously()
                    .addOnSuccessListener {
                        statusText.text = "Signed in"
                        pushProfileThenEnter(displayName)
                    }
                    .addOnFailureListener { e ->
                        statusText.text = "Auth failed: ${e.message}"
                    }
            } else {
                // Already signed in (process restart / returning user)
                pushProfileThenEnter(displayName)
            }
        }
    }

    private fun pushProfileThenEnter(displayName: String) {
        // Get FCM token once and upsert user profile (uid, name, token)
        TokenRefreshService.fetchOnce { token ->
            ioScope.launch {
                try {
                    UserRepository().upsertCurrentUser(
                        displayName = displayName,
                        deviceToken = token // null-safe: if null, repo preserves old token
                    )
                } catch (_: Throwable) { /* optional logging */ }

                // Hop to the calling UI
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}