package com.esper.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigTest {
    private val base = mapOf("ESPER_DB_PASSWORD" to "pw")

    @Test
    fun `origins split on commas, trim, drop blanks`() {
        val config = Config.fromEnv(base + ("ESPER_RP_ORIGINS" to " android:apk-key-hash:abc , ,android:apk-key-hash:def,"))
        assertEquals(setOf("android:apk-key-hash:abc", "android:apk-key-hash:def"), config.rpOrigins)
    }

    @Test
    fun `defaults match the documented ones`() {
        val config = Config.fromEnv(base + ("ESPER_RP_ORIGINS" to "x"))
        assertEquals(8080, config.port)
        assertEquals("leviathan256.github.io", config.rpId)
        assertEquals("jdbc:postgresql://127.0.0.1/esper", config.dbUrl)
        assertEquals("esper", config.dbUser)
    }

    @Test
    fun `empty origins fail fast`() {
        assertThrows<IllegalArgumentException> { Config.fromEnv(base) }
        assertThrows<IllegalArgumentException> { Config.fromEnv(base + ("ESPER_RP_ORIGINS" to " , ")) }
    }

    @Test
    fun `missing db password fails fast`() {
        assertThrows<IllegalArgumentException> { Config.fromEnv(mapOf("ESPER_RP_ORIGINS" to "x")) }
    }
}
