package com.example.webrtcapp

import android.app.Application
import com.example.webrtcapp.webrtc.RtcDependencies

/**
 * App entry point. Holds a single instance of RtcDependencies
 * for the whole process lifetime.
 */
class App : Application() {

    // Create once and reuse everywhere (UI will read it via context).
    lateinit var rtcDeps: RtcDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        rtcDeps = RtcDependencies(applicationContext)
    }

    /**
     * Note: onTerminate() is not called on real devices, only emulators.
     * We keep cleanup explicit and will wire it when we have a clear shutdown point.
     */
    override fun onTerminate() {
        // rtcDeps.dispose() // (avoid on real devices—this rarely runs)
        super.onTerminate()
    }
}