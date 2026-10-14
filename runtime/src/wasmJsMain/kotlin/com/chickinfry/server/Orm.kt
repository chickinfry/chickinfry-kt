package com.chickinfry.server

sealed class FieldValue<out T> {
    data object Absent : FieldValue<Nothing>()
    data class Present<T>(val value: T) : FieldValue<T>()
}

enum class ColumnKind { ID, STRING, INTEGER, DATE, TIMESTAMP }

data class ColumnDefinition(val name: String, val kind: ColumnKind, val nullable: Boolean = false, val optional: Boolean = false)
class TableDefinition(val name: String, val primary: List<String>, val columns: List<ColumnDefinition>) {
    init {
        identifier(name)
        require(columns.isNotEmpty() && columns.indices.all { index -> columns.take(index).none { it.name == columns[index].name } })
        columns.forEach { identifier(it.name) }
        require(primary.isNotEmpty() && primary.indices.all { index -> primary[index] !in primary.take(index) })
        require(primary.all { key -> columns.any { it.name == key && !it.nullable } })
    }
    internal fun column(name: String): ColumnDefinition = columns.singleOrNull { it.name == name }
        ?: error("Unknown column")
}

class Field<T>(internal val definition: ColumnDefinition) {
    fun eq(value: T): Filter = comparison("=", value)
    fun ne(value: T): Filter = comparison("<>", value)
    fun lt(value: T): Filter = comparison("<", value)
    fun lte(value: T): Filter = comparison("<=", value)
    fun gt(value: T): Filter = comparison(">", value)
    fun gte(value: T): Filter = comparison(">=", value)
    fun isNull(): Filter { require(definition.nullable); return Filter.Null(definition, false) }
    fun isNotNull(): Filter { require(definition.nullable); return Filter.Null(definition, true) }
    fun asc(): Order = Order(definition, false)
    fun desc(): Order = Order(definition, true)
    private fun comparison(operator: String, value: T): Filter {
        require(value != null) { "Use a null predicate" }
        return Filter.Compare(definition, operator, value)
    }
}

sealed class Filter {
    internal class Compare(val column: ColumnDefinition, val operator: String, val value: Any?) : Filter()
    internal class Null(val column: ColumnDefinition, val negate: Boolean) : Filter()
    internal class Group(val operator: String, val filters: List<Filter>) : Filter()
    internal class Negate(val filter: Filter) : Filter()
    infix fun and(other: Filter): Filter = Group("and", listOf(this, other))
    infix fun or(other: Filter): Filter = Group("or", listOf(this, other))
    fun not(): Filter = Negate(this)
}
class Order internal constructor(internal val column: ColumnDefinition, internal val descending: Boolean)
data class FindMany(val where: Filter? = null, val orderBy: List<Order> = emptyList(), val limit: Int? = null, val offset: Int? = null)

// Keep concatenation on strings; generic collection formatting can retain GC array text functions.
internal fun joinStrings(values: List<String>, separator: String): String = buildString {
    values.forEachIndexed { index, value ->
        if (index > 0) append(separator)
        append(value)
    }
}

/** Executes only operations defined by the generated table schema. */
class Repository(private val connection: DatabaseConnection, private val table: TableDefinition) {
    private val tableName = identifier(table.name)
    private val selection = joinStrings(table.columns.map { identifier(it.name) }, ", ")

    suspend fun insert(values: Map<String, Any?>): Map<String, Any?> {
        connection.ensureActive()
        require(table.columns.all { it.optional || values.containsKey(it.name) }) { "Missing required column" }
        val params = values.map { (name, value) -> validateValue(table.column(name), value) }
        val sql = if (values.isEmpty()) "insert into $tableName default values" else
            "insert into $tableName (${joinStrings(values.keys.map(::identifier), ", ")}) values (${joinStrings(params.indices.map { "$${it + 1}" }, ", ")})"
        return one("$sql returning $selection", params)
    }

    suspend fun findById(key: Map<String, Any?>): Map<String, Any?>? {
        val params = mutableListOf<Any?>()
        val where = keyPredicate(key, params)
        return connection.query("select $selection from $tableName where $where", params).singleOrNull()?.let { decodeRow(it, table) }
    }

