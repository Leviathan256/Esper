package com.esper.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.esper.app.game.ContentRepository
import com.esper.app.game.GameSession
import com.esper.engine.character.CharacterFactory
import kotlinx.coroutines.launch

private const val MAX_NAME_LENGTH = 20

/**
 * First-launch screen: the player names their character and picks a starting
 * job. Only jobs [CharacterFactory.startingJobs] allows are offered; the
 * character itself is built and saved on the server by [GameSession.createCharacter].
 */
@Composable
fun CharacterCreatorScreen() {
    val context = LocalContext.current
    val jobs = remember(context) { CharacterFactory.startingJobs(ContentRepository.catalog(context).jobs) }
    var name by rememberSaveable { mutableStateOf("") }
    var jobId by rememberSaveable { mutableStateOf(jobs.firstOrNull { it.id == "squire" }?.id ?: jobs.firstOrNull()?.id) }
    val trimmed = name.trim()
    val job = jobs.firstOrNull { it.id == jobId }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Create your character", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME_LENGTH) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Starting job", style = MaterialTheme.typography.titleMedium)
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            jobs.forEach { option ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option.id == jobId,
                            onClick = { jobId = option.id },
                            role = Role.RadioButton,
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.id == jobId, onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(option.displayName, style = MaterialTheme.typography.titleSmall)
                            if (option.description.isNotBlank()) {
                                Text(option.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val chosen = job ?: return@Button
                busy = true
                error = null
                scope.launch {
                    GameSession.createCharacter(trimmed, chosen)
                        .onFailure { error = it.message ?: "Couldn't create the character." }
                    busy = false
                }
            },
            enabled = trimmed.isNotEmpty() && job != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Creating…" else "Begin")
        }
    }
}
