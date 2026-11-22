package com.vclient.android.data

import com.vclient.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The backend answered with a message meant for the player (for example "Incorrect username or password"). */
class ApiException(message: String) : IOException(message)

/** Talks to the shared VYRN backend, the same one the Windows launcher and the website use. */
class ApiClient(baseUrl: String, private val token: String? = null) {
    val baseUrl: String = normalize(baseUrl)

    val configured: Boolean get() = isValidUrl(baseUrl)

    suspend fun serverConfig(): ServerConfig {
        val server = get("/api/config").getJSONObject("server")
        fun endpoint(key: String) = server.getJSONObject(key).let { Endpoint(it.getString("host"), it.getInt("port")) }
        return ServerConfig(
            name = server.optString("name", "VSMP"),
            java = endpoint("java"),
            bedrock = endpoint("bedrock"),
            discordUrl = server.optString("discordUrl"),
            websiteUrl = server.optString("websiteUrl"),
        )
    }

    suspend fun serverStatus(): ServerStatus {
        val root = get("/api/server/status")
        fun edition(key: String): EditionStatus {
            val node = root.optJSONObject(key) ?: return EditionStatus(false)
            if (!node.optBoolean("online")) return EditionStatus(false)
            val players = node.optJSONObject("players")
            return EditionStatus(
                online = true,
                players = players?.optInt("online") ?: 0,
                maxPlayers = players?.optInt("max") ?: 0,
                version = node.optString("version"),
                latencyMs = node.optLong("latencyMs"),
            )
        }
        return ServerStatus(edition("java"), edition("bedrock"))
    }

    suspend fun news(limit: Int = 30, category: String? = null): List<NewsPost> {
        val query = "?limit=$limit" + (category?.let { "&category=$it" } ?: "")
        val items = get("/api/news$query").getJSONArray("items")
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            NewsPost(
                id = item.getInt("id"),
                title = item.getString("title"),
                body = item.getString("body"),
                category = item.optString("category", "launcher"),
                pinned = item.optBoolean("pinned"),
                createdAt = item.optString("createdAt"),
            )
        }
    }

    suspend fun announcements(): List<Announcement> {
        val items = get("/api/announcements").getJSONArray("items")
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            Announcement(item.getInt("id"), item.getString("message"), item.optString("level", "info"))
        }
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo {
        val root = get("/api/launcher/update?platform=android&current=${encode(currentVersion)}")
        val latest = root.optJSONObject("latest")?.let {
            Release(
                version = it.getString("version"),
                notes = it.optString("notes"),
                mandatory = it.optBoolean("mandatory"),
                size = it.optLong("size"),
                sha256 = it.optString("sha256"),
                url = absolute(it.optString("url")),
            )
        }
        return UpdateInfo(root.optBoolean("updateAvailable"), root.optBoolean("mandatory"), latest)
    }

    suspend fun login(login: String, password: String): Session =
        session(send("POST", "/api/auth/login", JSONObject().put("login", login).put("password", password)))

    suspend fun register(username: String, email: String, password: String): Session = session(
        send("POST", "/api/auth/register", JSONObject().put("username", username).put("email", email).put("password", password)),
    )

    suspend fun profile(): Session = userSession(send("GET", "/api/auth/me").getJSONObject("user"), token.orEmpty())

    suspend fun linkNames(minecraftName: String, elybyName: String): Session = userSession(
        send("PUT", "/api/auth/profile", JSONObject().put("minecraftName", minecraftName).put("elybyName", elybyName))
            .getJSONObject("user"),
        token.orEmpty(),
    )

    /** Anonymous usage counter. Errors are swallowed on purpose: statistics must never disturb the player. */
    suspend fun reportEvent(installId: String, event: String) {
        if (!configured) return
        runCatching {
            send(
                "POST", "/api/stats/event",
                JSONObject().put("installId", installId).put("platform", "android")
                    .put("version", BuildConfig.VERSION_NAME.substringBefore('-')).put("event", event),
            )
        }
    }

    private fun session(root: JSONObject) = userSession(root.getJSONObject("user"), root.getString("token"))

    private fun userSession(user: JSONObject, token: String) = Session(
        token = token,
        username = user.getString("username"),
        minecraftName = user.optString("minecraftName"),
        elybyName = user.optString("elybyName"),
    )

    private fun absolute(path: String): String = when {
        path.isEmpty() -> ""
        path.startsWith("http") -> path
        else -> baseUrl + path
    }

    private suspend fun get(path: String) = send("GET", path)

    private suspend fun send(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (!configured) throw ApiException("Set the VYRN backend address in Settings.")
            val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "VYRN/${BuildConfig.VERSION_NAME} (android)")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                // A web host that isn't running the VYRN backend answers with an empty or HTML page.
                val notBackend = "The VYRN server isn't available right now. Try again later, or check the backend address in Settings."
                if (status < 400 && text.isBlank()) throw IOException(notBackend)
                val json = try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (e: org.json.JSONException) {
                    throw IOException(notBackend, e)
                }
                if (status >= 400) throw ApiException(json.optString("error").ifBlank { "The backend returned HTTP $status." })
                json
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        fun normalize(url: String): String = url.trim().trimEnd('/')

        /** Only http(s) addresses with a host are accepted, so a typo can't point the app at a file or script. */
        fun isValidUrl(url: String): Boolean = runCatching {
            val parsed = java.net.URI(normalize(url))
            !parsed.host.isNullOrEmpty() && (parsed.scheme == "https" || parsed.scheme == "http")
        }.getOrDefault(false)

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
