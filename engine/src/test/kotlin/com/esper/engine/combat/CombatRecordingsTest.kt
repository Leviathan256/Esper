package com.esper.engine.combat

import com.esper.engine.character.CharacterFactory
import com.esper.engine.character.CharacterState
import com.esper.engine.character.SaveCodec
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.ContentLoader
import com.esper.engine.dice.DiceExpr
import com.esper.engine.dice.SeededRandom
import com.esper.engine.encounter.BattleBuilder
import com.esper.engine.encounter.Encounter
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.HexCoord
import com.esper.engine.stats.DerivedStats
import com.esper.engine.stats.StatCalculator
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Seeded fight recordings in `content/fixtures/combat/`, played by [SimpleAi] on
 * both sides. The Rust `rules::combat` replays the same files and must match turn
 * for turn (ADR-0002). Pets and NPC companions have no rules yet, so they are plain
 * player-controlled units added after [BattleBuilder] (`allies`).
 *
 * Re-record after a deliberate engine change: `ESPER_RECORD_COMBAT=1 ./gradlew :engine:test`.
 */
class CombatRecordingsTest {

    private val contentDir: File by lazy {
        File(System.getProperty("esper.contentDir") ?: fail("esper.contentDir is not set"))
    }

    private val catalog: ContentCatalog by lazy {
        fun texts(sub: String) = File(contentDir, sub).listFiles()!!.associate { it.name to it.readText() }
        ContentLoader.load(texts("jobs"), texts("monsters"))
    }

    private class Scenario(
        val name: String,
        val seed: Long,
        val character: CharacterState,
        val monsterIds: List<String>,
        val allies: List<CombatUnit> = emptyList(),
    )

    private fun scenarios(): List<Scenario> {
        val ramza = SaveCodec.decode(File(contentDir, "fixtures/save/v1.json").readText())
        val squire = CharacterFactory.newCharacter("Ash", catalog.job("squire")!!)
        val apprentice = CharacterFactory.newCharacter("Lia", catalog.job("apprentice")!!)
        val wolf = catalog.monster("wolf")!!
        val pet = CombatUnit(
            id = "pet",
            displayName = "Rex",
            playerControlled = true,
            sourceId = wolf.id,
            stats = DerivedStats(
                maxHp = wolf.maxHp,
                armorClass = wolf.armorClass,
                attackBonus = wolf.attackBonus,
                damage = DiceExpr.parse(wolf.damage),
                speed = wolf.speed,
                moveRangeCells = wolf.moveRangeCells,
                attackRangeCells = wolf.attackRangeCells,
            ),
            currentHp = wolf.maxHp,
            position = HexCoord(1, 0),
        )
        val companionStats = StatCalculator.derive(CharacterFactory.STARTING_SCORES, catalog.job("squire")!!, 2)
        val companion = CombatUnit(
            id = "companion",
            displayName = "Delita",
            playerControlled = true,
            sourceId = "squire",
            stats = companionStats,
            currentHp = companionStats.maxHp,
            position = HexCoord(0, 1),
        )
        return listOf(
            Scenario("solo", 7, ramza, listOf("goblin", "goblin")),
            Scenario("solo_defeat", 3, squire, listOf("wolf", "wolf", "goblin")),
            Scenario("with_pet", 11, squire, listOf("goblin", "goblin", "slime"), listOf(pet)),
            Scenario("with_companion", 23, apprentice, listOf("wolf", "goblin"), listOf(companion)),
            Scenario("with_pet_and_companion", 42, apprentice, listOf("wolf", "wolf", "goblin", "goblin"), listOf(pet, companion)),
        )
    }

