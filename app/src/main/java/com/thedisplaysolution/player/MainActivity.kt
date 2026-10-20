package com.thedisplaysolution.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.thedisplaysolution.player.ui.theme.DisplaySolutionPlayerTheme
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// The live server; AppUpdater asks it for updates too.
internal const val PLAYER_ORIGIN = "https://sign.thedisplaysolution.com"
private const val PLAYER_URL = "$PLAYER_ORIGIN/player"

// The same pause the player page itself takes before trying the server again.
private const val RETRY_DELAY_MS = 10_000L

// Holding Back this long opens the menu. A shorter press does nothing, so the player never closes by accident.
private const val BACK_HOLD_MS = 5_000L

// "Exit for 10 minutes" — and the time a person in the TV's settings gets before the player comes back.
private const val EXIT_FOR_MS = 10 * 60_000L

// The oldest WebView the player's page runs on: Chrome 80. Its script is built for it and its styles are its own,
// with no Tailwind (the web app's vite.config.js and resources/views/player/index.blade.php). Used only to explain a
// page that did not start: below it, the WebView is the likely reason, and the TV says so instead of staying black.
private const val MIN_WEBVIEW_MAJOR = 80

// The first look for an update once the player opens: after the page has had time to start.
private const val FIRST_UPDATE_CHECK_MS = 30_000L

// An update offered on a TV nobody is watching goes away by itself, as "Later".
private const val OFFER_SHOWN_MS = 60_000L

// How long after the page loads its script must have marked itself started (window.signagePlayerStarted).
private const val START_CHECK_MS = 20_000L

// A WebView too old today may be updated in the background; the player is tried again this often.
private const val OLD_WEBVIEW_RETRY_MS = 10 * 60_000L

// What the page forgets itself when the server answers 401 (resetToPairing in the player's script): its token and
// any code on file. The device id stays, so the dashboard knows the set and offers Replace device.
private const val FORGET_PAIRING_JS =
    "(function(){try{['signage.device.token','signage.device.code','signage.device.code_expires']" +
        ".forEach(function(k){localStorage.removeItem(k)})}catch(e){}return true})()"

private const val RETRY_HTML =
    """<html><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#000;color:#9aa4b2;font:20px sans-serif">Cannot reach the server. Retrying...</body></html>"""

