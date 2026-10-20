package com.thedisplaysolution.player

import android.app.Application
import android.util.Log

/** A crash brings the player back a minute later, like any other way of leaving the screen (Watchdog). */
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Watchdog.onCrash(this)
            } catch (e: Throwable) {
                // Never let the watchdog hide the crash itself.
                Log.e("PlayerApp", "Watchdog could not note the crash", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
