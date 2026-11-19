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
import com.esper.app.ui.theme.EsperButton
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.Spec
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

    PromptsContent(
        state = PromptsState(text = text, error = error),
        onCopy = { copyToClipboard(context, "Esper prompts", it) },
        onBack = onBack,
    )
}

/** What the Prompts screen shows: the templates once loaded, or why they failed to. */
data class PromptsState(val text: String?, val error: String?)

/** The stateless Prompts screen (docs/VISUALS.md §4.6): what a test renders. `Copy all prompts` is its one gold button. */
@Composable
fun PromptsContent(state: PromptsState, onCopy: (String) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        Text(text = "Prompt templates", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Paste these into an assistant when you want to work outside the app.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val body = state.text
        when {
            state.error != null -> Text(
                text = "Failed to load prompts: ${state.error}",
                style = MaterialTheme.typography.bodyMedium,
                color = EsperColors.foe,
            )

            body == null -> Text(text = "Loading…", style = MaterialTheme.typography.bodyMedium)

            else -> {
                EsperButton(
                    text = "Copy all prompts",
                    onClick = { onCopy(body) },
                    primary = true,
                    modifier = Modifier.align(Alignment.End),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        EsperButton("Back", onClick = onBack, modifier = Modifier.align(Alignment.End))
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
