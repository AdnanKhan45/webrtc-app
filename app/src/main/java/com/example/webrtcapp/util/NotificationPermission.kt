
package com.example.webrtcapp.util

import android.Manifest
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class NotificationPermission(private val activity: AppCompatActivity) {

    private var launcher: ActivityResultLauncher<String>? = null

    fun register(onResult: (granted: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            launcher = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> onResult(granted) }
        } else {
            onResult(true) // pre-33: implicitly granted
        }
    }

    fun request() {
        if (Build.VERSION.SDK_INT >= 33) {
            launcher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}