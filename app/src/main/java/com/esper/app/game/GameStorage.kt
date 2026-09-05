package com.esper.app.game

import android.content.Context
import com.esper.engine.character.CharacterState
import com.esper.engine.character.SaveStore

/**
 * Placeholder. Filled in by the `android-core-and-map` work package.
 *
 * The `:app` side of the engine's [SaveStore] seam: plain `java.io` against
 * `filesDir/esper/character.json`, encoded by `SaveCodec`.
 *
 * Deliberately not `EncryptedSharedPreferences` — character data is not secret,
 * and plain files sidestep the broken-keystore failure class `core/Settings.kt`
 * already has to work around for the GitHub token.
 */
class GameStorage(private val context: Context) : SaveStore {

    override fun load(): CharacterState? {
        TODO("implemented by android-core-and-map")
    }

    override fun save(state: CharacterState) {
        TODO("implemented by android-core-and-map")
    }
}
