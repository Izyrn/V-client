package com.vclient.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vclient.android.MainViewModel
import com.vclient.android.Minecraft
import com.vclient.android.data.PlayMode
import com.vclient.android.ui.AnnouncementBanner
import com.vclient.android.ui.Panel
import com.vclient.android.ui.SectionLabel
import com.vclient.android.ui.StatusDot
import com.vclient.android.ui.VLogo
import com.vclient.android.ui.theme.Offline
import com.vclient.android.ui.theme.Online

@Composable
fun HomeScreen(viewModel: MainViewModel, onOpenServers: () -> Unit, modifier: Modifier = Modifier, onPlayDemo: () -> Unit = {}) {
    val context = LocalContext.current
    val preferred = viewModel.playMode

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VLogo(44.dp)
            Column {
                Text("VYRN", style = MaterialTheme.typography.headlineMedium)
                Text("Minecraft launcher", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        viewModel.announcements.forEach { AnnouncementBanner(it) }

        viewModel.update?.let { info ->
            val release = info.latest ?: return@let
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Update ${release.version} available", style = MaterialTheme.typography.titleMedium)
                        if (info.mandatory) Text("Required to keep using VYRN", style = MaterialTheme.typography.bodyMedium, color = Offline)
                    }
                }
                if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val progress = viewModel.updateProgress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.installUpdate(context) }) { Text("Update") }
                        if (!info.mandatory) TextButton(onClick = viewModel::dismissUpdate) { Text("Later") }
                    }
                }
            }
        }

        // Preferred mode: that edition's card comes first.
        SectionLabel("Preferred edition")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = preferred == PlayMode.JAVA, onClick = { viewModel.updatePlayMode(PlayMode.JAVA) }, label = { Text("Java Edition") })
            FilterChip(selected = preferred == PlayMode.BEDROCK, onClick = { viewModel.updatePlayMode(PlayMode.BEDROCK) }, label = { Text("Bedrock Edition") })
        }

        if (preferred == PlayMode.JAVA) {
            JavaCard(viewModel, highlighted = true)
            BedrockCard(viewModel, highlighted = false, onOpenServers = onOpenServers)
        } else {
            BedrockCard(viewModel, highlighted = true, onOpenServers = onOpenServers)
            JavaCard(viewModel, highlighted = false)
        }

        TrialCard()

        // Free, offline, original sandbox that needs no Minecraft at all.
        Panel {
            Text("Demo World", style = MaterialTheme.typography.titleLarge)
            Text(
                DEMO_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPlayDemo, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Play Demo World", style = MaterialTheme.typography.titleMedium)
            }
        }

        val config = viewModel.serverConfig
        if (config.discordUrl.isNotBlank() || viewModel.backendConfigured) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { Minecraft.openWebLink(context, config.discordUrl) },
                    enabled = config.discordUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Discord") }
                OutlinedButton(
                    onClick = { Minecraft.openWebLink(context, config.websiteUrl.ifBlank { viewModel.backendUrl }) },
                    enabled = viewModel.backendConfigured,
                    modifier = Modifier.weight(1f),
                ) { Text("Website") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EditionHeader(title: String, installedVersion: String?, missingText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                if (installedVersion != null) "Detected · version $installedVersion" else missingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusDot(if (installedVersion != null) Online else Offline)
    }
}

@Composable
private fun PlayButton(label: String, highlighted: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().height(if (highlighted) 60.dp else 48.dp)
    if (highlighted) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Java Edition runs in PojavLauncher (a separate app); VYRN detects it and opens it. */
@Composable
private fun JavaCard(viewModel: MainViewModel, highlighted: Boolean) {
    val context = LocalContext.current
    val installed = Minecraft.installedVersion(context, Minecraft.JAVA_PACKAGE)
    var menuOpen by remember { mutableStateOf(false) }

    Panel {
        EditionHeader("Java Edition", installed, "PojavLauncher not installed")

        SectionLabel("Version")
        Box {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(viewModel.javaVersion.ifBlank { "Choose a version" }, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose version")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                viewModel.javaVersions.forEach { version ->
                    DropdownMenuItem(text = { Text(version) }, onClick = { viewModel.updateJavaVersion(version); menuOpen = false })
                }
            }
        }
        Text(
            "Sign in with your Microsoft account inside PojavLauncher, then pick this version there. " +
                "PojavLauncher has no way for another app to start a specific version for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (installed != null) {
            PlayButton("Play Java Edition", highlighted) { Minecraft.launchApp(context, Minecraft.JAVA_PACKAGE) }
        } else {
            PlayButton("Play Java Edition", highlighted, enabled = false) {}
            OutlinedButton(onClick = { Minecraft.openWebLink(context, Minecraft.JAVA_LAUNCHER_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Get PojavLauncher")
            }
        }
    }
}

/** Mojang's official free trial of real Minecraft: detected, launched, or installed from Google Play. */
@Composable
private fun TrialCard() {
    val context = LocalContext.current
    val installed = Minecraft.installedVersion(context, Minecraft.TRIAL_PACKAGE)
    Panel {
        EditionHeader("Minecraft Trial (free)", installed, "Free trial not installed")
        Text(
            "The official free trial of real Minecraft from Mojang. It's limited (about 100 minutes of Survival play), " +
                "it can't join online servers, and trial worlds don't carry over to the full game. Limits are set by Mojang and may change.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed != null) {
            OutlinedButton(
                onClick = { Minecraft.launchApp(context, Minecraft.TRIAL_PACKAGE) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Play Minecraft Trial", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            OutlinedButton(
                onClick = { Minecraft.openStorePage(context, Minecraft.TRIAL_PACKAGE) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("Get the free Minecraft Trial", style = MaterialTheme.typography.titleMedium) }
        }
        TextButton(onClick = { Minecraft.openWebLink(context, Minecraft.TRIAL_INFO_URL) }) { Text("About the trial (minecraft.net)") }
    }
}

@Composable
private fun BedrockCard(viewModel: MainViewModel, highlighted: Boolean, onOpenServers: () -> Unit) {
    val context = LocalContext.current
    val installed = Minecraft.installedVersion(context, Minecraft.PACKAGE)
    val vsmp = viewModel.vsmp
    var message by remember { mutableStateOf<String?>(null) }

    Panel {
        EditionHeader("Bedrock Edition", installed, "Minecraft not installed")

        if (installed != null) {
            PlayButton("Play Bedrock Edition", highlighted) {
                message = null
                Minecraft.launchApp(context, Minecraft.PACKAGE)
            }
            OutlinedButton(
                onClick = {
                    viewModel.reportJoin()
                    if (Minecraft.joinBedrock(context, viewModel.serverConfig.name, vsmp) == Minecraft.Result.NotInstalled) {
                        message = "Minecraft isn't installed on this phone."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join ${viewModel.serverConfig.name} in one tap  ·  ${vsmp.address}") }
        } else {
            PlayButton("Play Bedrock Edition", highlighted, enabled = false) {}
            OutlinedButton(onClick = { Minecraft.openStorePage(context) }, modifier = Modifier.fillMaxWidth()) { Text("Get Minecraft") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Offline) }
        TextButton(onClick = onOpenServers) { Text("Bedrock server settings") }
    }
}
