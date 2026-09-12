package com.bayanasar.inkverse

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update straight from GitHub Releases.
 *
 * Sideloaded apps get no store to update them, and this one is easy to forget about
 * precisely because it works silently in the background.
 *
 * Installation goes through PackageInstaller's session API rather than an
 * ACTION_VIEW intent on a file:// URI, which would need a FileProvider and therefore
 * AndroidX — this app has no dependencies and is not about to grow one for this.
 *
 * Updating in place only works because releases are signed with a stable key; CI
 * falls back to a throwaway key when the signing secrets are absent, and APKs built
 * that way can only be installed after uninstalling.
 */
object Updater {

    private const val API = "https://api.github.com/repos/bayanasar/inkverse/releases/latest"
    const val INSTALL_ACTION = "com.bayanasar.inkverse.INSTALL_RESULT"

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    data class Release(val version: String, val apkUrl: String, val notes: String)

    /** Result is delivered on the main thread; [onResult] gets null when up to date. */
    fun check(context: Context, onResult: (Release?) -> Unit, onError: (String) -> Unit) {
        io.execute {
            try {
                val latest = fetchLatest()
                val current = currentVersion(context)
                val newer = latest != null && isNewer(latest.version, current)
                Log.i(TAG, "update check: current=$current latest=${latest?.version} newer=$newer")
                ui.post { onResult(if (newer) latest else null) }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: $e")
                ui.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.execute {
            try {
                install(context, release, onProgress)
                // The system installer UI takes over from here; success is reported
                // by the session, not by this call returning.
                ui.post { onDone(true, "Confirm the install when the system asks.") }
            } catch (e: Exception) {
                Log.e(TAG, "update failed: $e")
                ui.post { onDone(false, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    private fun fetchLatest(): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Inkverse")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode != 200) throw Exception("GitHub returned ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    return Release(
                        version = json.getString("tag_name").removePrefix("v"),
                        apkUrl = asset.getString("browser_download_url"),
                        notes = json.optString("body", "").take(600),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Numeric dotted comparison, so 0.1.10 sorts above 0.1.9. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun install(context: Context, release: Release, onProgress: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Inkverse")
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            try {
                if (conn.responseCode != 200) throw Exception("download returned ${conn.responseCode}")
                val total = conn.contentLengthLong
                var written = 0L
                session.openWrite("inkverse", 0, total).use { out ->
                    BufferedInputStream(conn.inputStream).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt()
                                ui.post { onProgress(pct) }
                            }
                        }
                    }
                    session.fsync(out)
                }
            } finally {
                conn.disconnect()
            }

            val intent = Intent(INSTALL_ACTION)
                .setClass(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
            Log.i(TAG, "install session committed for ${release.version}")
        }
    }
}
