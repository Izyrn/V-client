# VYRN

VYRN is the launcher ecosystem for the **VSMP** Minecraft server: a Windows desktop launcher, an Android app, one shared backend, and a download website.

```
V-client/
├── src/ , pom.xml , package-release.ps1   Windows launcher (Java 21 + JavaFX) → VYRN.exe
├── mod/                                   In-game Fabric mod bundled with the Windows launcher
├── android/                               Android app (Kotlin + Jetpack Compose, Material 3) → .apk
├── backend/                               Shared REST API + management API + download host (Node.js, SQLite)
├── website/                               Download site (home, downloads, features, news)
└── tools/                                 Windows setup-exe packaging
```

| Piece | What it gives players |
| --- | --- |
| Windows launcher | Play / one-click **JOIN OUR SMP**, **Cosmetics** page, version selector, Ely.by / Microsoft / offline accounts, RAM + Java settings, **News** page, **Error logs** page, live VSMP status, automatic updates |
| In-game mod | Right Shift menu with HUD modules, plus a **Cosmetics** menu (press K): capes, wings, hats, emotes, animated pieces and particle trails with live preview, Free / Premium / Event tiers, and a server-driven catalog ([docs/COSMETICS_API.md](docs/COSMETICS_API.md)) |
| Android app | **PLAY VSMP** (opens Minecraft Bedrock and connects), server status + player count, news, VYRN account, Discord/website buttons, in-app updates |
| Backend | One API for both apps and the site: accounts, news, announcements, launcher version checks and installer hosting, VSMP status (Java + Bedrock ping), anonymous usage statistics |
| Website | Download buttons for `.exe` and `.apk`, features, VSMP status, news |

## Quick start (everything on your own PC)

1. **Backend + website.** Needs Node.js 22.13 or newer.
   ```powershell
   cd backend
   npm install
   copy .env.example .env      # optional: edit ports, VSMP addresses, Discord link
   npm start
   ```
   On first start it prints a generated admin password once (or uses `ADMIN_PASSWORD` from `.env`). Open <http://localhost:8080> for the site.
2. **Point the apps at it.** Windows: open **News** → *Backend & updates* and save the address. Android: **Settings → Backend**. Or bake the address into a release build (see below).
3. **Publish news or a launcher update** with the management API (curl examples in [backend/README.md](backend/README.md)): create a release for `windows` or `android`, upload the installer, publish it. Apps with a lower version are offered the update; set *Minimum supported version* or *Mandatory* to force it.

Full backend reference, deployment notes and the API list: [backend/README.md](backend/README.md). Android details: [android/README.md](android/README.md).

## Build the Windows executable (.exe)

Requirements: Windows, 64-bit **Azul Zulu JDK 21** with `JAVA_HOME` set, Maven on `PATH`.

```powershell
# optional: bake your backend address into the build (AppInfo.DEFAULT_BACKEND_URL), then:
.\package-release.ps1
```

Output: `Release\VYRN.exe` (+ `Release\app`, `Release\runtime`) and `VYRN.zip`. To produce the single-file setup installer used by the website and the auto-updater, run `tools\package-standalone-installer.ps1`, then upload the resulting setup `.exe` as the Windows release (see backend/README.md). Run the launcher from source with `mvn javafx:run`, and the tests with `mvn test`.

Before publishing a new Windows build, raise `AppInfo.VERSION` in [AppInfo.java](src/main/java/com/vclient/AppInfo.java) and publish the same version number through the API; the updater compares them.

## Build the Android APK (.apk)

Requirements: Android Studio (Ladybug or newer) or JDK 17+ with the Android SDK (API 35).

```powershell
cd android
.\gradlew.bat assembleDebug                                   # android\app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease -Pvclient.backendUrl=https://vclient.example.com
```

