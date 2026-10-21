package com.thedisplaysolution.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates of the app itself (owner, 2026-10-10): the web app's Developer → TV App says which version every TV is offered,
 * at /player-app/latest.json. The player asks whenever it comes to the front — opened, or back from Home or another app
 * (owner, 2026-10-20: Home and open again found no update) — once ten minutes have passed since it last asked, and once a
 * day while it stays in front. A newer version is offered every time the player comes to the front, even after Later,
 * which quiets only the player's own looks while it stays in front (MainActivity.planUpdateCheck). The version is
 * offered in a dialog, downloaded into the app's cache, checked against its size and SHA-256 and
 * handed to Android's PackageInstaller, which asks for one press on the TV — Android installs nothing without it. The
 * downloaded file is deleted the moment Android has its copy, and anything left from before when the player opens, so no
 * APK stays in the TV's storage. A copy a store installed (storeThatInstalled) is the store's to update, and none of this
 * runs for it.
 *
 * Everything here but the preferences runs off the main thread.
 */
object AppUpdater {
    const val ACTION_INSTALL_RESULT = "com.thedisplaysolution.player.action.INSTALL_RESULT"

    /**
     * Asked again at most this often when the player comes to the front: a crash loop, or the watchdog bringing the
     * player back every minute, must not ask the server each time.
     */
    private const val ASK_AGAIN_AFTER_MS = 10 * 60_000L

    /** How often the server is asked while the player stays in front. */
    const val DAY_MS = 24 * 60 * 60_000L

    /**
     * How long "Later" keeps the player's own looks, while it stays in front, from offering the same version: a day less
     * an hour, so the next day's look offers it again — a full day, counted from a moment after that look began, let it
     * pass until the day after. Coming back to the front offers it whatever Later said.
     */
    private const val LATER_MS = 23 * 60 * 60_000L

    private const val PREFS = "updates"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_LATER_CODE = "later_code"
    private const val KEY_LATER_AT = "later_at"
    private const val KEY_OFFER = "offer"
    private const val KEY_DEBUG_ORIGIN = "debug_origin"
    private const val KEY_PROBLEM = "install_problem"

    private const val FOLDER = "update"
    private const val TIMEOUT_MS = 15_000

    /** The stores that update their own copies, by the installer package Android names, with the words for the TV. */
    private val STORES = mapOf(
        "com.amazon.venezia" to "the Amazon Appstore",
        "com.android.vending" to "Google Play",
    )

    /** What the server offers. */
    data class Offer(val code: Long, val name: String, val url: String, val size: Long, val sha256: String, val notes: String?)

    sealed interface Check {
        data class Newer(val offer: Offer) : Check
        data object Current : Check
        data object Failed : Check
    }

    class UpdateException(message: String) : IOException(message)

    /** How long until the server may be asked again, now that the player is in front: nought when it may be now. */
    fun msUntilDue(context: Context): Long {
        val checkedAt = prefs(context).getLong(KEY_CHECKED_AT, 0L)
        val now = System.currentTimeMillis()
        // Never asked, or a clock set back since: now.
        if (checkedAt == 0L || now < checkedAt) return 0L
        return (ASK_AGAIN_AFTER_MS - (now - checkedAt)).coerceAtLeast(0L)
    }

    /** Blocking: what the server offers, compared with what this TV has. */
    fun check(context: Context): Check {
        prefs(context).edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }

        val offer = try {
            val connection = open("${origin(context)}/player-app/latest.json")
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parse(connection.inputStream.bufferedReader().use { it.readText() }, context.packageName)
                    // No version uploaded yet, or every one deleted: nothing to offer.
                    HttpURLConnection.HTTP_NOT_FOUND -> {
                        remember(context, null)
                        return Check.Current
                    }
                    else -> return Check.Failed
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            return Check.Failed
        } catch (e: JSONException) {
            return Check.Failed
        } ?: return Check.Failed

