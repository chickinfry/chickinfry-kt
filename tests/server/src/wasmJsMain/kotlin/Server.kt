@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

import com.chickinfry.server.*
import kotlin.js.JsString
import kotlin.js.Promise

private class Values : AbstractMap<String, Any?>() {
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

private val application = Application({ it == "probe.run" }) { context, input ->
    val connection = DatabaseConnection(context.requestId)
    val id = ColumnDefinition("id", ColumnKind.ID)
    val table = TableDefinition("records", listOf("id"), listOf(
        id, ColumnDefinition("stamp", ColumnKind.TIMESTAMP),
        ColumnDefinition("day", ColumnKind.DATE), ColumnDefinition("count", ColumnKind.INTEGER),
    ))
    connection.transaction { tx ->
        val repository = Repository(tx, table)
        val key = Values().apply { this["id"] = requiredString(input, "id") }
        repository.insert(Values().apply {
            this["id"] = key["id"]
            this["stamp"] = requiredString(input, "stamp")
            this["day"] = requiredString(input, "day")
            this["count"] = 7
        })
        repository.findMany(FindMany(where = Field<String>(id).eq(key["id"] as String), limit = 1))
        repository.update(key, Values().apply { this["count"] = 8 })
        val row = repository.findById(key)!!
        repository.delete(key)
        buildJsonObject { put("stamp", row["stamp"] as String) }
    }
}

@JsExport
fun chikHandle(rid: Double, method: String, path: String, contentType: String,
    authorization: String, body: String, byteLength: Int): Promise<JsString> =
    application.handle(rid, method, path, contentType, authorization, body, byteLength)

@JsExport
fun padWithZeros(value: String, width: Int): String {
    var result = value
    while (result.length < width) result = "0" + result
    return result
}

@JsExport
fun asciiLowercase(value: String): String = buildString {
    require(value.all { it.code < 128 })
    val alphabet = "abcdefghijklmnopqrstuvwxyz"
    for (index in value.indices) {
        val char = value[index]
        append(if (char in 'A'..'Z') alphabet.substring(char - 'A', char - 'A' + 1)
            else value.substring(index, index + 1))
    }
}