Release builds must be signed to be installable: create `android/keystore.properties` (see [android/README.md](android/README.md)), then the signed `app-release.apk` appears in `android\app\build\outputs\apk\release\`. Raise `versionCode` / `versionName` in `android/app/build.gradle.kts` for every update you publish.

## Shared behaviour

- **Branding:** the same midnight-navy + gold palette and V logo on the site, Windows launcher and Android app.
- **Accounts:** one VYRN account (username/email + password) works in both apps and on the site; it can link a Minecraft and an Ely.by name. Game logins (Microsoft, Ely.by, offline) remain Windows-launcher features.
- **News + announcements:** posted once through the API, shown in both apps and on the site.
- **Updates:** both apps call `GET /api/launcher/update?platform=…&current=…`; downloads are verified against the SHA-256 the backend stores.
- **Server status:** the backend pings VSMP (Java Server List Ping and Bedrock RakNet) and caches the result for 20 s, so thousands of launchers cost the game server one ping.

---

## Windows launcher in detail

VYRN is a native Windows desktop launcher built with Java 21 and JavaFX. It installs and launches Minecraft inside its own private game folder, can launch installed vanilla, Fabric, or Forge profiles, lets you choose the game's maximum memory, and saves launcher preferences locally.

The launcher uses a live, animated pixel-night background with twinkling and drifting stars, a glowing crescent moon, and layered dusk hills. The midnight navy and violet interface uses warm gold highlights and translucent panels; the animation is drawn natively with JavaFX and does not load a web page or remote background.

The left navigation provides Home, Library, Mods, Boost, Wardrobe, Friends, Console, Settings, and Discord pages. Minecraft game files are kept in VYRN's private `%APPDATA%\VYRN\minecraft` folder; account/settings data stays in `%APPDATA%\VYRN`. The Home version dropdown refreshes the Mojang catalog dynamically and lists its latest release, latest snapshot, all available releases/snapshots, and installed loader profiles with Installed, Not Installed, or Downloading status. Selecting a version that is not installed automatically downloads its manifest, client, libraries, assets, and required Java runtime into VYRN's private folder. **REFRESH VERSIONS** reloads the catalog and installed profiles. VYRN does not need another launcher's folder. **IMPORT EXISTING GAME FILES** in Settings is optional; it copies local `versions`, `libraries`, and `assets` without deleting the source. Library scans only VYRN's private game folder. Mods lets you pick an installed Fabric, Forge, NeoForge, or Quilt profile, search Modrinth for compatible mods, and click **INSTALL** to download the verified JAR straight into the game folder's `mods` directory (created automatically on the first install); it also lists installed mods and can open the folder or remove a mod. To play a server, select the version supported by that server and use **JOIN OUR SMP**. Boost saves the Minecraft memory limit and does not modify Windows or promise FPS gains. Wardrobe previews the skin and cape already saved on the selected account. Friends is a local-only list of Minecraft usernames, saved in `%APPDATA%\VYRN\friends.json`. Console shows the current session's launcher/game output and can copy or clear it. Discord lets you save and open an invite URL you provide; VYRN does not invent or automatically contact an invite.

## Download and run

Download `VYRN.zip` and extract the complete archive to a folder. Open the extracted `Release` folder and double-click `VYRN.exe`. Keep the `app` and `runtime` folders next to the executable; they contain files required to run the launcher and Minecraft. The included runtimes mean you do not need to install Java.

On first launch, add a Microsoft Premium, Ely.by, or offline account. Microsoft uses Microsoft's device-code sign-in; Ely.by uses its authentication service and supports two-factor verification. Ely.by sign-in completes independently of skin/cape lookups, which load in the background; a skin-service delay will not block account sign-in. Offline accounts use a username and generated offline UUID. Multiple accounts can be saved, switched, and removed in Settings. Microsoft and Ely.by access tokens are encrypted with Windows DPAPI for the current Windows user; account passwords are never saved. Minecraft requires a valid Premium account for online-mode servers. Offline accounts are for offline-mode servers and single-player.

If Windows DPAPI takes longer than 15 seconds to save a newly signed-in account, the launcher reports that sign-in succeeded and keeps the account available for the current session; the secure save continues in the background. If Windows eventually completes the save, the launcher confirms it. If secure storage remains blocked, the account is not guaranteed to persist after closing the launcher.

The Windows release includes one Azul Zulu Java 21 x64 runtime, used by both the launcher and Minecraft versions that need Java 21 (including the VYRN 1.21.11 profile). Build the release with `JAVA_HOME` set to an Azul Zulu JDK 21 so the bundled runtime passes the launcher's vendor check. Minecraft 1.21.1 uses Java 25. Settings can use the Minecraft profile's default runtime (Java 25 is automatic for 1.21.1) or force Java 25 for every version. VYRN detects compatible Java 25 installations in `JAVA_HOME`, `PATH`, and common Windows installation folders; when Java 25 is not installed, it downloads the official Azul Zulu x64 JRE into `%APPDATA%\VYRN\runtimes`. For versions requiring Java 8, 17, or 21, VYRN downloads the matching official Azul Zulu x64 JRE if needed. Downloaded runtimes are checked for vendor, version, and architecture before launching.

Installed vanilla, Fabric, and Forge profiles in VYRN's private folder appear in the version list. You can choose a different existing game folder in Settings or optionally import its `versions`, `libraries`, and `assets` into VYRN's folder. Profile folder names with spaces, including `Fabric 1.21.11`, are supported. **JOIN OUR SMP** launches with the selected account as-is and passes `neuron.zeroxhost.space:19145` to Minecraft for automatic multiplayer connection. Neither main button refreshes account credentials. Missing Minecraft files and Java runtimes are downloaded into VYRN's own folders when needed; Minecraft itself must connect to the SMP to join it, and server authentication requirements still apply. When using an Ely.by account, VYRN uses the cached authlib-injector with Ely.by's `https://authserver.ely.by` API URL, downloading it into VYRN's libraries folder if needed.

