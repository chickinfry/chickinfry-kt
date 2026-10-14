package com.chickinfry.conformance

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal const val CONTROL_LIMIT = 16 * 1024 * 1024
internal val referencePattern = Regex("[A-Za-z0-9][A-Za-z0-9:._/-]{0,255}")
internal fun validReference(value: String): String = value.also {
    if (!referencePattern.matches(it)) throw DriverFailure("invalid_reference")
}

internal class Frames(private val input: InputStream, private val limit: Int, private val totalLimit: Long = Long.MAX_VALUE) {
    private var total = 0L
    fun next(): JsonObject? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() != 0) throw DriverFailure("protocol_violation")
                return null
            }
            if (++total > totalLimit || bytes.size() + 1 > limit) throw DriverFailure("protocol_violation")
            if (value == 10) break
            bytes.write(value)
        }
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            parseObject(text)
        } catch (_: Exception) { throw DriverFailure("protocol_violation") }
    }
}

internal fun parseObject(text: String): JsonObject {
    try {
        UniqueKeys(text).check()
        return Json.parseToJsonElement(text) as? JsonObject ?: throw DriverFailure("protocol_violation")
    } catch (_: Exception) { throw DriverFailure("protocol_violation") }
}

private class UniqueKeys(private val text: String) {
    private var index = 0
    private fun space() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun take(value: Char) { space(); if (index >= text.length || text[index++] != value) throw DriverFailure("protocol_violation") }
    private fun string(): String {
        space()
        val start = index
        take('"')
        while (index < text.length) {
            when (text[index++]) {
                '\\' -> { if (index == text.length) throw DriverFailure("protocol_violation"); index++ }
                '"' -> return (Json.parseToJsonElement(text.substring(start, index)) as JsonPrimitive).content
            }
        }
        throw DriverFailure("protocol_violation")
    }
    private fun value(depth: Int) {
        if (depth > 256) throw DriverFailure("protocol_violation")
        space()
        if (index >= text.length) throw DriverFailure("protocol_violation")
        when (text[index]) {
            '{' -> {
                index++
                val keys = hashSetOf<String>()
                space()
                if (index < text.length && text[index] == '}') { index++; return }
                while (true) {
                    if (!keys.add(string())) throw DriverFailure("protocol_violation")
                    take(':'); value(depth + 1); space()
                    if (index < text.length && text[index] == '}') { index++; return }
                    take(',')
                }
            }
            '[' -> {
                index++; space()
                if (index < text.length && text[index] == ']') { index++; return }
                while (true) {
                    value(depth + 1); space()
                    if (index < text.length && text[index] == ']') { index++; return }
                    take(',')
                }
            }
            '"' -> string()
            else -> {
                val start = index
                while (index < text.length && text[index] !in ",]} \r\n\t") index++
                if (start == index) throw DriverFailure("protocol_violation")
            }
        }
    }
    fun check() { value(0); space(); if (index != text.length) throw DriverFailure("protocol_violation") }
}