        val newer = offer.code > installedCode(context)
        remember(context, offer.takeIf { newer })
        return if (newer) Check.Newer(offer) else Check.Current
    }

    /**
     * The newer version the server named last, kept so the player can offer it each time it comes to the front without
     * asking the server again within ten minutes — and after a restart too (a Fire TV ends a hidden app when it needs
     * the memory). Null forgets it: the server named nothing newer.
     */
    private fun remember(context: Context, offer: Offer?) {
        prefs(context).edit {
            if (offer == null) {
                remove(KEY_OFFER)
            } else {
                putString(KEY_OFFER, JSONObject()
                    .put("package", context.packageName)
                    .put("version_code", offer.code)
                    .put("version_name", offer.name)
                    .put("url", offer.url)
                    .put("size", offer.size)
                    .put("sha256", offer.sha256)
                    .put("notes", offer.notes ?: JSONObject.NULL)
                    .toString())
            }
        }
    }

    /** What the server named last, while it is still newer than the version on this TV. */
    fun remembered(context: Context): Offer? {
        val json = prefs(context).getString(KEY_OFFER, null) ?: return null
        val offer = try {
            parse(json, context.packageName)
        } catch (e: JSONException) {
            null
        }
        return offer?.takeIf { it.code > installedCode(context) }
    }

    /** "Later": the player's own looks while it stays in front leave the same version alone until the next day's (LATER_MS). */
    fun postpone(context: Context, offer: Offer) {
        prefs(context).edit {
            putLong(KEY_LATER_CODE, offer.code)
            putLong(KEY_LATER_AT, System.currentTimeMillis())
        }
    }

    fun isPostponed(context: Context, offer: Offer): Boolean {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_LATER_AT, 0L)
        val now = System.currentTimeMillis()
        return prefs.getLong(KEY_LATER_CODE, 0L) == offer.code && now >= at && now - at < LATER_MS
    }

    /**
     * Why Android's installer refused the update, kept for the player to say on its own screen the next time it is in
     * front (InstallResultReceiver hears it while the installer's screen is) — a toast of three seconds is gone before
     * anybody across the room has read it.
     */
    fun noteProblem(context: Context, words: String) {
        prefs(context).edit { putString(KEY_PROBLEM, words) }
    }

    /** The problem noted, once: said, it is forgotten. */
    fun takeProblem(context: Context): String? {
        val prefs = prefs(context)
        val words = prefs.getString(KEY_PROBLEM, null) ?: return null
        prefs.edit { remove(KEY_PROBLEM) }
        return words
    }

    /** Whether Android lets this app install its update; from Android 8 it is a switch per app, off until turned on. */
    fun mayInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * Blocking: the update's file in the app's cache, its size and SHA-256 checked. Anything wrong deletes what arrived
     * and throws, with words for the TV.
     */
    fun download(context: Context, offer: Offer, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        deleteLeftovers(context)
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        // The download, and Android's own copy of it while it installs.
        if (freeSpace(context, folder) < offer.size * 2) {
            throw UpdateException(context.getString(R.string.update_no_space, megabytes(offer.size * 2)))
        }

        val file = File(folder, "TheDisplaySolution-${offer.code}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L

        try {
            val connection = open(downloadUrl(context, offer))
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateException(context.getString(R.string.update_server_said, connection.responseCode))
                }
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw UpdateException(context.getString(R.string.update_cancelled))
                            val read = input.read(buffer)
                            if (read < 0) break
                            received += read
                            if (received > offer.size) throw UpdateException(context.getString(R.string.update_damaged))
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            progress(received, offer.size)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (received != offer.size || !sha256.equals(offer.sha256, ignoreCase = true)) {
                throw UpdateException(context.getString(R.string.update_damaged))
            }

            return file
        } catch (e: IOException) {
            file.delete()
            throw e as? UpdateException ?: UpdateException(context.getString(R.string.update_no_connection))
        }
    }

    /**
     * Blocking: the file handed to Android's installer, which then asks the TV for one press (InstallResultReceiver).
     * The file is deleted as soon as Android has its own copy — and whatever happens.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)

        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                apk.delete()

                val result = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                // The installer writes the result into the intent, so from Android 12 it must be mutable; it is explicit.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw UpdateException(context.getString(R.string.update_install_failed, e.message ?: ""))
        } catch (e: SecurityException) {
            installer.abandonSession(id)
            throw UpdateException(context.getString(R.string.update_install_failed, e.message ?: ""))
        } finally {
            apk.delete()
        }
    }

    /**
     * The store this copy came from, by name, or null for one installed from the website (or updated by this app, which
     * is then its installer). A store signs the app with a key of its own — Amazon re-signs every app with one for the
     * developer's account — so a version from the website could never install over its copy, and the store keeps its
     * copy up to date anyway (owner, 2026-10-21: "Amazon khud kare"): such a copy never offers an update itself.
     */
    fun storeThatInstalled(context: Context): String? {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return STORES[installer]
    }

    /** Any update file left in the cache: a download cut short, or a version installed since. */
    fun deleteLeftovers(context: Context) {
        File(context.cacheDir, FOLDER).listFiles()?.forEach { it.delete() }
    }

    fun installedCode(context: Context): Long = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        PackageInfoCompat.getLongVersionCode(info)
    } catch (e: PackageManager.NameNotFoundException) {
        Long.MAX_VALUE
    }

    /**
     * A debug build only: the server to ask instead of the live one, for trying an update on an emulator against a local
     * server (adb shell am start -n com.thedisplaysolution.player/.MainActivity --es update_origin http://localhost:8001,
     * after adb reverse tcp:8001 tcp:8001). A release build never reads it.
     */
    fun setDebugOrigin(context: Context, origin: String?) {
        if (!isDebuggable(context)) return
        prefs(context).edit {
            if (origin.isNullOrBlank()) remove(KEY_DEBUG_ORIGIN) else putString(KEY_DEBUG_ORIGIN, origin.trimEnd('/'))
        }
    }

    private fun origin(context: Context): String =
        (if (isDebuggable(context)) prefs(context).getString(KEY_DEBUG_ORIGIN, null) else null) ?: PLAYER_ORIGIN

    /** On a debug build asking a local server, the file comes from that server too, whatever address its answer names. */
    private fun downloadUrl(context: Context, offer: Offer): String {
        val origin = origin(context)
        if (origin == PLAYER_ORIGIN) return offer.url
        val url = URL(offer.url)
        return origin + url.file
    }

    private fun parse(json: String, packageName: String): Offer? {
        val answer = JSONObject(json)
        if (answer.optString("package") != packageName) return null

        val code = answer.optLong("version_code", 0L)
        val name = answer.optString("version_name")
        val url = answer.optString("url")
        val size = answer.optLong("size", 0L)
        val sha256 = answer.optString("sha256")
        val notes = answer.optString("notes").takeIf { !answer.isNull("notes") && it.isNotBlank() }

        val valid = code > 0 && name.isNotBlank() && size > 0 && sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            (url.startsWith("https://") || url.startsWith("http://"))
        return if (valid) Offer(code, name, url, size, sha256, notes) else null
    }

    private fun open(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
        }

    private fun megabytes(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)

    /** What may be written there, counting the cache Android would clear to make room (Android 8 on). */
    private fun freeSpace(context: Context, folder: File): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val storage = context.getSystemService(StorageManager::class.java)
                if (storage != null) return storage.getAllocatableBytes(storage.getUuidForPath(folder))
            } catch (e: IOException) {
                // Not a volume Android can say it of: the plain count below.
            }
        }
        return folder.usableSpace
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