**PLAY** starts the selected profile for singleplayer using whichever account is selected, including Ely.by or Offline. If nothing is installed, it installs the latest stable Vanilla release first. Missing version files, libraries, assets, and Java runtimes are downloaded as needed. An optional logging configuration can be omitted when it is not available. Minecraft itself may still make network connections, such as connecting to the selected multiplayer server; VYRN does not change Windows firewall settings or promise to block the game's network traffic.

For launch troubleshooting, VYRN writes Java version details, redacted JVM/game arguments, manifest and classpath validation, library/asset progress, and Minecraft process output to `%APPDATA%\VYRN\minecraft\logs\vclient-launch.log` (or the configured game folder's `logs` directory). Failed launches show the underlying exception and include recent Minecraft output and the latest crash report when present. Authentication tokens are redacted from recorded launch arguments.

VYRN explicitly enables TLS 1.3 and TLS 1.2 and validates HTTPS certificate chains against both the Windows trusted-root store and Java's standard trust store. Mojang and Azul metadata/download requests retry transient connection failures and temporary HTTP server responses; certificate/TLS failures are not retried as transient errors. System proxy settings are preferred, with HTTP(S)_PROXY environment variables detected as a fallback. Proxy credentials are never written to the launcher log.

Use **Test Connection** on the home screen to check DNS resolution, general HTTPS connectivity, Mojang's certificate chain, and the official Mojang version manifest. The dialog and launch output show the selected proxy route, enabled TLS protocols, response status, and underlying network/TLS error. If HTTPS inspection by a proxy, firewall, or antivirus causes a certificate failure, configure that product's verified root certificate in the Windows trusted store or ask your administrator; VYRN never disables certificate verification.

**OFFLINE MODE** on the Home screen makes VYRN contact no one: it skips the Mojang version list, Modrinth, skin/cape downloads, and all game-file and Java downloads, and launches installed versions using only local files. Turn it off once if you still need to download a version or Java runtime. Even with Offline mode off, if Mojang cannot be reached the version list falls back to your installed versions instead of blocking you.

The first time you run the unsigned application, Windows SmartScreen may ask you to confirm that you trust it.

## Build the complete Windows release

On Windows, install a 64-bit JDK 21 with `JAVA_HOME` set and Maven on `PATH`. In PowerShell, run:

```powershell
.\package-release.ps1
```

This compiles the project without deleting Maven's existing dependency files, builds the self-contained Windows app image with its JavaFX application, Java runtime, and custom icon, stages the complete release in `Release`, copies the source into `Release\Source`, and generates `VYRN.zip` in the repository root. Existing files in `Release` that are not part of the new build are preserved. The resulting executable is `Release\VYRN.exe`. Build on Windows; `jpackage` produces an app for its host operating system.

Run the desktop launcher directly from source with:

```powershell
mvn javafx:run
```

## Local data

Settings are saved as JSON in `%APPDATA%\VYRN\settings.json`. Microsoft and Ely.by credentials are DPAPI-protected in `%APPDATA%\VYRN\accounts.dat` and can only be decrypted by the same Windows user on the same device. Offline usernames are saved separately in `%APPDATA%\VYRN\offline-accounts.json`; they contain no passwords or access tokens.

## VYRN in-game mod (Minecraft 1.21.11)

When Minecraft 1.21.11 is installed in VYRN's game folder, the launcher adds a **VYRN 1.21.11** profile and selects it by default. The profile runs Fabric Loader with the VYRN mod, and the launcher copies both from files bundled inside it, so nothing is downloaded. Mods installed from the Mods tab load in this profile too.

- Title screen and menus use the launcher's animated night sky (stars, crescent moon, comet, hills).
- **Right Shift** in game opens the VYRN mod menu: FPS, CPS, Ping, Coordinates, Speed, Clock, Memory, Day Counter, Server Address, Potion Effects, Keystrokes, Armor Status, Zoom, Fullbright, No Hurt Cam, Toggle Sprint, Toggle Sneak, and the night-sky background. Click a card to toggle it, or its gear to open its settings: every module has a toggle key, HUD elements have size, background, and label color, and modules have their own options (for example Zoom's key, level, smooth zoom, cinematic camera, and scroll-to-zoom). The mod menu key itself can be changed on the VYRN card. **EDIT HUD** lets you drag HUD elements, and **RESET HUD** restores default positions. Settings are saved in the game folder's `config/vclient.json`.

The mod's source is in `mod/`. `mod/build-mod.sh` (Git Bash, with `JAVA_HOME` set to a JDK 21) builds Fabric Loader 0.19.5 and its tools from source using only GitHub and Maven Central, compiles the mod against the local 1.21.11 client jar with Yarn names, and stages the result in `src/main/resources/vclient-fabric`. Run it before `package-release.ps1` whenever the mod changes.
