package com.esper.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esper.app.game.ContentRepository
import com.esper.app.game.GameSession
import com.esper.app.ui.theme.EsperBar
import com.esper.app.ui.theme.EsperCard
import com.esper.app.ui.theme.EsperColors
import com.esper.app.ui.theme.EsperTile
import com.esper.app.ui.theme.Spec
import com.esper.app.ui.theme.hudStyle
import com.esper.engine.character.CharacterState
import com.esper.engine.character.Progression
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.JobDefinition
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

    CharacterSheetContent(characterSheetState(character, job, catalog))
}

/** A stat tile's text. [note] is an ability's modifier. */
data class SheetTile(val label: String, val value: String, val note: String? = null)

/** One job row. [joinedAbove]: the row above is this job's first prerequisite. */
data class SheetJob(
    val name: String,
    val pointsText: String,
    val fraction: Float,
    val mastered: Boolean,
    /** The name of the first prerequisite, if any. */
    val from: String?,
    val joinedAbove: Boolean,
)

/** One bestiary card. */
data class SheetMonster(val name: String, val defeated: String)

/** What the character sheet shows, every number already worked out. */
data class CharacterSheetState(
    val name: String,
    val jobLine: String,
    val xpText: String,
    val xpFraction: Float,
    /** HP, AC, Attack, Damage. */
    val stats: List<SheetTile>,
    /** Speed, Move, Range. */
    val movement: List<SheetTile>,
    val abilities: List<SheetTile>,
    /** The current job, then every other job with job points or unlocked. */
    val jobs: List<SheetJob>,
    val bestiary: List<SheetMonster>,
)

private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"

/** [character] as a sheet, in the job [job] it has now. */
fun characterSheetState(character: CharacterState, job: JobDefinition, catalog: ContentCatalog): CharacterSheetState {
    val scores = StatCalculator.scoresAtLevel(character.baseScores, job, character.level)
    val derived = StatCalculator.derive(character.baseScores, job, character.level)
    val next = Progression.XP_THRESHOLDS.getOrNull(character.level)
    val shown = listOf(job) + catalog.jobs.filter {
        it.id != job.id && ((character.jobPoints[it.id] ?: 0) > 0 || it.id in character.unlockedJobIds)
    }
    return CharacterSheetState(
        name = character.name,
        jobLine = "${job.displayName} · Level ${character.level}",
        xpText = if (next != null) "XP ${character.xp} / $next" else "XP ${character.xp} · max level",
        xpFraction = if (next != null) character.xp.toFloat() / next else 1f,
        stats = listOf(
            SheetTile("HP", "${derived.maxHp}"),
            SheetTile("AC", "${derived.armorClass}"),
            SheetTile("ATTACK", signed(derived.attackBonus)),
            SheetTile("DAMAGE", "${derived.damage}"),
        ),
        // A cell is one metre.
        movement = listOf(
            SheetTile("SPEED", "${derived.speed}"),
            SheetTile("MOVE", "${derived.moveRangeCells} m"),
            SheetTile("RANGE", "${derived.attackRangeCells} m"),
        ),
        abilities = AbilityScores.KEYS.map { SheetTile(it.uppercase(), "${scores.score(it)}", signed(scores.modifier(it))) },
        jobs = shown.mapIndexed { i, shownJob ->
            val points = character.jobPoints[shownJob.id] ?: 0
            val first = shownJob.prerequisites.firstOrNull()
            SheetJob(
                name = shownJob.displayName,
                pointsText = "Job points $points / ${shownJob.jobPointsToMaster}",
                fraction = points.toFloat() / shownJob.jobPointsToMaster.coerceAtLeast(1),
                mastered = points >= shownJob.jobPointsToMaster,
                from = first?.let { catalog.job(it)?.displayName ?: it },
                joinedAbove = first != null && i > 0 && shown[i - 1].id == first,
            )
        },
        bestiary = character.bestiary.values.map {
            SheetMonster(catalog.monster(it.monsterId)?.displayName ?: it.monsterId, "defeated ×${it.timesDefeated}")
        },
    )
}

/** The stateless character sheet (docs/VISUALS.md §4.6): what a test renders. No plates yet (step 8). */
@Composable
fun CharacterSheetContent(state: CharacterSheetState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spec.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(Spec.GAP_WIDE_DP.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp)) {
            Text(state.name, style = MaterialTheme.typography.headlineMedium)
            Text(state.jobLine, style = hudStyle(16), color = EsperColors.inkSoft)
            EsperBar(state.xpFraction, EsperColors.esper)
            Text(state.xpText, style = MaterialTheme.typography.labelMedium, color = EsperColors.esper)
        }
        TileRow(state.stats)
        TileRow(state.movement)

        Text("Ability scores", style = MaterialTheme.typography.titleMedium)
        TileRow(state.abilities)

        Text("Jobs", style = MaterialTheme.typography.titleMedium)
        EsperCard(modifier = Modifier.fillMaxWidth()) {
            state.jobs.forEach { JobRow(it) }
        }

        Text("Bestiary", style = MaterialTheme.typography.titleMedium)
        if (state.bestiary.isEmpty()) {
            Text("No monsters recorded yet.", style = MaterialTheme.typography.bodySmall, color = EsperColors.inkSoft)
        }
        state.bestiary.chunked(BESTIARY_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
            ) {
                row.forEach { MonsterCard(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(BESTIARY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val BESTIARY_COLUMNS = 3

/** Tiles side by side, as tall as the tallest: at a large font scale they grow, never clip. */
@Composable
private fun TileRow(tiles: List<SheetTile>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
    ) {
        tiles.forEach { EsperTile(it.label, it.value, Modifier.weight(1f).fillMaxHeight(), it.note) }
    }
}

/** Indent of a job joined to the row above; the joining line runs down its middle. */
private const val JOIN_INDENT_DP = 16f

@Composable
private fun JobRow(job: SheetJob) {
    val joined = if (job.joinedAbove) {
        // shortcut: without plates the line joins at the name, not plate to plate; step 8 moves it to the plates.
        Modifier
            .drawBehind {
                val x = JOIN_INDENT_DP.dp.toPx() / 2
                val y = 12.dp.toPx()
                val stroke = Spec.JOB_LINE_DP.dp.toPx()
                drawLine(EsperColors.ink, Offset(x, -Spec.GAP_DP.dp.toPx()), Offset(x, y), stroke)
                drawLine(EsperColors.ink, Offset(x, y), Offset(JOIN_INDENT_DP.dp.toPx(), y), stroke)
            }
            .padding(start = (JOIN_INDENT_DP + 4).dp)
    } else {
        Modifier
    }
    Column(modifier = joined.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(job.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                job.mastered -> Text("MASTERED", style = MaterialTheme.typography.labelMedium, color = EsperColors.vitalInk)
                job.from != null -> Text("from ${job.from}", style = MaterialTheme.typography.bodySmall, color = EsperColors.inkSoft)
            }
        }
        EsperBar(job.fraction, EsperColors.esper, height = Spec.JOB_BAR_DP)
        Text(job.pointsText, style = MaterialTheme.typography.labelMedium, color = EsperColors.inkSoft)
    }
}

@Composable
private fun MonsterCard(monster: SheetMonster, modifier: Modifier) {
    EsperCard(modifier = modifier) {
        Text(
            monster.name,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            monster.defeated,
            style = MaterialTheme.typography.labelMedium,
            color = EsperColors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
