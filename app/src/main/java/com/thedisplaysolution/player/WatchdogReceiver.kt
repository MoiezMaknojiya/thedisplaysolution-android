package com.thedisplaysolution.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The watchdog's alarm, and the moments a TV starts: boot, a fast boot on some boxes, and an update of this app. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Watchdog.ACTION_CHECK -> Watchdog.check(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_QUICKBOOT_POWERON -> {
                Watchdog.onBoot(context)
                WatchdogService.start(context)
            }
        }
    }

    private companion object {
        // Sent instead of BOOT_COMPLETED by some boxes after a fast boot.
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
