$ErrorActionPreference = "Stop"

$repoRoot = $PSScriptRoot
$sourceDirectory = Join-Path $repoRoot "src"
$iconPath = Join-Path $sourceDirectory "main\resources\vclient.ico"
$releaseDirectory = Join-Path $repoRoot "Release"
$zipPath = Join-Path $repoRoot "AzyrClient.zip"
$appImageParent = Join-Path $repoRoot "target\release-app-image"
$releaseStaging = Join-Path $repoRoot "target\release-staging"
$jpackageInput = Join-Path $repoRoot "target\release-input"
$javafxModules = Join-Path $repoRoot "target\javafx-modules"
$zipStaging = Join-Path $repoRoot "target\AzyrClient.zip.tmp"
$javaHome = $env:JAVA_HOME
# One runtime serves both the launcher and Minecraft 1.21.11, so it carries the full
# Java SE platform plus the jdk.* modules Minecraft, Fabric, and Netty use.
$runtimeModules = @(
    "javafx.controls", "javafx.media", "javafx.web", "java.se", "jdk.accessibility", "jdk.charsets", "jdk.crypto.ec", "jdk.crypto.cryptoki",
    "jdk.crypto.mscapi", "jdk.dynalink", "jdk.httpserver", "jdk.jsobject", "jdk.management",
    "jdk.naming.dns", "jdk.net", "jdk.nio.mapmode", "jdk.random", "jdk.security.auth", "jdk.unsupported", "jdk.zipfs"
) -join ","

if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\jpackage.exe"))) {
    throw "Set JAVA_HOME to a 64-bit Azul Zulu JDK 21 installation before building the release."
}
$javaRelease = Join-Path $javaHome "release"
if (-not (Test-Path $javaRelease) -or ((Get-Content -Raw $javaRelease) -notmatch 'IMPLEMENTOR="Azul Systems') -or ((Get-Content -Raw $javaRelease) -notmatch 'JAVA_VERSION="21\.')) {
    throw "JAVA_HOME must be an Azul Zulu JDK 21: V-Client also runs Minecraft on the bundled runtime and only accepts Azul Zulu builds."
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven must be installed and available on PATH."
}
if (-not (Test-Path $iconPath)) {
    throw "The custom Windows launcher icon is missing: $iconPath"
}

