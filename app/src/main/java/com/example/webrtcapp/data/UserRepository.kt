package com.example.webrtcapp.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private fun currentUid(): String? = auth.currentUser?.uid
    private fun users() = db.collection("users")

    /**
     * Upsert the current user's doc with name + token.
     * Any argument left null will preserve the existing value.
     */
    suspend fun upsertCurrentUser(
        displayName: String? = null,
        deviceToken: String? = null
    ) {
        val uid = currentUid() ?: return
        val doc = users().document(uid)

        val payload = mutableMapOf<String, Any?>(
            "uid" to uid,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        if (displayName != null) payload["displayName"] = displayName
        if (deviceToken != null) payload["deviceToken"] = deviceToken

        // Merge to avoid nuking other fields later
        doc.set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    /** Convenience to update only the token (used by token refresh service). */
    suspend fun updateDeviceToken(token: String) {
        val uid = currentUid() ?: return
        users().document(uid).set(
            mapOf(
                "uid" to uid,
                "deviceToken" to token,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
    }
}