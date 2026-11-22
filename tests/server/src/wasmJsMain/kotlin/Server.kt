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

fun databaseErrorCode(error: DatabaseError): String? = error.code

private class DatabaseContext(context: Context) : Context(context) {
    val connection = DatabaseConnection(requestId)
}

private val application = Application({ it == "probe.run" }) { context, input ->
    val connection = DatabaseContext(context).connection
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

private val authRecords = TableDefinition("records", listOf("id"), listOf(ColumnDefinition("id", ColumnKind.ID)))
private var retainedAuthConnection: DatabaseConnection? = null
private var authCalls = 0
private var completedHooks = 0
private val authProbe = Application(
    accepts = { it in listOf("probe.protected", "probe.public", "probe.retained", "auth.session") },
    authEnabled = true,
    isPublic = { it == "probe.public" },
    authHooks = AuthHooks(
        userCreating = { db, user ->
            retainedAuthConnection = db
            try {
                db.transaction { error("nested transaction was allowed") }
                error("nested transaction was allowed")
            } catch (_: DatabaseError) { }
            Repository(db, authRecords).findMany(FindMany(limit = 1))
            if (user.id == "fail") error("private hook detail")
        },
        userCreated = { completedHooks++ },
    ),
) { context, _ ->
    authCalls++
    if (context.request.path == "/probe.retained") {
        Repository(checkNotNull(retainedAuthConnection), authRecords).findMany(FindMany(limit = 1))
    }
    check(context.request.headers["Authorization"] == null)
    check(context.request.headers["Cookie"] == null)
    check(context.request.headers["Proxy-Authorization"] == null)
    val session = (context as AuthenticatedContext<*>).auth.session as Session?
    buildJsonObject {
        put("session", session?.id ?: "anonymous")
        if (session != null) {
            put("user", session.user.id)
            put("email", session.user.email)
        }
    }
}

@JsExport
fun authRequest(rid: Double, path: String, auth: String?): Promise<JsString> =
    authProbe.handle(rid, "POST", path, "application/json", "Bearer raw", "{}", 2,
        """{"authorization":"Bearer raw","cookie":"app=value","proxy-authorization":"Basic raw"}""", auth)

@JsExport
fun authHook(rid: Double, frame: String, lease: Double): Promise<JsString> =
    authProbe.handleAuthHook(rid, frame, if (lease == 0.0) null else lease)

@JsExport
fun authDispatches(): Int = authCalls

@JsExport
fun postCommitHooks(): Int = completedHooks

private val authFreeProbe = Application({ it == "probe.run" }) { context, _ ->
    check(context !is AuthenticatedContext<*>)
    buildJsonObject {
        put("authorization", checkNotNull(context.request.headers["Authorization"]))
        put("cookie", checkNotNull(context.request.headers["Cookie"]))
        check(context.request.headers["Proxy-Authorization"] == null)
    }
}

@JsExport
fun authFreeRequest(auth: String?): Promise<JsString> =
    authFreeProbe.handle(10.0, "POST", "/probe.run", "application/json", "Bearer customer", "{}", 2,
        """{"cookie":"app=value","Proxy-Authorization":"Basic raw"}""", auth)

private class RealtimeContext(context: Context) : Context(context) {
    val realtime = Realtime(requestId)
}

private val realtimeProbe = Application({ it == "probe.publish" }, authorizeRealtime = { _, channel -> channel == "orders" }) { context, input ->
    RealtimeContext(context).realtime.publish("orders", input)
    buildJsonObject { put("published", "yes") }
}

@JsExport
fun realtimeRequest(rid: Double, body: String): Promise<JsString> =
    realtimeProbe.handle(rid, "POST", "/probe.publish", "application/json", "", body, body.length)

@JsExport
fun realtimeAuthorize(rid: Double, body: String): Promise<JsString> =
    realtimeProbe.handleRealtimeAuthorize(rid, body)
