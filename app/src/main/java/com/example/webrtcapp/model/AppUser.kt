package com.example.webrtcapp.model

data class AppUser(
    val uid: String = "",
    val displayName: String? = null,
    val deviceToken: String? = null,
    val updatedAt: com.google.firebase.Timestamp? = null
)