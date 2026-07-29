package com.esper.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copyable prompt templates, for driving an assistant outside the app.
 *
 * The in-app "Ask Claude" screen is the quicker route; this is here for when
 * you want to paste into a chat window instead.
 */
@Composable
fun PromptsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            text = withContext(Dispatchers.IO) {
                context.assets.open("codex_prompts.md").bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Prompt templates", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Paste these into an assistant when you want to work outside the app.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val body = text
        when {
            error != null -> Text(
                text = "Failed to load prompts: $error",
                color = MaterialTheme.colorScheme.error,
            )

            body == null -> Text(text = "Loading…")

            else -> {
                Button(
                    onClick = { copyToClipboard(context, "Esper prompts", body) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Copy all prompts")
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(onClick = onBack, modifier = Modifier.align(Alignment.End)) {
            Text("Back")
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
