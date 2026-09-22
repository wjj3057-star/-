package com.imaxcam

import android.app.Application
import android.os.Process

class ImaxCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The UI thread posts encoder and camera work; keeping it at display priority
        // stops a background task from delaying the start of a take.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
    }
}
