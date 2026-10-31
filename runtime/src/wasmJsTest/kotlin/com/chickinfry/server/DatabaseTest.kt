@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.chickinfry.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.*

@JsFun("() => { globalThis.dbFrames = []; globalThis.dbFault = ''; globalThis.dbMissing = false; globalThis.dbQueryFault = ''; globalThis.dbRow = {id:'00000000-0000-0000-0000-000000000001', scope:'tenant', stamp:'2026-10-14 00:00:00.000001+00', day:'2024-02-29', count:7, note:null}; globalThis.__chik = {db: async bytes => { const frame = JSON.parse(new TextDecoder().decode(bytes)); globalThis.dbFrames.push(frame); let response = frame.op === 'begin' ? {lease:1} : frame.op === 'commit' || frame.op === 'rollback' ? {} : {rows:globalThis.dbMissing ? [] : [globalThis.dbRow]}; if (frame.op === 'commit' && globalThis.dbFault) response = {error:globalThis.dbFault}; if ((frame.op === 'query' || frame.op === 'exec') && globalThis.dbQueryFault) response = {error:globalThis.dbQueryFault}; return new TextEncoder().encode(JSON.stringify(response)); }}; }")
private external fun installDatabaseMock()

@JsFun("(missing) => { globalThis.dbMissing = missing; }")
private external fun mockMissing(missing: Boolean)

@JsFun("(fault) => { globalThis.dbQueryFault = fault; }")
private external fun queryFault(fault: JsString)

@JsFun("() => globalThis.dbFrames[globalThis.dbFrames.length - 1].sql")
private external fun lastSql(): JsString

@JsFun("() => JSON.stringify(globalThis.dbFrames[globalThis.dbFrames.length - 1].params)")
private external fun lastParameters(): JsString

@JsFun("(fault) => { globalThis.dbFault = fault; }")
private external fun commitFault(fault: JsString)

@JsFun("(value) => { globalThis.dbRow.count = value; }")
private external fun mockCount(value: Int)

@JsFun("() => JSON.stringify(globalThis.dbFrames)")
private external fun frames(): JsString

private val table = TableDefinition("records", listOf("scope", "id"), listOf(
    ColumnDefinition("id", ColumnKind.ID), ColumnDefinition("scope", ColumnKind.STRING),
    ColumnDefinition("stamp", ColumnKind.TIMESTAMP), ColumnDefinition("day", ColumnKind.DATE),
    ColumnDefinition("count", ColumnKind.INTEGER, optional = true), ColumnDefinition("note", ColumnKind.STRING, nullable = true, optional = true),
))

private val recordKey = mapOf("scope" to "tenant", "id" to "00000000-0000-0000-0000-000000000001")
private val recordInput = recordKey + mapOf("stamp" to "2026-10-14T09:00:00.000001+09:00", "day" to "2024-02-29", "note" to null)
private val recordRow = mapOf("id" to recordKey["id"], "scope" to "tenant", "stamp" to "2026-10-14T00:00:00.000001Z", "day" to "2024-02-29", "count" to 7, "note" to null)
private const val selectedColumns = "\"id\", \"scope\", \"stamp\", \"day\", \"count\", \"note\""

class DatabaseTest {
    @Test
    fun missingUpdateReportsNotFound(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        mockMissing(true)
        val repository = Repository(DatabaseConnection(17.0), table)
        val key = mapOf("scope" to "tenant", "id" to "00000000-0000-0000-0000-000000000001")
        val error = assertFailsWith<DatabaseError> { repository.update(key, mapOf("note" to "new")) }
        assertTrue(error.code == "not_found")
        "passed".toJsString()
    }

