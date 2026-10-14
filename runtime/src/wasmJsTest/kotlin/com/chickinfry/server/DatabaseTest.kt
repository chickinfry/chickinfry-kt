@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.chickinfry.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.*

@JsFun("() => { globalThis.dbFrames = []; globalThis.dbFault = ''; globalThis.dbRow = {id:'00000000-0000-0000-0000-000000000001', scope:'tenant', stamp:'2026-10-14 00:00:00.000001+00', day:'2024-02-29', count:7, note:null}; globalThis.__chik = {db: async bytes => { const frame = JSON.parse(new TextDecoder().decode(bytes)); globalThis.dbFrames.push(frame); let response = frame.op === 'begin' ? {lease:1} : frame.op === 'commit' || frame.op === 'rollback' ? {} : {rows:[globalThis.dbRow]}; if (frame.op === 'commit' && globalThis.dbFault) response = {error:globalThis.dbFault}; return new TextEncoder().encode(JSON.stringify(response)); }}; }")
private external fun installDatabaseMock()

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

class DatabaseTest {
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
        assertFails { alias.delete(key) }
        commitFault("commit_unknown".toJsString())
        val before = frames().toString()
        val error = assertFailsWith<DatabaseError> { connection.transaction { leased = it; Repository(it, table).insert(input) } }
        assertEquals("commit_unknown", error.code)
        assertEquals(TransactionState.COMMIT_UNKNOWN, leased.transactionState)
        val after = frames().toString().removePrefix(before.removeSuffix("]"))
        assertEquals(1, Regex("\\\"op\\\":\\\"commit\\\"").findAll(after).count())
        assertFalse(after.contains("rollback"))
        assertFails { Repository(leased, table).findMany() }
        "passed".toJsString()
    }
}
