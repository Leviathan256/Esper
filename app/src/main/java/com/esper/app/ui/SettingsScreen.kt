package com.esper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.esper.app.BuildConfig
import com.esper.app.core.Settings
import com.esper.app.core.UpdateChecker
import androidx.compose.ui.platform.LocalContext

/**
 * Repo target, release channel, and the GitHub token used to dispatch runs.
 *
 * Nothing here is sent to Claude — the token in particular is deliberately
 * excluded from the app-state snapshot.
 */
@Composable
fun SettingsScreen(settings: Settings, onDone: () -> Unit) {
    val context = LocalContext.current
    var owner by remember { mutableStateOf(settings.repoOwner) }
    var repo by remember { mutableStateOf(settings.repoName) }
    var branch by remember { mutableStateOf(settings.baseBranch) }
    var token by remember { mutableStateOf(settings.githubToken) }
    var nightly by remember { mutableStateOf(settings.followNightly) }
    var revealToken by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it; saved = false },
            label = { Text("Repo owner") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it; saved = false },
            label = { Text("Repo name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it; saved = false },
            label = { Text("Base branch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it; saved = false },
            label = { Text("GitHub token") },
            singleLine = true,
            visualTransformation = if (revealToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { revealToken = !revealToken }) {
                Text(if (revealToken) "Hide" else "Reveal")
            }
            TextButton(
                onClick = {
                    UpdateChecker.openUrl(
                        context,
                        "https://github.com/settings/personal-access-tokens/new",
                    )
                },
            ) {
                Text("Create token")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Token scope", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Use a fine-grained token limited to this one repository, with " +
                        "Actions: read & write. That is enough to start a Claude run and read " +
                        "its status, and nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!Settings.keystoreAvailable) {
                    Text(
                        text = "Warning: this device's keystore is unavailable, so the token " +
                            "is stored unencrypted. Consider leaving it blank.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text("Follow nightly builds", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Track the rolling nightly prerelease instead of tagged stable releases.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = nightly, onCheckedChange = { nightly = it; saved = false })
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    settings.repoOwner = owner
                    settings.repoName = repo
                    settings.baseBranch = branch.ifBlank { "main" }
                    settings.githubToken = token
                    settings.followNightly = nightly
                    saved = true
                },
            ) {
                Text("Save")
            }
            TextButton(onClick = onDone) { Text("Done") }
            if (saved) {
                Text("Saved", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            text = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "${BuildConfig.CHANNEL} · ${BuildConfig.GIT_SHA.take(7)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
