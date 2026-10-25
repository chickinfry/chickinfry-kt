@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import kotlinx.coroutines.await
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString

@JsFun("(text) => globalThis.__chik.db(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostDatabase(text: JsString): Promise<JsString>

@JsFun("(text) => JSON.stringify(text)")
internal external fun quoteJson(text: JsString): JsString

@JsFun("(text) => { const value = JSON.parse(text); if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid database response'); return value; }")
private external fun parseDatabase(text: JsString): JsonObject

@JsFun("(value) => Object.keys(value).length")
private external fun fieldCount(value: JsonObject): Int

@JsFun("(value, name) => Object.hasOwn(value, name)")
internal external fun hasField(value: JsonObject, name: JsString): Boolean

@JsFun("(value, name) => { const item = value[name]; return typeof item === 'string' ? item : null; }")
private external fun databaseString(value: JsonObject, name: JsString): JsString?

@JsFun("(value, name) => Number.isInteger(value[name]) && value[name] >= -2147483648 && value[name] <= 2147483647 ? value[name] : NaN")
private external fun databaseInteger(value: JsonObject, name: JsString): Double

@JsFun("(value) => Number.isSafeInteger(value.lease) && value.lease > 0 ? value.lease : NaN")
private external fun databaseLease(value: JsonObject): Double

@JsFun("(value, name) => value[name] === null")
private external fun databaseNull(value: JsonObject, name: JsString): Boolean

@JsFun("(value) => { if (!Array.isArray(value.rows)) throw new Error('Invalid database rows'); return value.rows.length; }")
private external fun rowCount(value: JsonObject): Int

@JsFun("(value, index) => { const row = value.rows[index]; if (row === null || typeof row !== 'object' || Array.isArray(row)) throw new Error('Invalid database row'); return row; }")
private external fun databaseRow(value: JsonObject, index: Int): JsonObject

class DatabaseError internal constructor(internal val code: String) : Exception("Database operation failed")

enum class TransactionState { ACTIVE, COMMITTED, ROLLED_BACK, COMMIT_UNKNOWN }

/** A request-owned connection. SQL is accessible only inside the runtime ORM. */
class DatabaseConnection private constructor(
    private val requestId: Double,
    private val lease: Long?,
) {
    constructor(requestId: Double) : this(requestId, null) {
        require(requestId > 0 && requestId <= 9007199254740991.0 && requestId % 1.0 == 0.0)
    }

    var transactionState: TransactionState = TransactionState.ACTIVE
        private set
    private var transactionRunning = false

    internal fun ensureActive() {
        check(transactionState == TransactionState.ACTIVE) { "Transaction has ended" }
    }

    private suspend fun call(op: String, query: String? = null, params: List<Any?> = emptyList()): JsonObject {
        ensureActive()
        val frame = buildString {
            append("{\"rid\":").append(requestId.toLong().toString()).append(",\"op\":").append(jsonValue(op))
            if (lease != null) append(",\"lease\":").append(lease.toString())
            if (query != null) append(",\"sql\":").append(jsonValue(query)).append(",\"params\":[")
                .append(joinStrings(params.map(::jsonValue), ",")).append("]")
            append("}")
        }
        val response = parseDatabase(hostDatabase(frame.toJsString()).await())
        if (hasField(response, "error".toJsString())) {
            val code = databaseString(response, "error".toJsString())?.toString()
            if (fieldCount(response) != 1 || code == null || !(code in listOf("commit_unknown", "database_failed", "invalid_host_call") ||
                (code.length == 5 && code.all { it in 'A'..'Z' || it in '0'..'9' }))) {
                throw DatabaseError("database_failed")
            }
            throw DatabaseError(code)
        }
        return response
    }

    internal suspend fun query(sql: String, params: List<Any?>): List<JsonObject> {
        val response = call(if (lease == null) "query" else "exec", sql, params)
        if (fieldCount(response) != 1 || !hasField(response, "rows".toJsString())) throw DatabaseError("database_failed")
        return List(rowCount(response)) { databaseRow(response, it) }
    }

    suspend fun <T> transaction(work: suspend (DatabaseConnection) -> T): T {
        ensureActive()
        check(lease == null && !transactionRunning) { "Nested transactions are not supported" }
        transactionRunning = true
        try {
            val response = call("begin")
            val id = databaseLease(response)
            if (fieldCount(response) != 1 || id.isNaN() || id <= 0) throw DatabaseError("database_failed")
            val connection = DatabaseConnection(requestId, id.toLong())
            val value = try { work(connection) } catch (error: Throwable) {
                try { connection.call("rollback") } catch (_: Throwable) { }
                finally { connection.transactionState = TransactionState.ROLLED_BACK }
                throw error
            }
            try {
                val committed = connection.call("commit")
                if (fieldCount(committed) != 0) throw DatabaseError("commit_unknown")
                connection.transactionState = TransactionState.COMMITTED
            } catch (error: Throwable) {
                connection.transactionState = if (error is DatabaseError && error.code != "commit_unknown")
                    TransactionState.ROLLED_BACK else TransactionState.COMMIT_UNKNOWN
                throw error
            }
            return value
        } finally { transactionRunning = false }
    }
}

internal fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> quoteJson(value.toJsString()).toString()
    is Int -> value.toString()
    else -> error("Unsupported database value")
}

internal fun decodeRow(row: JsonObject, table: TableDefinition): Map<String, Any?> {
    if (fieldCount(row) != table.columns.size) throw DatabaseError("database_failed")
    val values = DatabaseRow()
    table.columns.forEach { column ->
        val name = column.name.toJsString()
        if (!hasField(row, name)) throw DatabaseError("database_failed")
        val value = if (databaseNull(row, name)) null else if (column.kind == ColumnKind.INTEGER) {
            val number = databaseInteger(row, name)
            if (number.isNaN()) throw DatabaseError("database_failed")
            number.toInt()
        } else databaseString(row, name)?.toString() ?: throw DatabaseError("database_failed")
        values[column.name] = validateValue(column, value)
    }
    return values
}

// Standard map formatting retains an unsupported character append.
private class DatabaseRow : AbstractMap<String, Any?>() {
    private val fields = mutableListOf<ValueEntry>()
    operator fun set(name: String, value: Any?) {
        val index = fields.indexOfFirst { it.key == name }
        val entry = ValueEntry(name, value)
        if (index < 0) fields.add(entry) else fields[index] = entry
    }
    override val entries: Set<Map.Entry<String, Any?>>
        get() = object : AbstractSet<Map.Entry<String, Any?>>() {
            override val size: Int get() = fields.size
            override fun iterator(): Iterator<Map.Entry<String, Any?>> = fields.iterator()
        }
    override fun toString(): String = fields.foldIndexed("{") { index, text, entry ->
        text + (if (index == 0) "" else ", ") + entry.key + "=" + entry.value
    } + "}"
    private data class ValueEntry(override val key: String, override val value: Any?) : Map.Entry<String, Any?> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value
        override fun hashCode(): Int = key.hashCode() xor (value?.hashCode() ?: 0)
    }
}