private const val OLD_WEBVIEW_HTML =
    """<html><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#000;color:#cbd5e1;font:22px sans-serif;text-align:center"><div style="max-width:70%%"><p style="font-size:32px;color:#fff;margin:0 0 24px">This TV cannot run the player</p><p>Its web engine, Android System WebView, is version %d. The player needs version %d or newer.</p><p>Update Android System WebView in the TV's app store, or use a newer device. Trying again in 10 minutes.</p></div></body></html>"""

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val openMenu = Runnable { showMenu() }
    private val checkStarted = Runnable { checkPlayerStarted() }
    private val retryPlayer = Runnable { webView?.loadUrl(PLAYER_URL) }
    private val dailyUpdateCheck = Runnable { checkForUpdate(byHand = false) }

    // The network and the installer, off the main thread: one job at a time.
    private val background = Executors.newSingleThreadExecutor()

    private var webView: WebView? = null
    private var dialog: AlertDialog? = null

    // "Pair this TV again" asked while the WebView showed another page: done once the player's page is back.
    private var forgetPairingOnLoad = false

    // An update waiting for "Install unknown apps" to be turned on in the TV's settings: it carries on once back here.
    private var waitingToInstall: AppUpdater.Offer? = null

    // A download under way, and whether Cancel was pressed.
    private var downloading = false
    @Volatile private var downloadCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nobody touches a remote in front of a sign, so without this the TV's screensaver or sleep timer takes over
        // (a Fire TV after 5 minutes). The page's own wake lock does not exist in a WebView. Set on the window itself,
        // as Amazon's Fire TV requirements say: keepScreenOn on the WebView, set before the view was attached, never
        // reached the window (seen with dumpsys window, 2026-10-09).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A TV has no system bars, but a tablet or a generic Android box does: hide them so the
        // player owns the whole panel.
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Back arrives here as a key (android:enableOnBackInvokedCallback="false" in the manifest) and
        // onKeyDown/onKeyUp handle it. Should a platform deliver it as a callback instead, this swallows it,
        // so a short press still never closes the player.
        onBackPressedDispatcher.addCallback(this) { }

        setContent {
            DisplaySolutionPlayerTheme {
                PlayerWebView(
                    url = PLAYER_URL,
                    onCreated = { webView = it },
                    onPageLoaded = ::onPageLoaded,
                    // The page's renderer died (usually out of memory on a cheap box). That WebView
                    // cannot be used again, so start over with a fresh activity and a fresh WebView.
                    onRendererGone = ::recreate,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (!Watchdog.canRelaunch(this) && !autoStartWarned) {
            autoStartWarned = true
            Toast.makeText(this, R.string.auto_start_off, Toast.LENGTH_LONG).show()
        }

        // No update file stays on the TV: one left by a download cut short, or by a version installed since, goes now.
        AppUpdater.deleteLeftovers(this)
        AppUpdater.setDebugOrigin(this, intent.getStringExtra(EXTRA_UPDATE_ORIGIN))
        handler.postDelayed(dailyUpdateCheck, if (AppUpdater.dueOnStart(this)) FIRST_UPDATE_CHECK_MS else AppUpdater.DAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_UPDATE_ORIGIN)) AppUpdater.setDebugOrigin(this, intent.getStringExtra(EXTRA_UPDATE_ORIGIN))
    }

    override fun onStart() {
        super.onStart()
        Watchdog.playerShown(this)
        WatchdogService.start(this)
    }

    override fun onResume() {
        super.onResume()
        Watchdog.endPause(this)
        // Back from the TV's settings with "Install unknown apps" turned on: the update carries on by itself. Still off,
        // the TV says so, with the settings a press away — never an update waiting in silence.
        waitingToInstall?.let { offer ->
            if (AppUpdater.mayInstall(this)) {
                waitingToInstall = null
                download(offer)
            } else if (dialog?.isShowing != true) {
                askAgainToAllowInstalls(offer)
            }
        }
        // An update Android's installer refused while its own screen was in front: said here, where it can be read.
        AppUpdater.takeProblem(this)?.let { say(it, R.string.update_failed_title) }
    }

    override fun onStop() {
        handler.removeCallbacks(openMenu)
        Watchdog.playerHidden(this)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        downloadCancelled = true
        background.shutdownNow()
        dialog?.dismiss()
        webView = null
        super.onDestroy()
    }

    // Back: nothing on a short press; held for five seconds, the menu. Handled as a key on purpose — a
    // callback hears Back only once it is let go, so it could never tell a hold (see the manifest).

    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // A held key repeats its down event: only the first one starts the count.
        if (event.repeatCount == 0) {
            handler.removeCallbacks(openMenu)
            handler.postDelayed(openMenu, BACK_HOLD_MS)
        }
        return true
    }

    @SuppressLint("GestureBackNavigation")
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        handler.removeCallbacks(openMenu)
        return true
    }

    private fun showMenu() {
        if (isFinishing || dialog?.isShowing == true) return
        // Without auto-start nothing brings the player back after Exit: the menu says so, in its title and on Exit itself.
        val autoStart = Watchdog.canRelaunch(this@MainActivity)
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.menu_reload) to { reloadPlayer() })
            add(getString(R.string.menu_check_update) to { checkForUpdate(byHand = true) })
            add(getString(R.string.menu_pair_again) to { confirmPairAgain() })
            add(getString(if (autoStart) R.string.menu_exit else R.string.menu_exit_for_good) to { exitForAWhile() })
            if (!autoStart) add(getString(R.string.menu_allow_auto_start) to { showAutoStartHowTo() })
            add(getString(R.string.menu_cancel) to { })
        }
        val title = if (autoStart) R.string.menu_title else R.string.menu_title_no_auto_start
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(title, getString(R.string.app_name), appVersion(), webViewVersion()))
            // Rows large enough to read across a room, in the theme's dialog (R.layout.tv_menu_item).
            .setAdapter(ArrayAdapter(this, R.layout.tv_menu_item, actions.map { it.first })) { _, which -> actions[which].second() }
            .show()
            .forTheRoom()
    }

    /**
     * A dialog read from across a room with a remote: the button or the row the remote is on is filled in the panel's
     * blue with white words — AppCompat's own grey ripple cannot be seen from a sofa — and the words are large.
     */
    private fun AlertDialog.forTheRoom(): AlertDialog {
        listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)
            .mapNotNull(::getButton)
            .forEach { button ->
                button.background = ContextCompat.getDrawable(context, R.drawable.tv_focus_fill)
                // White on the blue, and a light grey otherwise — never AppCompat's teal, which is no colour of the panel's.
                button.setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.WHITE, Color.rgb(0xE5, 0xE7, 0xEB)),
                ))
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                val side = (16 * context.resources.displayMetrics.density).toInt()
                button.setPadding(side, button.paddingTop, side, button.paddingBottom)
            }
        listView?.selector = ContextCompat.getDrawable(context, R.drawable.tv_focus_fill)
        findViewById<TextView>(android.R.id.message)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        return this
    }

    // ── Updates of the app (AppUpdater) ────────────────────────────────────────────────────────────────────────

    /** Ask the server, and offer a newer version. By hand it always answers; by itself it says nothing unless there is one. */
    private fun checkForUpdate(byHand: Boolean) {
        handler.removeCallbacks(dailyUpdateCheck)
        handler.postDelayed(dailyUpdateCheck, AppUpdater.DAY_MS)
        if (downloading) {
            // Asked by hand, it answers — never a press that does nothing.
            if (byHand) Toast.makeText(this, R.string.update_already_downloading, Toast.LENGTH_LONG).show()
            return
        }
        if (byHand) Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()

        runInBackground({ AppUpdater.check(applicationContext) }) { result ->
            when (result) {
                is AppUpdater.Check.Newer -> {
                    val free = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && dialog?.isShowing != true
                    if (free && (byHand || !AppUpdater.isPostponed(this, result.offer))) offerUpdate(result.offer, byHand)
                }
                AppUpdater.Check.Current -> if (byHand) say(getString(R.string.update_newest, appVersion()))
                AppUpdater.Check.Failed -> if (byHand) say(getString(R.string.update_check_failed))
            }
        }
    }

    private fun offerUpdate(offer: AppUpdater.Offer, byHand: Boolean) {
        val message = getString(R.string.update_message, offer.name, appVersion()) +
            (offer.notes?.let { "\n\n$it" } ?: "")
        val offered = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(offer) }
            .setNegativeButton(R.string.update_later) { _, _ -> AppUpdater.postpone(this, offer) }
            .setOnCancelListener { AppUpdater.postpone(this, offer) }
            .show()
            .forTheRoom()
            // A remote has no pointer: OK on it updates, without first finding a button.
            .also { it.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus() }
        dialog = offered
        // Nobody in front of the TV: it goes by itself, as Later — unless somebody asked for it.
        if (!byHand) {
            handler.postDelayed({
                if (offered.isShowing) {
                    AppUpdater.postpone(this, offer)
                    offered.dismiss()
                }
            }, OFFER_SHOWN_MS)
        }
    }

    private fun startUpdate(offer: AppUpdater.Offer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !AppUpdater.mayInstall(this)) askToAllowInstalls(offer) else download(offer)
    }

    /** Android asks once per TV before an app may install its own update: its switch is in the TV's settings (Android 8 on). */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun askToAllowInstalls(offer: AppUpdater.Offer) {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_allow_title)
            .setMessage(R.string.update_allow_message)
            .setPositiveButton(R.string.update_allow_open) { _, _ -> openInstallSettings(offer) }
            .setNegativeButton(R.string.menu_cancel, null)
            .show()
            .forTheRoom()
            .also { it.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus() }
    }

    /** Back from the TV's settings with the switch still off: the settings again, or no update — chosen, never silence. */
    private fun askAgainToAllowInstalls(offer: AppUpdater.Offer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_still_off_title)
            .setMessage(getString(R.string.update_still_off_message, getString(R.string.app_name)))
            .setPositiveButton(R.string.update_allow_open) { _, _ -> openInstallSettings(offer) }
            .setNegativeButton(R.string.menu_cancel) { _, _ -> waitingToInstall = null }
            .setOnCancelListener { waitingToInstall = null }
            .show()
            .forTheRoom()
            .also { it.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openInstallSettings(offer: AppUpdater.Offer) {
        // The settings screen takes the player off the screen: the person there gets ten minutes, not one.
        Watchdog.pause(this, EXIT_FOR_MS)
        waitingToInstall = offer
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            // A Fire TV, and many boxes, keep the switch elsewhere: the way there, in words. The update stays waiting,
            // so it carries on the moment the person comes back with the switch on, and OK gives them their ten
            // minutes in the TV's settings before the player comes to the front again.
            Watchdog.endPause(this)
            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.update_allow_title)
                .setMessage(getString(R.string.update_allow_steps, getString(R.string.app_name)))
                .setPositiveButton(android.R.string.ok) { _, _ -> Watchdog.pause(this, EXIT_FOR_MS) }
                .setNegativeButton(R.string.menu_cancel) { _, _ -> waitingToInstall = null }
                .setOnCancelListener { waitingToInstall = null }
                .show()
                .forTheRoom()
                .also { it.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus() }
        }
    }

    private fun download(offer: AppUpdater.Offer) {
        if (downloading) return
        downloading = true
        downloadCancelled = false

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        // Read across a room: the theme's own large text, in its first colour.
        val words = TextView(this).apply {
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Medium)
            text = getString(R.string.update_progress, 0L, megabytes(offer.size))
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(bar)
            addView(words)
        }
        // Back is Cancel here as everywhere: the download stops, and nothing is installed.
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading, offer.name))
            .setView(view)
            .setNegativeButton(R.string.menu_cancel) { _, _ -> downloadCancelled = true }
            .setOnCancelListener { downloadCancelled = true }
            .show()
            .forTheRoom()
        progressDialog.setCanceledOnTouchOutside(false)
        dialog = progressDialog

        var lastShown = 0L
        runInBackground({
            try {
                val apk = AppUpdater.download(applicationContext, offer, progress = { received, total ->
                    // A few times a second is enough for the bar.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShown >= 250 || received == total) {
                        lastShown = now
                        runOnUiThread {
                            bar.progress = (received * 1000 / total).toInt()
                            words.text = getString(R.string.update_progress, megabytes(received), megabytes(total))
                        }
                    }
                }, cancelled = { downloadCancelled })
                // Android's confirmation takes the player off the screen: the watchdog must not cover it a minute later.
                Watchdog.pause(applicationContext, EXIT_FOR_MS)
                AppUpdater.install(applicationContext, apk)
                null
            } catch (e: AppUpdater.UpdateException) {
                Watchdog.endPause(applicationContext)
                if (downloadCancelled) null else e.message
            }
        }) { problem ->
            downloading = false
            progressDialog.dismiss()
            problem?.let { say(it, R.string.update_failed_title) }
        }
    }

    /** Work off the main thread, and its answer on it — never on an activity that has gone. */
    private fun <T> runInBackground(work: () -> T, then: (T) -> Unit) {
        try {
            background.execute {
                val result = work()
                runOnUiThread { if (!isFinishing && !isDestroyed) then(result) }
            }
        } catch (e: RejectedExecutionException) {
            // The activity is going: nothing to do.
        }
    }

    private fun say(words: String, title: Int? = null) {
        dialog = AlertDialog.Builder(this)
            .apply { if (title != null) setTitle(title) }
            .setMessage(words)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .forTheRoom()
            .also { it.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus() }
    }

    private fun megabytes(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)

    private fun reloadPlayer() {
        handler.removeCallbacks(retryPlayer)
        webView?.loadUrl(PLAYER_URL)
    }

    private fun confirmPairAgain() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pair_again_title)
            .setMessage(R.string.pair_again_message)
            .setPositiveButton(R.string.pair_again_confirm) { _, _ -> pairAgain() }
            .setNegativeButton(R.string.menu_cancel, null)
            .show()
            .forTheRoom()
            // One press of OK must not unpair a TV: Cancel has the focus.
            .also { it.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus() }
    }

    private fun pairAgain() {
        val view = webView ?: return
        // localStorage belongs to the player's origin: from the "retrying" or "too old" page it cannot be
        // reached, so the player's page is loaded first and the pairing forgotten once it is there.
        if (view.url?.startsWith(PLAYER_ORIGIN) == true) {
            forgetPairingAndReload(view)
        } else {
            forgetPairingOnLoad = true
            reloadPlayer()
        }
    }

    private fun forgetPairingAndReload(view: WebView) {
        view.evaluateJavascript(FORGET_PAIRING_JS) { view.loadUrl(PLAYER_URL) }
    }

    private fun exitForAWhile() {
        // With auto-start off the player never comes back by itself: asked once more, Cancel first.
        if (!Watchdog.canRelaunch(this)) {
            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.exit_for_good_title)
                .setMessage(R.string.exit_for_good_message)
                .setPositiveButton(R.string.exit_for_good_confirm) { _, _ -> finish() }
                .setNegativeButton(R.string.menu_cancel, null)
                .show()
                .forTheRoom()
                .also { it.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus() }
            return
        }
        Watchdog.pause(this, EXIT_FOR_MS)
        finish()
    }

    /**
     * How to let the player come back by itself: the TV's own setting where it has one, else once from a computer on the
     * same Wi-Fi with TheDisplaySolution-AutoStart.bat — this TV's address said here, so it can be typed there. A Fire TV
     * has no such setting (Fire OS 8): its steps are the computer's alone, and it is offered no button that leads nowhere
     * — nor is a TV whose setting turned out not to be there (`withSettings` false).
     *
     * The steps are long for a dialog's message, which the remote cannot scroll: they sit in a scrolling box the remote
     * can reach, so ↓ reads to the end.
     */
    private fun showAutoStartHowTo(withSettings: Boolean = !isFireTv()) {
        val address = tvAddress() ?: getString(R.string.auto_start_no_address)
        val steps = getString(if (withSettings) R.string.auto_start_how_to else R.string.auto_start_how_to_fire_tv, address, packageName)
        val side = (24 * resources.displayMetrics.density).toInt()
        val reader = ScrollView(this).apply {
            isFocusable = true
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.tv_focus_fill)?.mutate()?.apply { alpha = 90 }
            setPadding(side, side / 2, side, side / 2)
            addView(TextView(this@MainActivity).apply {
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Medium)
                text = steps
            })
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.auto_start_title)
            .setView(reader)
            .setNegativeButton(R.string.auto_start_close, null)
        if (withSettings) builder.setPositiveButton(R.string.auto_start_open_settings) { _, _ -> openOverlaySettings() }
        dialog = builder.show().forTheRoom().also {
            (it.getButton(DialogInterface.BUTTON_POSITIVE) ?: it.getButton(DialogInterface.BUTTON_NEGATIVE))?.requestFocus()
        }
    }

    private fun openOverlaySettings() {
        // The settings screen takes the player off the screen: the person there gets ten minutes, not one.
        Watchdog.pause(this, EXIT_FOR_MS)
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            // No such screen after all: the player is still here, so the pause goes again, and the computer's steps are
            // shown in place of the button that led nowhere.
            Watchdog.endPause(this)
            showAutoStartHowTo(withSettings = false)
        }
    }

    private fun isFireTv(): Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** This TV's own address on its network, as the computer will reach it. */
    private fun tvAddress(): String? = try {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity?.activeNetwork?.let(connectivity::getLinkProperties)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: SecurityException) {
        null
    }

    private fun onPageLoaded(view: WebView, url: String) {
        if (!url.startsWith(PLAYER_URL)) return
        if (forgetPairingOnLoad) {
            forgetPairingOnLoad = false
            forgetPairingAndReload(view)
            return
        }
        handler.removeCallbacks(checkStarted)
        handler.postDelayed(checkStarted, START_CHECK_MS)
    }

    /** A page whose script never ran on an old WebView says why, instead of leaving the TV black. */
    private fun checkPlayerStarted() {
        val view = webView ?: return
        if (view.url?.startsWith(PLAYER_URL) != true) return
        view.evaluateJavascript("window.signagePlayerStarted === true") { started ->
            val major = webViewMajor() ?: return@evaluateJavascript
            if (started != "true" && major < MIN_WEBVIEW_MAJOR) {
                view.loadDataWithBaseURL(null, OLD_WEBVIEW_HTML.format(major, MIN_WEBVIEW_MAJOR), "text/html", "utf-8", null)
                handler.removeCallbacks(retryPlayer)
                handler.postDelayed(retryPlayer, OLD_WEBVIEW_RETRY_MS)
            }
        }
    }

    /** The WebView's Chrome major version, from its user agent — which Amazon's WebView carries too. */
    private fun webViewMajor(): Int? = try {
        Regex("""Chrome/(\d+)""").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1)?.toIntOrNull()
    } catch (e: RuntimeException) {
        null
    }

    // Below Android 8 there is no package to ask, and the user agent's major version stands in.
    @SuppressLint("WebViewApiAvailability")
    private fun webViewVersion(): String {
        val full = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage()?.versionName else null
        return full ?: webViewMajor()?.toString() ?: "?"
    }

    private fun appVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private companion object {
        // Said once per process, not on every recreate().
        var autoStartWarned = false

        // A debug build only: the server to ask for updates instead of the live one (AppUpdater.setDebugOrigin).
        const val EXTRA_UPDATE_ORIGIN = "update_origin"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerWebView(
    url: String,
    onCreated: (WebView) -> Unit,
    onPageLoaded: (WebView, String) -> Unit,
    onRendererGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // AndroidView gives its view WRAP_CONTENT, and a WebView whose height wraps lays the
                // page out at zero height: 100vh and height:100% become 0 and the player shows nothing.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
                // On a D-pad device a focused view gets a grey highlight drawn over it; on a
                // full-screen player that would grey out the whole picture.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
                settings.javaScriptEnabled = true
                // The device token lives in localStorage: without this the TV asks to be paired on every start.
                settings.domStorageEnabled = true
                // Nobody taps a TV, so videos must start on their own.
                settings.mediaPlaybackRequiresUserGesture = false
                // Ads are laid out to the pixel; the system font size must not enlarge their text.
                settings.textZoom = 100
                webViewClient = PlayerWebViewClient(url, onPageLoaded, onRendererGone)
                onCreated(this)
                loadUrl(url)
            }
        },
        onRelease = WebView::destroy,
    )
}

/**
 * Keeps the player inside the app and never leaves an error page on the wall: when the page
 * itself cannot be loaded (no network yet after power-on, the server down or mid-deploy), it
 * shows a black "retrying" screen and tries again. Once the page is up, the player handles a
 * lost connection on its own.
 */
private class PlayerWebViewClient(
    private val url: String,
    private val onPageLoaded: (WebView, String) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {

    private var retry: Runnable? = null

    override fun onPageFinished(view: WebView, url: String) {
        onPageLoaded(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) retryLater(view)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame && errorResponse.statusCode >= 500) retryLater(view)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        retry?.let(view::removeCallbacks)
        onRendererGone()
        return true
    }

    private fun retryLater(view: WebView) {
        view.loadDataWithBaseURL(null, RETRY_HTML, "text/html", "utf-8", null)
        // One retry at a time, and never on a WebView that has already been released.
        retry?.let(view::removeCallbacks)
        retry = Runnable { if (view.isAttachedToWindow) view.loadUrl(url) }
            .also { view.postDelayed(it, RETRY_DELAY_MS) }
    }
}
