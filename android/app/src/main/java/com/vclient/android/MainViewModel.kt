package com.vclient.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vclient.android.data.Announcement
import com.vclient.android.data.ApiClient
import com.vclient.android.data.Endpoint
import com.vclient.android.data.JavaVersions
import com.vclient.android.data.NewsPost
import com.vclient.android.data.PlayMode
import com.vclient.android.data.Prefs
import com.vclient.android.data.SavedServer
import com.vclient.android.data.ServerConfig
import com.vclient.android.data.ServerPing
import com.vclient.android.data.ServerStatus
import com.vclient.android.data.Session
import com.vclient.android.data.UpdateInfo
import com.vclient.android.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Single source of truth for the screens. Every network call is optional: failures show a message, never crash. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = Prefs(application)

    var backendUrl by mutableStateOf(prefs.backendUrl); private set
    var autoUpdate by mutableStateOf(prefs.autoUpdate); private set
    var serverConfig by mutableStateOf(prefs.serverConfig); private set

    var servers by mutableStateOf(prefs.savedServers); private set

    var playMode by mutableStateOf(prefs.playMode); private set
    var javaVersion by mutableStateOf(prefs.javaVersion.ifBlank { JavaVersions.FALLBACK.first() }); private set
    var javaVersions by mutableStateOf(JavaVersions.FALLBACK); private set
    var vsmpAddress by mutableStateOf(prefs.vsmpAddress); private set

    /** The VSMP address the one-click button uses: the player's own override, else the server's published one. */
    val vsmp: Endpoint
        get() = Minecraft.parseAddress(vsmpAddress) ?: serverConfig.bedrock

    var status by mutableStateOf<ServerStatus?>(null); private set
    var statusError by mutableStateOf<String?>(null); private set
    var statusLoading by mutableStateOf(false); private set

    var announcements by mutableStateOf<List<Announcement>>(emptyList()); private set

    var news by mutableStateOf<List<NewsPost>>(emptyList()); private set
    var newsError by mutableStateOf<String?>(null); private set
    var newsLoading by mutableStateOf(false); private set
    var newsFilter by mutableStateOf<String?>(null); private set

    var session by mutableStateOf<Session?>(null); private set
    var accountBusy by mutableStateOf(false); private set
    var accountMessage by mutableStateOf<String?>(null); private set

    var update by mutableStateOf<UpdateInfo?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    var updateMessage by mutableStateOf<String?>(null); private set
    private var downloadedApk: File? = null
    private var updateJob: Job? = null

    private fun client(withToken: Boolean = false) =
        ApiClient(backendUrl, if (withToken) prefs.token.ifBlank { null } else null)

    val backendConfigured: Boolean get() = client().configured

    init {
        refreshAll()
        if (prefs.token.isNotBlank()) loadProfile()
        if (autoUpdate) checkForUpdate(manual = false)
        viewModelScope.launch { client().reportEvent(prefs.installId, "launch") }
        viewModelScope.launch {
            javaVersions = JavaVersions.load()
            // Follow Mojang's newest release until the player picks one themselves.
            if (prefs.javaVersion.isBlank()) javaVersion = javaVersions.first()
        }
    }

    fun updatePlayMode(mode: PlayMode) {
        prefs.playMode = mode
        playMode = mode
    }

    fun updateJavaVersion(version: String) {
        prefs.javaVersion = version
        javaVersion = version
    }

    /** Returns an error message, or null when saved. An empty value goes back to the server's own address. */
    fun saveVsmpAddress(value: String): String? {
        val text = value.trim()
        if (text.isNotEmpty() && Minecraft.parseAddress(text) == null) {
            return "Enter an address like play.example.com or play.example.com:19132"
        }
        prefs.vsmpAddress = text
        vsmpAddress = text
        return null
    }

    fun refreshAll() {
        refreshNews()
        viewModelScope.launch {
            val api = client()
            if (!api.configured) return@launch
            runCatching { api.serverConfig() }.onSuccess {
                serverConfig = it
                prefs.serverConfig = it
            }
            runCatching { api.announcements() }.onSuccess { announcements = it }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val api = client()
            statusLoading = true
            // Prefer the backend (cached for everyone); if it is down, ask the game server directly.
            val viaBackend = if (api.configured) runCatching { api.serverStatus() } else null
            val result = viaBackend?.getOrNull()
                ?: ServerPing.status(serverConfig.java, serverConfig.bedrock)
            status = result
            statusError = if (result.anyOnline) null else "VSMP didn't answer. It may be offline, or your connection is blocking it."
            statusLoading = false
        }
    }

    fun refreshNews() {
        viewModelScope.launch {
            val api = client()
            if (!api.configured) {
                newsError = "Set the VYRN backend address in Settings."
                return@launch
            }
            newsLoading = true
            runCatching { api.news(category = newsFilter) }
                .onSuccess { news = it; newsError = null }
                .onFailure { if (it !is CancellationException) newsError = it.message ?: "News could not be loaded." }
            newsLoading = false
        }
    }

    fun filterNews(category: String?) {
        newsFilter = category
        refreshNews()
    }

    /** Returns an error message, or null when the server was added. */
    fun addServer(name: String, address: String): String? {
        val endpoint = Minecraft.parseAddress(address) ?: return "Enter an address like play.example.com or play.example.com:19132"
        val label = name.trim().ifEmpty { endpoint.host }.take(40)
        servers = (servers + SavedServer(label, endpoint)).also { prefs.savedServers = it }
        return null
    }

    fun removeServer(server: SavedServer) {
        servers = (servers - server).also { prefs.savedServers = it }
    }

    fun reportJoin() {
        viewModelScope.launch { client().reportEvent(prefs.installId, "join") }
    }

    // ---- Account ----

    private fun loadProfile() {
        viewModelScope.launch {
            runCatching { client(withToken = true).profile() }
                .onSuccess { session = it }
                .onFailure {
                    // An expired or revoked token comes back as an ApiException; a plain network error keeps the token.
                    if (it is com.vclient.android.data.ApiException) prefs.token = ""
                }
        }
    }

    fun signIn(login: String, password: String) = authenticate { it.login(login.trim(), password) }

    fun register(username: String, email: String, password: String) =
        authenticate { it.register(username.trim(), email.trim(), password) }

    private fun authenticate(call: suspend (ApiClient) -> Session) {
        viewModelScope.launch {
            accountBusy = true
            accountMessage = null
            runCatching { call(client()) }
                .onSuccess { prefs.token = it.token; session = it }
                .onFailure { accountMessage = it.message ?: "Could not sign in." }
            accountBusy = false
        }
    }

    fun saveLinkedNames(minecraftName: String, elybyName: String) {
        viewModelScope.launch {
            accountBusy = true
            runCatching { client(withToken = true).linkNames(minecraftName.trim(), elybyName.trim()) }
                .onSuccess { session = it; accountMessage = "Linked names saved." }
                .onFailure { accountMessage = it.message ?: "Could not save." }
            accountBusy = false
        }
    }

    fun signOut() {
        prefs.token = ""
        session = null
        accountMessage = null
    }

    // ---- Settings ----

    /** Returns an error message, or null when the address was saved. */
    fun saveBackendUrl(value: String): String? {
        val address = ApiClient.normalize(value)
        if (address.isNotEmpty() && !ApiClient.isValidUrl(address)) {
            return "Enter a full address such as https://vclient.example.com"
        }
        prefs.backendUrl = address
        backendUrl = prefs.backendUrl
        refreshAll()
        return null
    }

    fun updateAutoUpdate(enabled: Boolean) {
        prefs.autoUpdate = enabled
        autoUpdate = enabled
    }

    // ---- Updates ----

    fun checkForUpdate(manual: Boolean) {
        viewModelScope.launch {
            val api = client()
            if (!api.configured) {
                if (manual) updateMessage = "Set the VYRN backend address first."
                return@launch
            }
            runCatching { api.checkForUpdate(BuildConfig.VERSION_NAME.substringBefore('-')) }
                .onSuccess {
                    update = it.takeIf { info -> info.updateAvailable && info.latest != null }
                    if (manual) updateMessage = if (update == null) "You are on the latest version." else null
                }
                .onFailure { if (manual) updateMessage = it.message ?: "Could not check for updates." }
        }
    }

    fun dismissUpdate() {
        if (update?.mandatory != true) update = null
    }

    fun clearUpdateMessage() {
        updateMessage = null
    }

    /** Downloads the update, then asks Android to install it. Calling again after a permission prompt reuses the file. */
    fun installUpdate(context: android.content.Context) {
        val release = update?.latest ?: return
        if (updateJob?.isActive == true) return
        downloadedApk?.takeIf { it.exists() }?.let {
            if (!Updater.install(context, it)) updateMessage = "Allow VYRN to install apps, then tap Update again."
            return
        }
        updateJob = viewModelScope.launch {
            updateProgress = 0f
            updateMessage = null
            runCatching { Updater.download(context, release, backendUrl) { updateProgress = it } }
                .onSuccess {
                    downloadedApk = it
                    if (!Updater.install(context, it)) updateMessage = "Allow VYRN to install apps, then tap Update again."
                }
                .onFailure { if (it !is CancellationException) updateMessage = it.message ?: "Update failed." }
            updateProgress = null
        }
    }
}
