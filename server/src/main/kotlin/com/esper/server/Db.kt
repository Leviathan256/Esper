package com.esper.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.flywaydb.core.Flyway

/** One pool, migrated on start. Plain JDBC: step 2 has four tables. */
class Db(url: String, user: String?, password: String?) : AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 8
        },
    )

    init {
        Flyway.configure().dataSource(pool).load().migrate()
    }

    /** Runs [block] in one transaction; commits on return, rolls back on throw. */
    fun <T> tx(block: (Connection) -> T): T =
        pool.connection.use { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }

    override fun close() = pool.close()
}
