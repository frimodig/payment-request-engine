package engine

import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/** Plain JDBC. One transaction per call to [tx]: commit on return, rollback on any exception. */
class Db(private val dataSource: DataSource) {
    fun <T> tx(block: (Connection) -> T): T {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (e: Throwable) {
                // A rollback that fails too must not replace the error that caused it.
                try { conn.rollback() } catch (rollbackFailure: Throwable) { e.addSuppressed(rollbackFailure) }
                throw e
            }
        }
    }

    companion object {
        fun connect(url: String, user: String, password: String): Db =
            Db(PGSimpleDataSource().apply { setUrl(url); this.user = user; this.password = password })

        fun migrate(url: String, user: String, password: String) {
            Flyway.configure().dataSource(url, user, password).load().migrate()
        }
    }
}

/**
 * Serialises storing a downstream reference with receiving events for it, until commit. Without it an event
 * can be inserted PARKED at the moment the reference is committed, and nobody links the two.
 */
fun Connection.lockReference(provider: String, ref: String) {
    prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { ps ->
        ps.setString(1, "$provider|$ref")
        ps.executeQuery().use { it.next() }
    }
}

/** A JSON string literal. Downstream-supplied text goes through this, never straight into a JSON template. */
fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

fun Instant.toDb(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

fun PreparedStatement.setInstant(index: Int, value: Instant?) = setObject(index, value?.toDb())
