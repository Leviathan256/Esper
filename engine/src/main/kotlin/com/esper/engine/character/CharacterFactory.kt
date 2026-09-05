package com.esper.engine.character

import com.esper.engine.content.JobDefinition
import com.esper.engine.stats.AbilityScores

object CharacterFactory {
    /** Fixed, not rolled — a new character must be deterministic and testable. */
    val STARTING_SCORES: AbilityScores = AbilityScores(
        str = 14,
        dex = 12,
        con = 13,
        int = 10,
        wis = 10,
        cha = 10,
    )

    fun newCharacter(name: String, startingJob: JobDefinition): CharacterState =
        CharacterState(
            name = name,
            baseScores = STARTING_SCORES,
            currentJobId = startingJob.id,
        )
}
