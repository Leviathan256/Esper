package com.esper.engine.character

/**
 * Persistence seam. Implemented in `:app` by `GameStorage`; the engine never
 * touches a file, which is what keeps it testable on a plain JVM.
 */
interface SaveStore {
    fun load(): CharacterState?

    fun save(state: CharacterState)
}
