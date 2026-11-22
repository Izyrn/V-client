package com.vclient.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vclient.android.data.Endpoint

/**
 * Hands the player over to Minecraft. Bedrock Edition is launched directly; Java Edition can't run inside VYRN,
 * so it is launched through PojavLauncher, a separate app the player installs.
 */
object Minecraft {
    const val PACKAGE = "com.mojang.minecraftpe"
    /** Mojang's official free Minecraft Trial app on Google Play. */
    const val TRIAL_PACKAGE = "com.mojang.minecrafttrialpe"
    const val TRIAL_INFO_URL = "https://www.minecraft.net/en-us/free-trial"
    const val JAVA_PACKAGE = "net.kdt.pojavlaunch"
    const val JAVA_LAUNCHER_URL = "https://github.com/PojavLauncherTeam/PojavLauncher/releases"

    /** An installed app's version name, or null when it isn't installed. Needs the package in the manifest queries. */
    fun installedVersion(context: Context, pkg: String): String? = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName ?: "installed"
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    sealed interface Result {
        data object Launched : Result
        data object NotInstalled : Result
    }

    /**
     * Opens Minecraft and connects straight to [server] with a `minecraft://connect` link. Older builds that don't
     * handle it fall back to the add-server link, so the player always ends up with VSMP in their server list.
     */
    fun joinBedrock(context: Context, name: String, server: Endpoint): Result {
        val connect = Uri.parse("minecraft://connect/?serverUrl=${Uri.encode(server.host)}&serverPort=${server.port}")
        if (open(context, connect)) return Result.Launched
        val add = Uri.parse("minecraft://?addExternalServer=${Uri.encode("$name|${server.address}")}")
        if (open(context, add)) return Result.Launched
        return Result.NotInstalled
    }

    /** Opens an installed app by package name. Returns false when it isn't installed. */
    fun launchApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Parses "host" or "host:port" (Bedrock default port 19132). Returns null for anything invalid. */
    fun parseAddress(input: String): Endpoint? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' }) return null
        val host = text.substringBeforeLast(':', text)
        val port = if (text.contains(':')) text.substringAfterLast(':').toIntOrNull() else 19132
        if (host.isEmpty() || port == null || port !in 1..65535) return null
        return Endpoint(host, port)
    }

    fun openStorePage(context: Context, pkg: String = PACKAGE) {
        val market = Uri.parse("market://details?id=$pkg")
        if (!open(context, market)) open(context, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
    }

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Opens http(s) links only; used for the Discord and website buttons whose URLs come from the backend. */
    fun openWebLink(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return open(context, uri)
    }

    private fun open(context: Context, uri: Uri): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
