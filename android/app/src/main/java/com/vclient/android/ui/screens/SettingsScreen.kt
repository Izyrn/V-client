package com.vclient.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vclient.android.BuildConfig
import com.vclient.android.MainViewModel
import com.vclient.android.ui.Panel
import com.vclient.android.ui.SectionLabel

@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var address by remember(viewModel.backendUrl) { mutableStateOf(viewModel.backendUrl) }
    var addressError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Panel {
            SectionLabel("Backend")
            Text(
                "The VYRN server this app gets news, server status and updates from. It's the same one the Windows launcher uses.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address, onValueChange = { address = it; addressError = null },
                singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Backend address") },
                isError = addressError != null, supportingText = addressError?.let { { Text(it) } },
            )
            Button(onClick = { addressError = viewModel.saveBackendUrl(address) }) { Text("Save address") }
        }

        Panel {
            SectionLabel("Updates")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates on start", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Updates are verified with SHA-256 before Android's installer opens.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = viewModel.autoUpdate, onCheckedChange = viewModel::updateAutoUpdate)
            }
            OutlinedButton(onClick = { viewModel.checkForUpdate(manual = true) }) { Text("Check now") }
            viewModel.updateMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val info = viewModel.update
            val release = info?.latest
            if (release != null) {
                Text("Version ${release.version} is available.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { viewModel.installUpdate(context) }, enabled = viewModel.updateProgress == null) { Text("Update to ${release.version}") }
            }
        }

        Panel {
            SectionLabel("About")
            Text("VYRN ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Minecraft is a trademark of Mojang Studios. VYRN is an unofficial launcher for the VSMP server.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
