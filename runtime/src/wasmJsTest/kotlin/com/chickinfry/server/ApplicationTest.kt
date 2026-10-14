@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.chickinfry.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
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
