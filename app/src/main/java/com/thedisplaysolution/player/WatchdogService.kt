package com.thedisplaysolution.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Runs the watchdog's check every few seconds and hears the TV wake up. A foreground service, because only that keeps
 * the process alive through a night in standby: without it Android puts an app that has been off the screen for hours
 * in a lower standby bucket, delays its alarms by many minutes, and the player would not be back "a minute after the
 * TV comes on". On a TV the notification it has to carry is not shown anywhere.
 */
class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            Watchdog.check(this@WatchdogService)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Watchdog.onScreenOn(context)
    }

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            // Android refused a foreground start (a restart from the background after a crash): the alarm opens the
            // player, and the player starts this service properly.
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            unregisterReceiver(screenOn)
        } catch (e: IllegalArgumentException) {
            // Never registered: the service stopped itself in onCreate.
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.watchdog_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watchdog)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.watchdog_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not run in the foreground", e)
            false
        }
    }

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 5_000L

        /** From the player on the screen, or straight after boot: the two moments Android allows it. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchdogService::class.java))
            } catch (e: RuntimeException) {
                // Not allowed from here (the background on Android 12 and later): the alarm covers it.
                Log.w(TAG, "Could not start the watchdog", e)
            }
        }
    }
}