    suspend fun findMany(options: FindMany = FindMany()): List<Map<String, Any?>> {
        val params = mutableListOf<Any?>()
        val sql = buildString {
            append("select $selection from $tableName")
            options.where?.let { append(" where ").append(predicate(it, params)) }
            if (options.orderBy.isNotEmpty()) append(" order by ").append(joinStrings(options.orderBy.map {
                require(table.column(it.column.name) == it.column)
                identifier(it.column.name) + if (it.descending) " desc" else " asc"
            }, ", "))
            options.limit?.let { require(it >= 0); params.add(it); append(" limit $").append(params.size.toString()) }
            options.offset?.let { require(it >= 0); params.add(it); append(" offset $").append(params.size.toString()) }
        }
        return connection.query(sql, params).map { decodeRow(it, table) }
    }

    suspend fun update(key: Map<String, Any?>, patch: Map<String, Any?>): Map<String, Any?> {
        connection.ensureActive()
        require(patch.isNotEmpty() && patch.keys.none { it in table.primary }) { "Invalid update" }
        val params = mutableListOf<Any?>()
        val assignments = joinStrings(patch.entries.map { (name, value) ->
            params.add(validateValue(table.column(name), value))
            "${identifier(name)} = $${params.size}"
        }, ", ")
        val where = keyPredicate(key, params)
        return one("update $tableName set $assignments where $where returning $selection", params)
    }

    suspend fun delete(key: Map<String, Any?>): Boolean {
        val params = mutableListOf<Any?>()
        val where = keyPredicate(key, params)
        return connection.query("delete from $tableName where $where returning $selection", params).isNotEmpty()
    }

    private suspend fun one(sql: String, params: List<Any?>): Map<String, Any?> =
        connection.query(sql, params).singleOrNull()?.let { decodeRow(it, table) } ?: throw DatabaseError("database_failed")

    private fun keyPredicate(key: Map<String, Any?>, params: MutableList<Any?>): String {
        require(key.size == table.primary.size && table.primary.all { key.containsKey(it) }) { "Every primary key column is required" }
        return joinStrings(table.primary.map { name ->
            params.add(validateValue(table.column(name), key[name]))
            "${identifier(name)} = $${params.size}"
        }, " and ")
    }

    private fun predicate(filter: Filter, params: MutableList<Any?>): String = when (filter) {
        is Filter.Compare -> {
            require(table.column(filter.column.name) == filter.column)
            params.add(validateValue(filter.column, filter.value))
            "${identifier(filter.column.name)} ${filter.operator} $${params.size}"
        }
        is Filter.Null -> {
            require(table.column(filter.column.name) == filter.column && filter.column.nullable)
            "${identifier(filter.column.name)} is ${if (filter.negate) "not " else ""}null"
        }
        is Filter.Group -> "(" + joinStrings(filter.filters.map { predicate(it, params) }, " ${filter.operator} ") + ")"
        is Filter.Negate -> "not (${predicate(filter.filter, params)})"
    }
}

internal fun identifier(name: String): String {
    require(name.length in 1..63 && name[0] in 'a'..'z' && name.last() != '_' && name.indices.all { index ->
        val char = name[index]
        char in 'a'..'z' || char in '0'..'9' || (char == '_' && index > 0 && name[index - 1] != '_')
    }) { "Invalid database identifier" }
    return "\"$name\""
}
internal fun validateValue(column: ColumnDefinition, value: Any?): Any? {
    if (value == null) { require(column.nullable); return null }
    if (column.kind == ColumnKind.INTEGER) { require(value is Int); return value }
    require(value is String && '\u0000' !in value)
    return when (column.kind) {
        ColumnKind.ID -> {
            require(value.length == 36 && value.indices.all { index ->
                if (index in listOf(8, 13, 18, 23)) value[index] == '-'
                else value[index] in '0'..'9' || value[index] in 'a'..'f' || value[index] in 'A'..'F'
            })
            buildString {
                for (index in value.indices) {
                    val char = value[index]
                    append(if (char in 'A'..'F') "abcdef".substring(char - 'A', char - 'A' + 1) else value.substring(index, index + 1))
                }
            }
        }
        ColumnKind.DATE -> { validateDate(value); value }
        ColumnKind.TIMESTAMP -> normalizeTimestamp(value)
        else -> value
    }
}
