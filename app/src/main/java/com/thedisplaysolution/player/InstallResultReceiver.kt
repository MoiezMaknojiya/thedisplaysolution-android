package com.thedisplaysolution.player

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * What Android's installer says about an update AppUpdater handed it: first that it needs the TV's press — its own
 * confirmation screen, opened here — then whether it worked. On success Android replaces the app, and the new version
 * opens itself a minute later (WatchdogReceiver, MY_PACKAGE_REPLACED); anything else is said on the TV and the player
 * carries on.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_INSTALL_RESULT) return

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No screen to confirm the update on", e)
                    finished(context, context.getString(R.string.update_install_failed, e.message ?: ""))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.deleteLeftovers(context)
            PackageInstaller.STATUS_FAILURE_ABORTED -> finished(context, context.getString(R.string.update_cancelled))
            else -> finished(context, refusal(context, intent))
        }
    }

    /** What the installer refused, in words a person can act on, with Android's own beneath for whoever fixes it. */
    private fun refusal(context: Context, intent: Intent): String {
        val reason = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.update_failed_conflict
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.update_failed_incompatible
            PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.update_failed_storage
            PackageInstaller.STATUS_FAILURE_INVALID -> R.string.update_failed_invalid
            PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.update_failed_blocked
            else -> R.string.update_failed_other
        }
        val android = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }

        return context.getString(reason) + (android?.let { "\n\n" + context.getString(R.string.update_android_said, it) } ?: "")
    }

    /**
     * The update is over without one: the player comes in front again and says why on its own screen (MainActivity
     * takes the words in onResume); the toast is for a TV where the player is not there to say it.
     */
    private fun finished(context: Context, words: String) {
        AppUpdater.deleteLeftovers(context)
        Watchdog.endPause(context)
        AppUpdater.noteProblem(context, words)
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: RuntimeException) {
            // Android may not let it come forward from here: it says it when somebody opens it.
            Log.w(TAG, "The player could not come forward to say why", e)
        }
        Toast.makeText(context, words, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "InstallResult"
    }
}
