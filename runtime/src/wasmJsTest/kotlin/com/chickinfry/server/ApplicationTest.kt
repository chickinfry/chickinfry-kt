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
import kotlin.test.assertSame

class ApplicationTest {
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

private suspend fun Application.request(authorization: String = ""): String =
    handle(1.0, "POST", "/greeting.echo", "application/json", authorization, "{}", 2).await().toString()
