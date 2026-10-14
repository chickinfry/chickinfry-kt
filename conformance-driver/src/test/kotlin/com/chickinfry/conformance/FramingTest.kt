package com.chickinfry.conformance

import java.io.ByteArrayInputStream
import kotlin.test.*
import kotlinx.serialization.json.*

class FramingTest {
    @Test fun byteBoundaryUtf8TruncationAndDuplicateKeys() {
        val prefix = "{\"value\":\""
        val suffix = "\"}\n"
        val exact = (prefix + "a".repeat(CONTROL_LIMIT - prefix.length - suffix.length) + suffix).toByteArray()
        assertEquals(CONTROL_LIMIT, exact.size)
        assertNotNull(Frames(ByteArrayInputStream(exact), CONTROL_LIMIT).next())
        fun rejects(bytes: ByteArray, limit: Int = CONTROL_LIMIT) {
            assertEquals("protocol_violation", assertFailsWith<DriverFailure> { Frames(ByteArrayInputStream(bytes), limit).next() }.code)
        }
        rejects(exact.copyOfRange(0, exact.size - 1) + byteArrayOf(32, 10))
        rejects("{}".toByteArray())
        rejects(byteArrayOf(0xc3.toByte(), 10))
        for (value in listOf("{\"a\":0,\"a\":1}\n", "{\"a\":{\"b\":0,\"\\u0062\":1}}\n", "{\"a\":[{\"b\":1,\"b\":2}]}\n")) rejects(value.toByteArray())
        val unicode = "{\"value\":\"é🎉\"}\n".toByteArray()
        assertEquals("é🎉", Frames(ByteArrayInputStream(unicode), unicode.size).next()!!.string("value"))
        rejects(unicode, unicode.size - 1)
        assertFailsWith<DriverFailure> { Frames(ByteArrayInputStream("{}\n{}\n".toByteArray()), 3, 5).apply { next(); next() } }
    }
    @Test fun referencesNeverOverwriteAndOutputNeverContainsSecrets() {
        val references = References()
        references.register("access", "a".repeat(43)); references.register("refresh", "b".repeat(43))
        assertFailsWith<DriverFailure> { references.register("access", "a".repeat(43)) }
        assertFailsWith<DriverFailure> { references.get("unknown") }
        val result = references.output(buildJsonObject {
            put("access_token", "a".repeat(43)); put("refresh_token", "b".repeat(43))
            put("nested", buildJsonObject { put("value", "a".repeat(43)) })
        }, buildJsonObject {})
        assertFalse(result.toString().contains("a".repeat(43)))
        assertFalse(result.toString().contains("b".repeat(43)))
        assertEquals("access", (result["credentialRefs"] as JsonObject).string("access_token"))
    }
}
