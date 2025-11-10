package com.example.webrtcapp.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.FirebaseMessaging
import com.example.webrtcapp.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TokenRefreshService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.d("TokenRefreshService", "onNewToken: $token")
        // Persist immediately and deterministically
        CoroutineScope(Dispatchers.IO).launch {
            try {
                UserRepository().upsertCurrentUser(
                    displayName = null,
                    deviceToken = token
                )
            } catch (t: Throwable) {
                Log.e("TokenRefreshService", "Failed to upsert token", t)
            }
        }
    }

    companion object {
        /**
         * One-shot fetch used by onboarding. Ensures AutoInit on,
         * then asks FCM for the cached/issued token.
         */
        fun fetchOnce(callback: (String?) -> Unit) {
            try {
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
            } catch (_: Throwable) {}

            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { callback(it) }
                .addOnFailureListener { callback(null) }
        }
    }
}