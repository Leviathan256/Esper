package com.esper.app.game

import android.content.Context
import com.esper.engine.character.CharacterState
import com.esper.engine.character.SaveCodec
import com.esper.engine.character.SaveStore
import java.io.File

/**
 * The `:app` side of the engine's [SaveStore] seam: plain `java.io` against
 * `filesDir/esper/character.json`, encoded by `SaveCodec`.
 *
 * Deliberately not `EncryptedSharedPreferences` — character data is not secret,
 * and plain files sidestep the broken-keystore failure class `core/Settings.kt`
 * already has to work around for the GitHub token.
 */
class GameStorage(private val context: Context) : SaveStore {

    private val file: File
        get() = File(File(context.filesDir, "esper"), "character.json")

    override fun load(): CharacterState? {
        val target = file
        if (!target.exists()) return null
        return try {
            SaveCodec.decode(target.readText())
        } catch (_: Exception) {
            // A corrupt or unreadable save must not crash the app — treat it like
            // "no save" so the player starts fresh instead of being locked out.
            null
        }
    }

    override fun save(state: CharacterState) {
        try {
            val target = file
            target.parentFile?.mkdirs()
            target.writeText(SaveCodec.encode(state))
        } catch (_: Exception) {
            // Best-effort; losing a save write is better than crashing mid-fight.
        }
    }
}
