package com.vclient.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vclient.android.data.ApiClient
import com.vclient.android.data.Release
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Downloads a launcher APK from the VYRN backend, verifies it, and opens Android's installer. */
object Updater {
    /** True when [candidate] is a newer dotted-numeric version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        val left = a.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * Downloads the APK into the app's cache. The download is refused unless it comes from the configured backend
     * and matches the size and SHA-256 the backend published, so a tampered file is never handed to the installer.
     */
    suspend fun download(context: Context, release: Release, backendUrl: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val source = URL(release.url)
            val backendHost = URL(ApiClient.normalize(backendUrl)).host
            if (source.host != backendHost) throw IOException("The update is not hosted by your VYRN backend.")
            if (!source.path.endsWith(".apk")) throw IOException("The update file is not an Android package.")

            val directory = File(context.cacheDir, "updates").apply {
                deleteRecursively()
                mkdirs()
            }
            val target = File(directory, "VYRN-${release.version}.apk")
            val partial = File(directory, target.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = source.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                if (connection.responseCode != 200) throw IOException("Update download failed (HTTP ${connection.responseCode}).")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                var received = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            if (total > 0) onProgress((received.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
                if (release.size > 0 && received != release.size) {
                    throw IOException("The downloaded update is the wrong size ($received of ${release.size} bytes).")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256.isNotBlank() && !actual.equals(release.sha256, ignoreCase = true)) {
                    throw IOException("The downloaded update failed its SHA-256 check and was discarded.")
                }
                if (!partial.renameTo(target)) throw IOException("Could not save the update.")
                target
            } catch (e: Throwable) {
                partial.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Opens the system installer for [apk]. Returns false when Android first needs the player to allow installs
     * from VYRN; the settings screen is opened and the player taps Update again afterwards.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
