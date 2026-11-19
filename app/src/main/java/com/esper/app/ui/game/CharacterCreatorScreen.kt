package com.esper.app.ui.game

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.esper.app.game.ContentRepository
import com.esper.app.game.GameSession
import com.esper.app.ui.theme.EsperButton
import com.esper.app.ui.theme.EsperCard
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.EsperField
import com.esper.app.ui.theme.Spec
import com.esper.engine.character.CharacterFactory
import com.esper.engine.content.JobDefinition
import com.esper.engine.dice.DiceExpr
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
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    CharacterCreatorContent(
        state = CharacterCreatorState(name = name, jobs = jobs, chosenJobId = jobId, busy = busy, error = error),
        onNameChange = { name = it.take(MAX_NAME_LENGTH) },
        onChooseJob = { jobId = it },
        onBegin = {
            val chosen = jobs.firstOrNull { it.id == jobId }
            if (chosen != null) {
                busy = true
                error = null
                scope.launch {
                    GameSession.createCharacter(name.trim(), chosen)
                        .onFailure { error = it.message ?: "Couldn't create the character." }
                    busy = false
                }
            }
        },
    )
}

/** What the character creator shows. */
data class CharacterCreatorState(
    val name: String,
    /** The starting jobs on offer. */
    val jobs: List<JobDefinition>,
    val chosenJobId: String?,
    /** The character is being created. */
    val busy: Boolean,
    /** The last attempt's failure. */
    val error: String?,
)

/** A job card's stat line: hit die, AC, attack ability, damage, move. `d10 · AC 12 · STR · 1d8 · Move 4`. */
fun jobStatLine(job: JobDefinition): String =
    "d${DiceExpr.parse(job.hitDie).sides} · AC ${job.baseArmorClass} · ${job.attackAbility.uppercase()} · " +
        "${job.damage} · Move ${job.moveRangeCells}"

/** The stateless character creator (docs/VISUALS.md §4.6): what a test renders. No plates yet (step 8). */
@Composable
fun CharacterCreatorContent(
    state: CharacterCreatorState,
    onNameChange: (String) -> Unit,
    onChooseJob: (String) -> Unit,
    onBegin: () -> Unit,
) {
    val canBegin = state.name.isNotBlank() && state.jobs.any { it.id == state.chosenJobId } && !state.busy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        Text("Create your character", style = MaterialTheme.typography.titleLarge)

        EsperField(
            value = state.name,
            onValueChange = onNameChange,
            label = "Name",
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Starting job", style = MaterialTheme.typography.titleMedium)
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
        ) {
            state.jobs.forEach { option -> JobCard(option, chosen = option.id == state.chosenJobId) { onChooseJob(option.id) } }
        }

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = EsperColors.foe)
        }
        EsperButton(
            text = if (state.busy) "Creating…" else "Begin",
            onClick = onBegin,
            primary = true,
            enabled = canBegin,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A starting job: the chosen one has the emphasis outline and a tick, and stays a radio button for TalkBack. */
@Composable
private fun JobCard(job: JobDefinition, chosen: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    EsperCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = chosen, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick),
        emphasis = chosen,
    ) {
        Row {
            Text(job.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = EsperColors.ink)
        }
        if (job.description.isNotBlank()) {
            Text(job.description, style = MaterialTheme.typography.bodySmall)
        }
        Text(jobStatLine(job), style = MaterialTheme.typography.labelMedium, color = EsperColors.inkSoft)
    }
}
