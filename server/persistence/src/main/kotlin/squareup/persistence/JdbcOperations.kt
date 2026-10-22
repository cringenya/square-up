package squareup.persistence

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

internal class JdbcOperations(private val connection: Connection) {
    fun checkOpen() {
        check(!connection.isClosed) { "Repositories must be used inside their transaction." }
    }

    fun update(sql: String, values: List<Any?>): Int {
        checkOpen()
        return connection.prepareStatement(sql).use { statement ->
            bind(statement, values)
            statement.executeUpdate()
        }
    }

    fun <T> query(sql: String, values: List<Any?>, readRow: (ResultSet) -> T): List<T> {
        return read(sql, values) { result ->
            buildList {
                while (result.next()) add(readRow(result))
            }
        }
    }

    fun <T> querySet(sql: String, values: List<Any?>, readRow: (ResultSet) -> T): Set<T> {
        return read(sql, values) { result ->
            buildSet {
                while (result.next()) add(readRow(result))
            }
        }
    }

    fun <K, V> queryMap(sql: String, values: List<Any?>, readKey: (ResultSet) -> K, readValue: (ResultSet) -> V): Map<K, V> {
        return read(sql, values) { result ->
            buildMap {
                while (result.next()) put(readKey(result), readValue(result))
            }
        }
    }

    fun <T> queryOne(sql: String, values: List<Any?>, readRow: (ResultSet) -> T): T? {
        return read(sql, values) { result ->
            if (result.next()) {
                val row = readRow(result)
                check(!result.next()) { "Query must return at most one row." }
                row
            } else {
                null
            }
        }
    }

    private fun <T> read(sql: String, values: List<Any?>, block: (ResultSet) -> T): T {
        checkOpen()
        return connection.prepareStatement(sql).use { statement ->
            bind(statement, values)
            statement.executeQuery().use(block)
        }
    }

    private fun bind(statement: PreparedStatement, values: List<Any?>) {
        values.forEachIndexed { index, value ->
            statement.setObject(index + 1, if (value is Uuid) value.toJavaUuid() else value)
        }
    }
}

internal fun ResultSet.uuid(column: String): Uuid = getObject(column, java.util.UUID::class.java).toKotlinUuid()

internal fun ResultSet.optionalUuid(column: String): Uuid? = getObject(column, java.util.UUID::class.java)?.toKotlinUuid()

internal fun <T> checkIdentity(cache: Map<Uuid, T>, id: Uuid, entity: T) {
    check(cache[id] == null || cache[id] === entity) { "Cannot save another instance with an already loaded id." }
}