    private fun record(s: Scenario): String {
        val encounter = Encounter(id = s.name, anchor = GeoPoint(0.0, 0.0), monsterIds = s.monsterIds)
        val battle = BattleBuilder.build(encounter, s.character, catalog)
        val engine = CombatEngine(battle.board, battle.units + s.allies, SeededRandom(s.seed))
        val start = engine.units
        val turns = buildJsonArray {
            var first = true
            var count = 0
            while (!engine.isOver()) {
                val actor = engine.advanceToNextActor() ?: fail("${s.name}: no actor")
                val gauges = engine.units.associate { it.id to it.atbGauge }
                // Illegal actions on the very first turn, so rejections are recorded too.
                val tries = if (first) {
                    listOf(
                        CombatAction.Move(HexCoord(99, 0)),
                        CombatAction.Attack("nobody"),
                        CombatAction.Attack(engine.units.first { it.playerControlled != actor.playerControlled }.id),
                    )
                } else {
                    emptyList()
                } + SimpleAi.chooseAction(engine, actor)
                first = false
                addJsonObject {
                    put("actor", actor.id)
                    putJsonObject("gauges") { gauges.forEach { (id, g) -> put(id, g) } }
                    putJsonArray("actions") {
                        for (action in tries) {
                            addJsonObject {
                                put("action", action.toJson())
                                putJsonArray("events") { engine.submitAction(action).forEach { add(it.toJson()) } }
                            }
                        }
                    }
                }
                assertTrue(++count < 1_000, "${s.name}: fight does not end")
            }
        }
        val json = buildJsonObject {
            put("seed", s.seed)
            putJsonObject("encounter") {
                putJsonArray("monsterIds") { s.monsterIds.forEach { add(it) } }
                put("boardRadiusCells", encounter.boardRadiusCells)
            }
            put("character", Json.parseToJsonElement(SaveCodec.encode(s.character)))
            putJsonArray("allies") { s.allies.forEach { add(it.toJson()) } }
            putJsonArray("units") { start.forEach { add(it.toJson()) } }
            put("turns", turns)
            put("result", engine.result()!!.name)
            putJsonArray("finalUnits") { engine.units.forEach { add(it.toJson()) } }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), json) + "\n"
    }

    @TestFactory
    fun `every recording matches what the engine plays now`(): List<DynamicTest> {
        val dir = File(contentDir, "fixtures/combat")
        val write = System.getenv("ESPER_RECORD_COMBAT") == "1"
        if (write) dir.mkdirs()
        return scenarios().map { s ->
            DynamicTest.dynamicTest(s.name) {
                val file = File(dir, "${s.name}.json")
                val actual = record(s)
                if (write) file.writeText(actual)
                assertEquals(file.readText(), actual)
            }
        }
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true }

        fun HexCoord.toJson(): JsonElement = buildJsonObject {
            put("q", q)
            put("r", r)
        }

        fun CombatUnit.toJson(): JsonElement = buildJsonObject {
            put("id", id)
            put("displayName", displayName)
            put("playerControlled", playerControlled)
            put("sourceId", sourceId)
            putJsonObject("stats") {
                put("maxHp", stats.maxHp)
                put("armorClass", stats.armorClass)
                put("attackBonus", stats.attackBonus)
                put("damage", stats.damage.toString())
                put("speed", stats.speed)
                put("moveRangeCells", stats.moveRangeCells)
                put("attackRangeCells", stats.attackRangeCells)
            }
            put("currentHp", currentHp)
            put("position", position.toJson())
            put("atbGauge", atbGauge)
        }

        fun CombatAction.toJson(): JsonElement = buildJsonObject {
            when (val a = this@toJson) {
                is CombatAction.Move -> {
                    put("type", "Move")
                    put("to", a.to.toJson())
                }
                is CombatAction.Attack -> {
                    put("type", "Attack")
                    put("targetId", a.targetId)
                }
                CombatAction.Wait -> put("type", "Wait")
            }
        }

        fun CombatEvent.toJson(): JsonElement = buildJsonObject {
            when (val e = this@toJson) {
                is CombatEvent.TurnReady -> {
                    put("type", "TurnReady")
                    put("unitId", e.unitId)
                }
                is CombatEvent.Moved -> {
                    put("type", "Moved")
                    put("unitId", e.unitId)
                    put("from", e.from.toJson())
                    put("to", e.to.toJson())
                }
                is CombatEvent.Attacked -> {
                    put("type", "Attacked")
                    put("attackerId", e.attackerId)
                    put("targetId", e.targetId)
                    putJsonObject("roll") {
                        put("d20", e.roll.d20)
                        put("total", e.roll.total)
                        put("hit", e.roll.hit)
                        put("critical", e.roll.critical)
                        put("fumble", e.roll.fumble)
                    }
                    put("damage", e.damage)
                    put("targetHpAfter", e.targetHpAfter)
                }
                is CombatEvent.Defeated -> {
                    put("type", "Defeated")
                    put("unitId", e.unitId)
                }
                is CombatEvent.ActionRejected -> {
                    put("type", "ActionRejected")
                    put("unitId", e.unitId)
                    put("reason", e.reason)
                }
                is CombatEvent.BattleEnded -> {
                    put("type", "BattleEnded")
                    put("result", e.result.name)
                }
            }
        }
    }
}