    @Test
    fun membershipFiltersBindValuesAndSnapshotLists(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        val count = Field<Int>(table.column("count"))
        val note = Field<String?>(table.column("note"))
        val values = mutableListOf("O'Reilly", "x'); drop table records; --")
        val filter = note.isIn(values)
        values.clear()
        assertEquals(listOf(recordRow), repository.findMany(FindMany(where = count.gt(0) and filter)))
        assertEquals("select $selectedColumns from \"records\" where (\"count\" > $1 and \"note\" in ($2, $3))", lastSql().toString())
        assertEquals("[0,\"O'Reilly\",\"x'); drop table records; --\"]", lastParameters().toString())
        repository.findMany(FindMany(where = count.isIn(listOf(Int.MIN_VALUE, Int.MAX_VALUE))))
        assertEquals("select $selectedColumns from \"records\" where \"count\" in ($1, $2)", lastSql().toString())
        assertEquals("[-2147483648,2147483647]", lastParameters().toString())
        repository.findMany(FindMany(where = count.isIn(emptyList())))
        assertEquals("select $selectedColumns from \"records\" where 1 = 0", lastSql().toString())
        assertEquals("[]", lastParameters().toString())
        repository.findMany(FindMany(where = note.isIn(listOf("x")) or note.isNull()))
        assertEquals("select $selectedColumns from \"records\" where (\"note\" in ($1) or \"note\" is null)", lastSql().toString())
        assertEquals("[\"x\"]", lastParameters().toString())
        "passed".toJsString()
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun invalidFilterValuesNeverReachTheDatabase(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        val note = Field<String?>(table.column("note"))
        val message = "Filter values cannot be null or undefined; use isNull or isNotNull."
        assertEquals(message, assertFailsWith<IllegalArgumentException> { note.eq(null) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { note.ne(null) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { note.isIn(listOf("x", null) as List<String>) }.message)
        for (filter in listOf(Filter.Compare(table.column("note"), "=", null), Filter.In(table.column("note"), listOf(null)))) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { repository.findMany(FindMany(where = filter)) }.message)
        }
        assertFailsWith<IllegalArgumentException> { repository.findMany(FindMany(where = note.isIn(listOf("bad\u0000value")))) }
        assertFailsWith<IllegalStateException> { repository.findMany(FindMany(where = Field<String>(ColumnDefinition("missing", ColumnKind.STRING)).isIn(emptyList()))) }
        assertEquals("[]", frames().toString())
        "passed".toJsString()
    }

    @Test
    fun upsertUsesCompositeKeysAndOnlyProvidedMutableValues(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        assertEquals(recordRow, repository.upsert(recordInput))
        assertEquals("insert into \"records\" (\"scope\", \"id\", \"stamp\", \"day\", \"note\") values ($1, $2, $3, $4, $5) on conflict (\"scope\", \"id\") do update set \"stamp\" = excluded.\"stamp\", \"day\" = excluded.\"day\", \"note\" = excluded.\"note\" returning $selectedColumns", lastSql().toString())
        assertEquals("[\"tenant\",\"00000000-0000-0000-0000-000000000001\",\"2026-10-14T00:00:00.000001Z\",\"2024-02-29\",null]", lastParameters().toString())
        val before = frames().toString()
        assertFailsWith<IllegalArgumentException> { repository.upsert(recordInput - "scope") }
        assertFailsWith<IllegalStateException> { repository.upsert(recordInput + ("unknown" to 1)) }
        val optionalKey = Repository(DatabaseConnection(17.0), TableDefinition("keys", listOf("id"), listOf(
            ColumnDefinition("id", ColumnKind.ID, optional = true), ColumnDefinition("note", ColumnKind.STRING, optional = true),
        )))
        assertFailsWith<IllegalArgumentException> { optionalKey.upsert(mapOf("note" to "x")) }
        assertFailsWith<IllegalArgumentException> { optionalKey.upsert(mapOf("id" to recordKey["id"])) }
        assertEquals(before, frames().toString())
        mockMissing(true)
        assertNull(assertFailsWith<DatabaseError> { repository.upsert(recordInput) }.code)
        "passed".toJsString()
    }

    @Test
    fun conditionalWritesBindTheKeyAndConditionAtomically(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        val count = Field<Int>(table.column("count"))
        assertEquals(recordRow, repository.updateIf(recordKey, count.eq(6) or count.eq(7), mapOf("note" to null)))
        assertEquals("update \"records\" set \"note\" = $1 where (\"scope\" = $2 and \"id\" = $3 and (\"count\" = $4 or \"count\" = $5)) returning $selectedColumns", lastSql().toString())
        assertEquals("[null,\"tenant\",\"00000000-0000-0000-0000-000000000001\",6,7]", lastParameters().toString())
        assertEquals(recordRow, repository.incrementIf(recordKey, count.gte(2), mapOf("count" to -2), mapOf("note" to "new")))
        assertEquals("update \"records\" set \"count\" = \"count\" + $1, \"note\" = $2 where (\"scope\" = $3 and \"id\" = $4 and \"count\" >= $5) returning $selectedColumns", lastSql().toString())
        assertEquals("[-2,\"new\",\"tenant\",\"00000000-0000-0000-0000-000000000001\",2]", lastParameters().toString())
        mockMissing(true)
        assertNull(repository.updateIf(recordKey, count.eq(8), mapOf("note" to "new")))
        assertNull(repository.incrementIf(recordKey, count.eq(8), mapOf("count" to 1), mapOf("note" to null)))
        "passed".toJsString()
    }

    @Test
    fun incrementsAcceptSafeIntegerDeltasAndReportMissingTargets(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        for ((amount, encoded) in listOf(
            1 to "1", -1 to "-1", Int.MIN_VALUE to "-2147483648", Int.MAX_VALUE to "2147483647",
            9007199254740991L to "9007199254740991", -9007199254740991L to "-9007199254740991", 2.0 to "2",
        )) {
            assertEquals(recordRow, repository.increment(recordKey, mapOf("count" to amount)))
            assertEquals("update \"records\" set \"count\" = \"count\" + $1 where \"scope\" = $2 and \"id\" = $3 returning $selectedColumns", lastSql().toString())
            assertEquals("[$encoded,\"tenant\",\"00000000-0000-0000-0000-000000000001\"]", lastParameters().toString())
        }
        mockMissing(true)
        assertEquals("not_found", assertFailsWith<DatabaseError> { repository.increment(recordKey, mapOf("count" to 1)) }.code)
        "passed".toJsString()
    }

    @Test
    fun invalidWritesFailBeforeTheHostCall(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        val count = Field<Int>(table.column("count"))
        for (amount in listOf(null, 0, 0L, -0.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            9007199254740992L, -9007199254740992L, Long.MAX_VALUE, Long.MIN_VALUE, "1", true)) {
            assertFailsWith<IllegalArgumentException> { repository.increment(recordKey, mapOf("count" to amount)) }
            assertFailsWith<IllegalArgumentException> { repository.incrementIf(recordKey, count.eq(7), mapOf("count" to amount), mapOf("note" to null)) }
        }
        for (columns in listOf(emptyMap(), mapOf("id" to 1))) {
            assertFailsWith<IllegalArgumentException> { repository.increment(recordKey, columns) }
            assertFailsWith<IllegalArgumentException> { repository.incrementIf(recordKey, count.eq(7), columns, mapOf("note" to null)) }
        }
        for (patch in listOf(emptyMap(), mapOf("id" to recordKey["id"]), mapOf("count" to 2147483648L), mapOf("count" to -2147483649L), mapOf("count" to null))) {
            assertFailsWith<IllegalArgumentException> { repository.updateIf(recordKey, count.eq(7), patch) }
            assertFailsWith<IllegalArgumentException> { repository.incrementIf(recordKey, count.eq(7), mapOf("count" to 1), patch) }
        }
        assertFailsWith<IllegalStateException> { repository.increment(recordKey, mapOf("unknown" to 1)) }
        assertFailsWith<IllegalStateException> { repository.incrementIf(recordKey, count.eq(7), mapOf("unknown" to 1), mapOf("note" to null)) }
        assertFailsWith<IllegalStateException> { repository.updateIf(recordKey, count.eq(7), mapOf("unknown" to 1)) }
        assertFailsWith<IllegalStateException> { repository.incrementIf(recordKey, count.eq(7), mapOf("count" to 1), mapOf("unknown" to 1)) }
        assertEquals("A column cannot be incremented and set together.", assertFailsWith<IllegalArgumentException> {
            repository.incrementIf(recordKey, count.eq(7), mapOf("count" to 1), mapOf("count" to 8))
        }.message)
        assertFailsWith<IllegalArgumentException> { repository.increment(mapOf("id" to recordKey["id"]), mapOf("count" to 1)) }
        assertFailsWith<IllegalArgumentException> { jsonValue(9007199254740992L) }
        assertEquals("[]", frames().toString())
        "passed".toJsString()
    }

    @Test
    fun deleteReturnsTheDecodedRowOrNullAndHostErrorsPropagate(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val repository = Repository(DatabaseConnection(17.0), table)
        assertEquals(recordRow, repository.delete(recordKey))
        assertEquals("delete from \"records\" where \"scope\" = $1 and \"id\" = $2 returning $selectedColumns", lastSql().toString())
        assertEquals("[\"tenant\",\"00000000-0000-0000-0000-000000000001\"]", lastParameters().toString())
        mockMissing(true)
        assertNull(repository.delete(recordKey))
        for ((fault, expected) in listOf("invalid_host_call" to null, "database_failed" to null, "28P01" to "28P01")) {
            queryFault(fault.toJsString())
            assertEquals(expected, assertFailsWith<DatabaseError> { repository.delete(recordKey) }.code)
        }
        queryFault("database_failed".toJsString())
        assertNull(assertFailsWith<DatabaseError> { repository.update(recordKey, mapOf("note" to "new")) }.code)
        assertNull(assertFailsWith<DatabaseError> { repository.increment(recordKey, mapOf("count" to 1)) }.code)
        assertNull(assertFailsWith<DatabaseError> { repository.delete(recordKey) }.code)
        "passed".toJsString()
    }

    @Test
    fun timestampPrecisionAndCalendar() {
        for ((input, output) in mapOf(
            "2026-10-14T09:00:00.123456+09:00" to "2026-10-14T00:00:00.123456Z",
            "2026-10-14 00:00:00.120000+00" to "2026-10-14T00:00:00.120Z",
            "2026-10-14T00:00:00.123400Z" to "2026-10-14T00:00:00.1234Z",
            "2026-10-14T00:00:00.123450Z" to "2026-10-14T00:00:00.12345Z",
            "2026-10-14T00:00:00.000001Z" to "2026-10-14T00:00:00.000001Z",
            "2026-10-14T09:00:00.1+0900" to "2026-10-14T00:00:00.100Z",
            "2026-10-14T09:00:00.000000+09" to "2026-10-14T00:00:00.000Z",
            "2024-03-01T00:00:00+01:00" to "2024-02-29T23:00:00.000Z",
            "2026-12-31T23:30:00-01:00" to "2027-01-01T00:30:00.000Z",
        )) assertEquals(output, normalizeTimestamp(input))
        for (value in listOf("infinity", "-infinity", "2026-10-14T00:00:00", "2026-10-14T00:00:00.1234567Z", "0000-01-01T00:00:00Z", "0001-01-01T00:00:00+01:00", "9999-12-31T23:59:59-01:00", "2026-02-29T00:00:00Z")) {
            assertFails { normalizeTimestamp(value) }
        }
        assertFails { validateDate("2026-02-29") }
        for (value in listOf("2026-10-14T24:00:00Z", "2026-10-14T00:60:00Z", "2026-10-14T00:00:60Z",
            "2026-10-14T00:00:00.Z", "2026-10-14T00:00:00.123", "2026-10-14T00:00:00z",
            "2026-10-14X00:00:00Z", "2026-10-14T00:00:00+24", "2026-10-14T00:00:00+0060",
            "2026-10-14T00:00:00+0:00", "2026-10-14T00:00:00+00::00", "2026-10-14T00:00:00+00:000",
            "2026-10-14T00:00:00+00Z", "2026-10-14T00:00:00Zextra", "2026-10-14T0x:00:00Z")) {
            assertFails { normalizeTimestamp(value) }
        }
    }

    @Test
    fun identifierAndUuidBoundaries() {
        assertEquals("\"a0_b1\"", identifier("a0_b1"))
        assertEquals("\"${"a".repeat(63)}\"", identifier("a".repeat(63)))
        for (value in listOf("", "_a", "a_", "a__b", "A", "0a", "a-b", "a\"b", "a".repeat(64))) {
            assertFails { identifier(value) }
        }
        val column = ColumnDefinition("id", ColumnKind.ID)
        assertEquals("abcdef01-abcd-abcd-abcd-abcdef012345", validateValue(column, "ABCDEF01-ABCD-ABCD-ABCD-ABCDEF012345"))
        for (value in listOf("abcdef01-abcd-abcd-abcd-abcdef0123450", "gbcdef01-abcd-abcd-abcd-abcdef012345",
            "abcdef01_abcd-abcd-abcd-abcdef012345", "abcdef01-abcd-abcd-abcd-abcdef01234")) {
            assertFails { validateValue(column, value) }
        }
    }

    @Test
    fun schemaQueriesAndLeaseLifetime(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        installDatabaseMock()
        val connection = DatabaseConnection(17.0)
        val repository = Repository(connection, table)
        val key = mapOf("scope" to "tenant", "id" to "00000000-0000-0000-0000-000000000001")
        val input = key + mapOf("stamp" to "2026-10-14T09:00:00.000001+09:00", "day" to "2024-02-29", "note" to null)
        val row = repository.insert(input)
        val expected = mapOf("id" to key["id"], "scope" to key["scope"], "stamp" to "2026-10-14T00:00:00.000001Z", "day" to "2024-02-29", "count" to 7, "note" to null)
        assertEquals(expected, row)
        assertEquals(expected.entries, row.entries)
        assertEquals(expected.hashCode(), row.hashCode())
        assertEquals(expected.toString(), row.toString())
        assertTrue(frames().toString().contains("null"))
        mockCount(Int.MIN_VALUE)
        assertEquals(Int.MIN_VALUE, repository.insert(input + ("count" to Int.MIN_VALUE))["count"])
        mockCount(7)
        val name = Field<String>(table.column("note"))
        val count = Field<Int>(table.column("count"))
        repository.findMany(FindMany(where = (count.gte(2) and name.isNull()).or(count.eq(0).not()), orderBy = listOf(count.desc()), limit = 2, offset = 1))
        assertTrue(frames().toString().contains("where ((\\\"count\\\" >= $1 and \\\"note\\\" is null) or not (\\\"count\\\" = $2)) order by \\\"count\\\" desc limit $3 offset $4"))
        repository.update(key, mapOf("note" to "O'Reilly"))
        assertTrue(frames().toString().contains("O'Reilly"))
        assertFails { repository.insert(input + ("id" to "not-a-uuid")) }
        assertFails { repository.findById(mapOf("id" to key["id"])) }
        assertFails { repository.update(key, mapOf("id" to key["id"])) }
        assertFails { repository.findMany(FindMany(limit = -1)) }
        lateinit var alias: Repository
        lateinit var leased: DatabaseConnection
        connection.transaction { tx ->
            leased = tx
            alias = Repository(tx, table)
            alias.findById(key)
            var nestedCalls = 0
            assertFailsWith<IllegalStateException> { tx.transaction { nestedCalls++ } }
            assertFailsWith<IllegalStateException> { connection.transaction { nestedCalls++ } }
            assertEquals(0, nestedCalls)
        }
        assertEquals(TransactionState.COMMITTED, leased.transactionState)
        assertFails { alias.findById(key) }
        assertFails { alias.insert(input) }
        assertFails { alias.findMany() }
        assertFails { alias.update(key, mapOf("note" to null)) }
        assertFails { alias.upsert(input) }
        assertFails { alias.updateIf(key, count.eq(7), mapOf("note" to null)) }
        assertFails { alias.increment(key, mapOf("count" to 1)) }
        assertFails { alias.incrementIf(key, count.eq(7), mapOf("count" to 1), mapOf("note" to null)) }
        assertFails { alias.delete(key) }
        commitFault("commit_unknown".toJsString())
        val before = frames().toString()
        val error = assertFailsWith<DatabaseError> { connection.transaction { leased = it; Repository(it, table).insert(input) } }
        assertNull(error.code)
        assertEquals(TransactionState.COMMIT_UNKNOWN, leased.transactionState)
        val after = frames().toString().removePrefix(before.removeSuffix("]"))
        assertEquals(1, Regex("\\\"op\\\":\\\"commit\\\"").findAll(after).count())
        assertFalse(after.contains("rollback"))
        assertFails { Repository(leased, table).findMany() }
        "passed".toJsString()
    }
}