Push-Location $repoRoot
try {
    & mvn -B -ntp package
    if ($LASTEXITCODE -ne 0) {
        throw "The Maven build failed with exit code $LASTEXITCODE."
    }

    $runtimeDependencies = Join-Path $repoRoot "target\app"
    if (-not (Test-Path (Join-Path $runtimeDependencies "V-Client.jar"))) {
        throw "The Maven build did not produce the launcher JAR."
    }


    if (Test-Path $appImageParent) {
        Remove-Item -LiteralPath $appImageParent -Recurse -Force
    }
    if (Test-Path $releaseStaging) {
        Remove-Item -LiteralPath $releaseStaging -Recurse -Force
    }
    if (Test-Path $zipStaging) {
        Remove-Item -LiteralPath $zipStaging -Force
    }
    New-Item -ItemType Directory -Path $jpackageInput, $javafxModules -Force | Out-Null

    Get-ChildItem -LiteralPath $runtimeDependencies -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "javafx-*" } |
        Copy-Item -Destination $jpackageInput -Force
    $platformJavaFx = Get-ChildItem -LiteralPath $runtimeDependencies -Filter "javafx-*-win.jar" -File
    if (-not $platformJavaFx) {
        throw "The Maven build did not produce Windows JavaFX modules."
    }
    Copy-Item -LiteralPath $platformJavaFx.FullName -Destination $javafxModules -Force

    $modulePath = "$javafxModules;$javaHome\jmods"
    & (Join-Path $javaHome "bin\jpackage.exe") `
        --type app-image `
        --name V-Client `
        --app-version 1.0.0 `
        --icon $iconPath `
        --input $jpackageInput `
        --main-jar "V-Client.jar" `
        --main-class "com.vclient.LauncherApp" `
        --module-path $modulePath `
        --add-modules $runtimeModules `
        --jlink-options "--strip-debug --no-man-pages --no-header-files" `
        --java-options "--add-modules=javafx.controls,javafx.media,javafx.web,java.desktop,java.logging,java.net.http,jdk.crypto.mscapi,jdk.crypto.ec" `
        --dest $appImageParent
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE."
    }

    $packagedApplication = Join-Path $appImageParent "V-Client"
    if (-not (Test-Path (Join-Path $packagedApplication "V-Client.exe"))) {
        throw "jpackage did not produce a runnable V-Client.exe."
    }

    New-Item -ItemType Directory -Path $releaseStaging | Out-Null
    Copy-Item -Path (Join-Path $packagedApplication "*") -Destination $releaseStaging -Recurse
    $bundledJava = Join-Path $releaseStaging "runtime\bin\java.exe"
    if (-not (Test-Path $bundledJava)) {
        throw "The packaged runtime has no java.exe, so Minecraft could not use it."
    }
    Copy-Item -LiteralPath $iconPath -Destination $releaseStaging
    Copy-Item -LiteralPath (Join-Path $repoRoot "README.md") -Destination $releaseStaging
    $releaseSource = Join-Path $releaseStaging "Source"
    New-Item -ItemType Directory -Path $releaseSource | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot "pom.xml"), (Join-Path $repoRoot "README.md"), `
        (Join-Path $repoRoot ".gitignore"), (Join-Path $repoRoot "package-release.ps1") `
        -Destination $releaseSource
    Copy-Item -LiteralPath $sourceDirectory -Destination $releaseSource -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot "V-Client") -Destination $releaseSource

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $output = [System.IO.File]::Open($zipStaging, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new($output,
            [System.IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            $files = Get-ChildItem -LiteralPath $releaseStaging -File -Recurse -Force
            foreach ($file in $files) {
                $relativePath = $file.FullName.Substring($releaseStaging.Length).TrimStart('\').Replace('\', '/')
                $entry = $archive.CreateEntry("Release/$relativePath",
                    [System.IO.Compression.CompressionLevel]::Optimal)
                $entryStream = $entry.Open()
                $input = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open,
                    [System.IO.FileAccess]::Read,
                    [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete)
                try {
                    $input.CopyTo($entryStream)
                }
                finally {
                    $input.Dispose()
                    $entryStream.Dispose()
                }
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    finally {
        $output.Dispose()
    }

    $check = [System.IO.Compression.ZipFile]::OpenRead($zipStaging)
    try {
        if ($check.Entries.Count -ne $files.Count) {
            throw "Release ZIP is incomplete: expected $($files.Count) files but found $($check.Entries.Count)."
        }
        foreach ($entry in $check.Entries) {
            $input = $entry.Open()
            try {
                $input.CopyTo([System.IO.Stream]::Null)
            }
            finally {
                $input.Dispose()
            }
        }
    }
    finally {
        $check.Dispose()
    }

    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    Get-ChildItem -LiteralPath $releaseStaging -File -Recurse -Force | ForEach-Object {
        $relativePath = $_.FullName.Substring($releaseStaging.Length).TrimStart('\')
        $destination = Join-Path $releaseDirectory $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
    }
    if (Test-Path $zipPath) {
        Move-Item -LiteralPath $zipStaging -Destination $zipPath -Force
    } else {
        Move-Item -LiteralPath $zipStaging -Destination $zipPath
    }

    Write-Host ""
    Write-Host "Release ready:"
    Write-Host "  Launcher: $releaseDirectory\V-Client.exe"
    Write-Host "  ZIP:      $zipPath"
    Write-Host "Existing files in Release that are not part of this build were preserved."
}
finally {
    Pop-Location
}
