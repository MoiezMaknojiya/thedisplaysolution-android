package com.thedisplaysolution.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * What the player's page may ask of the app: `window.SignagePlayer` (docs/TV-ORIENTATION-SPEC.md in the panel's
 * repository). Only the methods marked @JavascriptInterface reach the page.
 *
 * A set mounted another way than flat is turned by the APP, not by the page's CSS (Roadstar 4, 2026-10-27): a
 * Fire TV draws a video on a hardware plane of its own that no CSS transform reaches, while turning the display
 * turns that plane with everything else. The page says which way the set is mounted on every playlist answer,
 * and once told `true` turns nothing itself.
 */
class SignagePlayerBridge(private val activity: Activity) {

    /**
     * Turn the display the way the set is mounted: one of the four values of `screens.orientation`. Called on the
     * page's JavaScript thread, so the request is handed to the UI thread. True for any of the four, even when the
     * display is turned that way already (the page asks every thirty seconds); false for anything else, which leaves
     * the page turning its own stage as it did before the app could.
     */
    @JavascriptInterface
    fun turnDisplay(orientation: String): Boolean {
        val wanted = when (orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "landscape_flipped" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "portrait_flipped" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> {
                Log.w(TAG, "turnDisplay($orientation): not a way a set is mounted")
                return false
            }
        }

        activity.runOnUiThread {
            if (activity.requestedOrientation != wanted) {
                Log.i(TAG, "turnDisplay($orientation): requesting orientation $wanted (was ${activity.requestedOrientation})")
                activity.requestedOrientation = wanted
            }
        }

        return true
    }

    private companion object {
        const val TAG = "SignagePlayer"
    }
}
