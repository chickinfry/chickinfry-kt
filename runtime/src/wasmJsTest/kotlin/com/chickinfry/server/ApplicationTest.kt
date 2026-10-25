@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.chickinfry.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class ApplicationTest {
    @Test
    fun ownKeysDistinguishNullFromAbsentAndUndefined() {
        val input = presenceFixture()
        assertFalse(hasField(input, "missing"))
        assertFalse(hasField(input, "inherited"))
        assertFalse(isNull(input, "missing"))
        assertFalse(isNull(input, "inherited"))
        assertTrue(hasField(input, "undefined"))
        assertFalse(isNull(input, "undefined"))
        assertTrue(hasField(input, "cleared"))
        assertTrue(isNull(input, "cleared"))
        assertFalse(isNull(input, "text"))
        assertEquals("null", requiredString(input, "text"))
        assertTrue(hasField(input, "hasOwnProperty"))
        val budget = InputBudget(input)
        assertSame(input, withDefault(input, "undefined", "\"fallback\"", budget))
        val defaultNull = withDefault(input, "missing", "null", budget)
        assertTrue(hasField(defaultNull, "missing"))
        assertTrue(isNull(defaultNull, "missing"))
        for (name in listOf("missing", "inherited", "undefined", "cleared")) {
            assertEquals(400, assertFailsWith<OperationError> { requiredString(input, name) }.status)
        }
    }

    @Test
    fun defaultsOnlyReplaceAbsentKeys(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var calls = 0
        val app = Application({ true }) { _, input ->
            val budget = InputBudget(input)
            val textInput = withDefault(input, "text", "\"fallback\"", budget)
            val text = if (isNull(textInput, "text")) null else requiredString(textInput, "text")
            val strict = requiredString(withDefault(input, "strict", "\"strict default\"", budget), "strict")
            calls++
            buildJsonObject { put("text", text ?: "<null>"); put("strict", strict) }
        }
        for ((body, expected) in listOf(
            "{}" to "fallback",
            "{\"text\":null}" to "<null>",
            "{\"text\":\"\"}" to "",
            "{\"text\":\"null\"}" to "null",
            "{\"text\":\"value\"}" to "value",
        )) {
            assertEquals("{\"status\":200,\"body\":{\"text\":\"$expected\",\"strict\":\"strict default\"}}", app.request(body = body))
        }
        for (body in listOf("{\"strict\":null}", "{\"strict\":42}", "{\"text\":false}", "{\"text\":[]}", "{\"text\":{}}")) {
            assertEquals("{\"status\":400,\"body\":{\"error\":\"invalid_input\"}}", app.request(body = body))
        }
        assertEquals(5, calls)
        "passed".toJsString()
    }

    @Test
    fun listsValidateEveryItemBeforeCallingTheService(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var calls = 0
        val app = Application({ true }) { _, input ->
            val budget = InputBudget(input)
            val rowsInput = withDefault(input, "rows", "[{\"children\":[{\"tags\":[\"default\"]}]}]", budget)
            val rows = requiredObjectList(rowsInput, "rows").flatMap { row ->
                requiredObjectList(row, "children").flatMap { child -> requiredStringList(child, "tags") }
            }.toMutableList()
            val result = rows.joinToString("|")
            rows.clear()
            if (!hasField(input, "rows")) corruptDefault(rowsInput)
            calls++
            buildJsonObject { put("result", result) }
        }
        for ((body, expected) in listOf(
            "{}" to "default",
            "{}" to "default",
            "{\"rows\":[]}" to "",
            "{\"rows\":[{\"children\":[{\"tags\":[\"\",\"null\",\"ok\"]}]}]}" to "|null|ok",
        )) {
            assertEquals("{\"status\":200,\"body\":{\"result\":\"$expected\"}}", app.request(body = body))
        }
        for (body in listOf(
            "{\"rows\":null}", "{\"rows\":{}}", "{\"rows\":[null]}", "{\"rows\":[{\"children\":[]},null]}", "{\"rows\":[1]}", "{\"rows\":[[]]}",
            "{\"rows\":[{}]}", "{\"rows\":[{\"children\":null}]}",
            "{\"rows\":[{\"children\":[{\"tags\":null}]}]}",
            "{\"rows\":[{\"children\":[{\"tags\":\"x\"}]}]}",
            "{\"rows\":[{\"children\":[{\"tags\":[\"valid\",null]}]}]}",
            "{\"rows\":[{\"children\":[{\"tags\":[42]}]}]}",
        )) {
            assertEquals("{\"status\":400,\"body\":{\"error\":\"invalid_input\"}}", app.request(body = body))
        }
        assertEquals(4, calls)
        "passed".toJsString()
    }

    @Test
    fun defaultsShareOneRequestByteBudget(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var calls = 0
        val app = Application({ true }) { _, input ->
            val budget = InputBudget(input)
            val size = requiredString(input, "size").toInt()
            val padding = "x".repeat(size)
            val literal = "\"" + padding + "\""
            requiredString(withDefault(input, "first", literal, budget), "first")
            requiredString(withDefault(input, "next", literal, budget), "next")
            calls++
            buildJsonObject {}
        }
        // The expanded object occupies exactly 4 MiB, including field names and separators.
        assertEquals("{\"status\":200,\"body\":{}}", app.request(body = "{\"size\":\"02097132\"}"))
        assertEquals("{\"status\":413,\"body\":{\"error\":\"request_too_large\"}}", app.request(body = "{\"size\":\"02097133\"}"))
        assertEquals(1, calls)
        val nested = Application({ true }) { _, input ->
            val budget = InputBudget(input)
            for (row in requiredObjectList(input, "rows")) {
                requiredString(withDefault(row, "text", "\"" + "é".repeat(1048576) + "\"", budget), "text")
            }
            buildJsonObject {}
        }
        assertEquals("{\"status\":413,\"body\":{\"error\":\"request_too_large\"}}", nested.request(body = "{\"rows\":[{},{}]}"))
        "passed".toJsString()
    }

    @Test
    fun budgetChecksReserializedSizeBeforeAddingDefaults(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var calls = 0
        val app = Application({ true }) { _, input ->
            val budget = InputBudget(input)
            requiredString(withDefault(input, "existing", "\"fallback\"", budget), "existing")
            calls++
            buildJsonObject {}
        }
        // The ignored number expands by 17 bytes; all defaulted fields are already present.
        val present = "{\"existing\":\"" + "x".repeat(4194258) + "\",\"ignored\":1e20}"
        assertEquals(4194288, present.length)
        assertEquals("{\"status\":413,\"body\":{\"error\":\"request_too_large\"}}", app.request(body = present))
        assertEquals(0, calls)
        val below = "{\"existing\":\"" + "x".repeat(4194256) + "\",\"ignored\":1e20}"
        assertEquals(4194286, below.length)
        assertEquals("{\"status\":200,\"body\":{}}", app.request(body = below))
        // One less padding byte makes the reserialized object exactly 4 MiB.
        val boundary = "{\"existing\":\"" + "x".repeat(4194257) + "\",\"ignored\":1e20}"
        assertEquals(4194287, boundary.length)
        assertEquals("{\"status\":200,\"body\":{}}", app.request(body = boundary))
        assertEquals(2, calls)
        "passed".toJsString()
    }

    @Test
    fun explicitOperationErrorsPreserveCodeAndStatus(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        for (status in listOf(400, 401, 409, 422, 500, 501, 503, 599)) {
            val code = if (status == 501) "not_implemented" else "business_failure"
            val app = Application({ true }) { _, _ -> throw OperationError(code, status) }
            assertEquals("{\"status\":$status,\"body\":{\"error\":\"$code\"}}", app.request())
        }
        "passed".toJsString()
    }

    @Test
    fun operationErrorRejectsNonErrorStatuses() {
        for (status in listOf(-1, 0, 100, 199, 200, 204, 302, 399, 600, 999)) {
            assertFailsWith<IllegalArgumentException> { OperationError("business_failure", status) }
        }
    }

    @Test
    fun unexpectedFailuresAreSanitized(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        val failures: List<() -> Nothing> = listOf(
            { throw IllegalStateException("private database credential") },
            { throw NotImplementedError("private unfinished operation") },
            { TODO("private TODO detail") },
        )
        for (fail in failures) {
            val app = Application({ true }) { _, _ -> fail() }
            assertEquals("{\"status\":500,\"body\":{\"error\":\"operation_failed\"}}", app.request())
        }
        "passed".toJsString()
    }

    @Test
    fun unauthenticatedContextAndAuthorizationRejection(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var calls = 0
        val app = Application({ true }) { context, _ ->
            assertEquals(1.0, context.requestId)
            assertEquals("POST", context.method)
            assertEquals("/greeting.echo", context.path)
            calls++
            buildJsonObject { put("message", "accepted") }
        }
        assertEquals("{\"status\":200,\"body\":{\"message\":\"accepted\"}}", app.request())
        assertEquals("{\"status\":401,\"body\":{\"error\":\"invalid_token\"}}", app.request("Bearer external"))
        assertEquals(1, calls)
        "passed".toJsString()
    }

    @Test
    fun concurrentRequestsWaitForSetupOncePerGeneration(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        withTimeout(5000) {
            var setups = 0
            var calls = 0
            var ready = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fun generation() = Application({ true }, setup = {
                setups++
                entered.complete(Unit)
                release.await()
            }) { _, _ ->
                calls++
                buildJsonObject { put("message", "accepted") }
            }
            val app = generation()
            assertEquals(0, setups)
            val boot = async(start = CoroutineStart.UNDISPATCHED) { app.initialize(); ready++ }
            entered.await()
            val first = async(start = CoroutineStart.UNDISPATCHED) { app.request() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { app.request() }
            assertEquals(0, ready)
            assertEquals(0, calls)
            release.complete(Unit)
            boot.await()
            val success = "{\"status\":200,\"body\":{\"message\":\"accepted\"}}"
            assertEquals(success, first.await())
            assertEquals(success, second.await())
            assertEquals(success, app.request())
            assertEquals(1, setups)
            assertEquals(1, ready)
            assertEquals(3, calls)
            val restarted = generation()
            restarted.initialize()
            assertEquals(success, restarted.request())
            assertEquals(2, setups)
        }
        "passed".toJsString()
    }

    @Test
    fun setupFailureLatchesWithoutReadinessOrDispatch(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        for (failure in listOf(IllegalStateException("private setup credential"), OperationError("not_implemented", 501))) {
            var setups = 0
            var ready = 0
            var calls = 0
            val app = Application({ true }, setup = { setups++; throw failure }) { _, _ ->
                calls++
                buildJsonObject {}
            }
            repeat(2) {
                val error = assertFailsWith<Exception> { app.initialize(); ready++ }
                assertSame(failure, error)
                assertEquals("{\"status\":500,\"body\":{\"error\":\"operation_failed\"}}", app.request())
            }
            assertEquals(1, setups)
            assertEquals(0, ready)
            assertEquals(0, calls)
        }
        "passed".toJsString()
    }

    @Test
    fun cancelledWaitersLeaveSetupAndOwnerAlive(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        withTimeout(5000) {
            var setups = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val app = Application({ true }, setup = { setups++; entered.complete(Unit); release.await() }) { _, _ ->
                buildJsonObject { put("message", "accepted") }
            }
            val owner = async(start = CoroutineStart.UNDISPATCHED) { app.initialize() }
            entered.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { app.initialize() }
            waiter.cancelAndJoin()
            assertFalse(owner.isCompleted)
            val later = async(start = CoroutineStart.UNDISPATCHED) { app.initialize() }
            release.complete(Unit)
            owner.await()
            later.await()
            assertEquals("{\"status\":200,\"body\":{\"message\":\"accepted\"}}", app.request())
            assertEquals(1, setups)
        }
        "passed".toJsString()
    }

    @Test
    fun cancelledFirstCallerDoesNotCancelSetup(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        withTimeout(5000) {
            var setups = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val app = Application({ true }, setup = { setups++; entered.complete(Unit); release.await() }) { _, _ -> buildJsonObject {} }
            val first = async(start = CoroutineStart.UNDISPATCHED) { app.initialize() }
            entered.await()
            first.cancelAndJoin()
            release.complete(Unit)
            app.initialize()
            assertEquals(1, setups)
            assertEquals("{\"status\":200,\"body\":{}}", app.request())
        }
        "passed".toJsString()
    }

    @Test
    fun setupCancellationLatchesWithoutCancellingApplication(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var setups = 0
        var calls = 0
        val app = Application({ true }, setup = { setups++; throw CancellationException("private setup cancellation") }) { _, _ ->
            calls++
            buildJsonObject {}
        }
        repeat(2) {
            assertFailsWith<CancellationException> { app.initialize() }
            assertEquals("{\"status\":500,\"body\":{\"error\":\"operation_failed\"}}", app.request())
        }
        assertEquals(1, setups)
        assertEquals(0, calls)
        "passed".toJsString()
    }

    @Test
    fun requestByteBoundary(): Promise<JsString> = GlobalScope.promise(Dispatchers.Default) {
        var calls = 0
        val app = Application({ it == "greeting.echo" }) { _, input ->
            requiredString(input, "id")
            calls++
            buildJsonObject { put("message", "accepted") }
        }
        for (size in listOf(4194303, 4194304, 4194305)) {
            val body = "{\"id\":\"" + "x".repeat(size - 9) + "\"}"
            val result = app.handle(size.toDouble(), "POST", "/greeting.echo", "application/json", "", body, size).await().toString()
            val expected = if (size <= 4194304) "{\"status\":200,\"body\":{\"message\":\"accepted\"}}"
                else "{\"status\":413,\"body\":{\"error\":\"request_too_large\"}}"
            assertEquals(expected, result)
        }
        assertEquals(2, calls)
        "passed".toJsString()
    }
}

@JsFun("() => Object.assign(Object.create({ inherited: 'inherited' }), { undefined: undefined, cleared: null, text: 'null', hasOwnProperty: 'value' })")
private external fun presenceFixture(): JsonObject

@JsFun("(input) => { input.rows[0].children[0].tags[0] = 'changed'; }")
private external fun corruptDefault(input: JsonObject)

private suspend fun Application.request(authorization: String = "", body: String = "{}"): String =
    handle(1.0, "POST", "/greeting.echo", "application/json", authorization, body, 0).await().toString()
