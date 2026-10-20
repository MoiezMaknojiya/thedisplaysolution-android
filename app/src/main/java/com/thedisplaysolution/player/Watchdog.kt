package com.thedisplaysolution.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

/**
 * Keeps the player on the screen (owner, 2026-09-24): a minute after the TV starts or wakes up, and a minute after
 * the player crashed or somebody left it, the player opens again. "Exit for 10 minutes" keeps it closed for ten.
 *
 * Every event only writes down when the player went away; check() decides, and opens the player when it is due.
 * WatchdogService calls it every few seconds; when no service is running (a crash, a killed process), an alarm
 * calls it instead — the alarm lives outside the process, so nothing that happens to the process can lose it.
 * Times are elapsedRealtime, which a clock set at boot cannot move.
 *
 * What cannot be survived: "Force stop" in the TV's settings, after which Android lets nothing of the app run
 * until somebody opens it by hand.
 */
object Watchdog {
    const val ACTION_CHECK = "com.thedisplaysolution.player.action.WATCHDOG_CHECK"

    /** How long the player may be off the screen before it opens again. */
    const val RELAUNCH_AFTER_MS = 60_000L

    private const val TAG = "Watchdog"
    private const val PREFS = "watchdog"

    // When the player left the screen, or the TV came on without it.
    private const val KEY_HIDDEN_SINCE = "hidden_since"

    // The last time a check saw the player on the screen: for a process that died without a word.
    private const val KEY_SEEN_AT = "seen_at"

    // Until when "Exit for 10 minutes" keeps the player closed.
    private const val KEY_PAUSED_UNTIL = "paused_until"

    // How often "still on the screen" is written down: often enough, never a disk write every tick.
    private const val SEEN_WRITE_EVERY_MS = 30_000L

    // The alarm is kept this far ahead, and pushed on once a minute while a process is alive to push it.
    private const val BACKUP_ALARM_MS = 90_000L
    private const val BACKUP_PUSH_EVERY_MS = 60_000L

    /** True while the player activity is started — in this process only, so a dead process reads "not on screen". */
    @Volatile
    var playerOnScreen = false
        private set

    private var lastSeenWrite = 0L
    private var lastBackupPush = 0L

    fun playerShown(context: Context) {
        playerOnScreen = true
        lastSeenWrite = now()
        prefs(context).edit {
            remove(KEY_HIDDEN_SINCE)
            remove(KEY_PAUSED_UNTIL)
            putLong(KEY_SEEN_AT, lastSeenWrite)
        }
    }

    fun playerHidden(context: Context) {
        playerOnScreen = false
        prefs(context).edit {
            remove(KEY_SEEN_AT)
            putLong(KEY_HIDDEN_SINCE, now())
        }
    }

    /** "Exit for 10 minutes": the player stays closed that long, then opens. */
    fun pause(context: Context, durationMs: Long) {
        prefs(context).edit { putLong(KEY_PAUSED_UNTIL, now() + durationMs) }
    }

    /**
     * The player is back in front: a pause asked for on the way out is over. Needed besides playerShown() because a
     * TV's settings can open as a side panel, which pauses the player without ever stopping it.
     */
    fun endPause(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(KEY_PAUSED_UNTIL)) prefs.edit { remove(KEY_PAUSED_UNTIL) }
    }

    /** Boot, or this app just updated: the player opens a minute from now. */
    fun onBoot(context: Context) {
        // A pause does not outlive a restart, and every time on file belongs to the last boot.
        prefs(context).edit {
            clear()
            putLong(KEY_HIDDEN_SINCE, now())
        }
        setAlarm(context, RELAUNCH_AFTER_MS)
    }

    /** The TV woke up: if the player is not what it shows, it will be a minute from now. */
    fun onScreenOn(context: Context) {
        if (playerOnScreen) return
        prefs(context).edit { putLong(KEY_HIDDEN_SINCE, now()) }
    }

    /** From the crash handler, while the process goes down: written at once, and only the alarm survives it. */
    fun onCrash(context: Context) {
        playerOnScreen = false
        prefs(context).edit(commit = true) {
            remove(KEY_SEEN_AT)
            putLong(KEY_HIDDEN_SINCE, now())
        }
        setAlarm(context, RELAUNCH_AFTER_MS)
    }

    fun check(context: Context) {
        val prefs = prefs(context)
        val now = now()
        pushBackupAlarm(context, now)

        if (playerOnScreen) {
            if (now - lastSeenWrite >= SEEN_WRITE_EVERY_MS) {
                lastSeenWrite = now
                prefs.edit { putLong(KEY_SEEN_AT, now) }
            }
            return
        }

        val pausedUntil = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        if (pausedUntil > now) return

        if (!screenIsOn(context)) {
            // The TV is in standby: nobody is looking, and the minute counts from the moment it wakes.
            if (prefs.contains(KEY_HIDDEN_SINCE) || prefs.contains(KEY_SEEN_AT) || prefs.contains(KEY_PAUSED_UNTIL)) {
                prefs.edit {
                    remove(KEY_HIDDEN_SINCE)
                    remove(KEY_SEEN_AT)
                    remove(KEY_PAUSED_UNTIL)
                }
            }
            return
        }

        // A pause that is over opens the player at once; otherwise it must have been away for a minute: since it
        // left, or since the last check that still saw it, or — knowing neither — from now.
        if (pausedUntil == 0L) {
            val since = listOf(prefs.getLong(KEY_HIDDEN_SINCE, 0L), prefs.getLong(KEY_SEEN_AT, 0L))
                .firstOrNull { it in 1..now }
                ?: now.also { prefs.edit { putLong(KEY_HIDDEN_SINCE, it) } }
            if (now - since < RELAUNCH_AFTER_MS) return
        }

        // Counted again from now, so a launch Android refused is tried once a minute, not once a tick.
        prefs.edit {
            remove(KEY_PAUSED_UNTIL)
            remove(KEY_SEEN_AT)
            putLong(KEY_HIDDEN_SINCE, now)
        }
        launch(context)
    }

    /** Whether this TV lets the player bring itself back: Android 10 and later need "Display over other apps". */
    fun canRelaunch(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    private fun launch(context: Context) {
        if (!canRelaunch(context)) {
            Log.w(TAG, "Opening the player without \"Display over other apps\": Android will not let it come forward")
        }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the player", e)
        }
    }

    private fun pushBackupAlarm(context: Context, now: Long) {
        if (lastBackupPush != 0L && now - lastBackupPush < BACKUP_PUSH_EVERY_MS) return
        lastBackupPush = now
        setAlarm(context, BACKUP_ALARM_MS)
    }

    /**
     * One alarm, replaced each time. Inexact — an exact one needs a permission meant for alarm clocks — so Android
     * may deliver it up to three quarters of the wait late: it only stands in for the service, which is on time.
     */
    private fun setAlarm(context: Context, delayMs: Long) {
        val appContext = context.applicationContext
        val alarms = appContext.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, WatchdogReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, intent)
    }

    private fun screenIsOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = SystemClock.elapsedRealtime()
}
