package com.vclient.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vclient.android.MainViewModel
import com.vclient.android.ui.Panel
import com.vclient.android.ui.SectionLabel
import com.vclient.android.ui.VLogo

@Composable
fun AccountScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineMedium)
        val session = viewModel.session
        if (session == null) SignedOut(viewModel) else SignedIn(viewModel, session.username, session.minecraftName, session.elybyName)
    }
}

@Composable
private fun SignedOut(viewModel: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VLogo(36.dp)
            Column {
                Text(if (creating) "Create your account" else "Sign in", style = MaterialTheme.typography.titleLarge)
                Text(
                    "One VYRN account for Android, Windows and the website.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = login, onValueChange = { login = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (creating) "Username" else "Username or email") },
        )
        if (creating) {
            OutlinedTextField(
                value = email, onValueChange = { email = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (creating) "Password (8+ characters)" else "Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        viewModel.accountMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (creating) viewModel.register(login, email, password) else viewModel.signIn(login, password) },
                enabled = !viewModel.accountBusy && login.isNotBlank() && password.isNotEmpty() && (!creating || email.isNotBlank()),
            ) { Text(if (creating) "Create account" else "Sign in") }
            TextButton(onClick = { creating = !creating }) { Text(if (creating) "I have an account" else "Create an account") }
            if (viewModel.accountBusy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(start = 4.dp))
        }
    }
    if (!viewModel.backendConfigured) {
        Text(
            "Set the VYRN backend address in Settings before signing in.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignedIn(viewModel: MainViewModel, username: String, minecraftName: String, elybyName: String) {
    var minecraft by remember(minecraftName) { mutableStateOf(minecraftName) }
    var ely by remember(elybyName) { mutableStateOf(elybyName) }
    Panel {
        SectionLabel("Signed in")
        Text(username, style = MaterialTheme.typography.titleLarge)
    }
    Panel {
        SectionLabel("Linked Minecraft names")
        Text(
            "Shared with the Windows launcher so your accounts follow you.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(value = minecraft, onValueChange = { minecraft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Minecraft username") })
        OutlinedTextField(value = ely, onValueChange = { ely = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Ely.by username") })
        viewModel.accountMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { viewModel.saveLinkedNames(minecraft, ely) }, enabled = !viewModel.accountBusy) { Text("Save") }
            OutlinedButton(onClick = viewModel::signOut) { Text("Sign out") }
        }
    }
}
