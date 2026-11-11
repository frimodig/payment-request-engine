package engine

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaSmokeTest {
    @Test
    fun `V1 migration applies to a real Postgres`() {
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val result = Flyway.configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .load()
                .migrate()
            assertEquals(1, result.migrationsExecuted)

            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                c.createStatement().executeQuery(
                    "select count(*) from information_schema.tables " +
                        "where table_schema = 'public' and table_name = 'payment_request'",
                ).use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }
}
