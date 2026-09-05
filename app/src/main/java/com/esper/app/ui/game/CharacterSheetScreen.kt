package com.esper.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.esper.app.game.ContentRepository
import com.esper.app.game.GameSession
import com.esper.engine.character.Progression
import com.esper.engine.stats.AbilityScores
import com.esper.engine.stats.StatCalculator

/**
 * Name, job, level, xp, derived stats, ability scores, job points, unlocked jobs
 * and the bestiary — everything about the character the engine already knows,
 * rendered as-is. No game rule is computed here; [StatCalculator] and
 * [Progression] already did that.
 */
@Composable
fun CharacterSheetScreen() {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        GameSession.ensureCharacter(context)
    }

    val character = GameSession.character
    if (character == null) {
        PlainMessage(GameSession.contentError ?: "No character yet.")
        return
    }

    val catalog = ContentRepository.catalog(context)
    val job = catalog.job(character.currentJobId)
    if (job == null) {
        PlainMessage("Job \"${character.currentJobId}\" is not in the loaded content.")
        return
    }

    val scores = StatCalculator.scoresAtLevel(character.baseScores, job, character.level)
    val derived = StatCalculator.derive(character.baseScores, job, character.level)
    val nextThreshold = Progression.XP_THRESHOLDS.getOrNull(character.level)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(character.name, style = MaterialTheme.typography.titleLarge)
        Text(
            text = "${job.displayName} · Level ${character.level}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (nextThreshold != null) {
                "XP: ${character.xp} / $nextThreshold"
            } else {
                "XP: ${character.xp} (max level)"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Stats", style = MaterialTheme.typography.titleSmall)
                Text(
                    "HP ${derived.maxHp} · AC ${derived.armorClass} · " +
                        "Attack +${derived.attackBonus} · Damage ${derived.damage}",
                )
                Text(
                    "Speed ${derived.speed} · Move ${derived.moveRangeCells} · " +
                        "Range ${derived.attackRangeCells}",
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Ability scores", style = MaterialTheme.typography.titleSmall)
                AbilityScores.KEYS.forEach { key ->
                    val score = scores.score(key)
                    val modifier = scores.modifier(key)
                    val sign = if (modifier >= 0) "+" else ""
                    Text("${key.uppercase()}: $score ($sign$modifier)")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Job points", style = MaterialTheme.typography.titleSmall)
                if (character.jobPoints.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    character.jobPoints.forEach { (jobId, points) ->
                        val jobName = catalog.job(jobId)?.displayName ?: jobId
                        Text("$jobName: $points")
                    }
                }

                Text("Unlocked jobs", style = MaterialTheme.typography.titleSmall)
                if (character.unlockedJobIds.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(character.unlockedJobIds.joinToString(", ") { catalog.job(it)?.displayName ?: it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Bestiary", style = MaterialTheme.typography.titleSmall)
                if (character.bestiary.isEmpty()) {
                    Text("No monsters recorded yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    character.bestiary.values.forEach { entry ->
                        val monster = catalog.monster(entry.monsterId)
                        Text(
                            text = "${monster?.displayName ?: entry.monsterId} — " +
                                "defeated ${entry.timesDefeated}×",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (monster != null && monster.bestiaryText.isNotBlank()) {
                            Text(monster.bestiaryText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
