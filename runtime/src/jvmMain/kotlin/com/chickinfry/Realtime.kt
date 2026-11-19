package com.chickinfry

import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class RealtimeEvent(val channel: String, val payload: JsonElement) {
    val type: String get() = "event"
}

class RealtimeGap(val channel: String) {
    val type: String get() = "gap"
}

class SubscribeOptions(val onGap: (suspend (RealtimeGap) -> Unit)? = null)

interface RealtimeSubscription {
    val events: Flow<RealtimeEvent>
    suspend fun unsubscribe()
}

class RealtimeClient internal constructor(private val transport: Transport) {
    suspend fun subscribe(channel: String, options: SubscribeOptions = SubscribeOptions()): RealtimeSubscription {
        if (channel.isEmpty() || channel.toByteArray().size > 128 || channel.any { it.code < 32 || it.code == 127 } ||
            channel.toByteArray().toString(Charsets.UTF_8) != channel) {
            throw ClientError("invalid_argument")
        }
        val subscription = Subscription(transport, channel, options.onGap, currentCoroutineContext()[RealtimeProbe])
        return subscription.start()
    }
}

fun createRealtimeClient(transport: Transport): RealtimeClient = RealtimeClient(transport)

internal class RealtimeProbe(
    val endpoint: (String) -> String,
    val point: (String) -> Unit = {},
    val inspect: ((() -> Map<String, Int>) -> Unit) = {},
    val dispatch: ((String, () -> Unit) -> Unit) = { _, receive -> receive() },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RealtimeProbe>
}

private const val REPREPARE_MS = 30_000L
private const val MAX_FRAME_BYTES = 66_560
private val uuid = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val errorCode = Regex("[a-z_]{1,64}")
private val sockets by lazy {
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun realtimeEndpoint(value: String): String {
    val uri = try { URI(value) } catch (_: Exception) { throw ClientError("internal") }
    val host = uri.host ?: throw ClientError("internal")
    val suffix = ".chickinfry.app"
    if (!host.endsWith(suffix) || !uuid.matches(host.removeSuffix(suffix)) || value != "wss://$host/realtime") {
        throw ClientError("internal")
    }
    return value
}

private class Subscription(
    private val transport: Transport,
    private val channel: String,
    private val onGap: (suspend (RealtimeGap) -> Unit)?,
    private val probe: RealtimeProbe?,
) : RealtimeSubscription {
    private class Connection {
        var socket: WebSocket? = null
        val closed = CompletableDeferred<Unit>()
        var confirmation: String? = null
        var checkpoint: String? = null
        var prepared = false
    }
    private class Recovery(val cursor: String) {
        var complete = false
        var connection: Connection? = null
    }
    private class Incoming(val cursor: String, val payload: JsonElement)

    // Observer callbacks and coroutine transitions share one short lock.
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Unit>()
    private val consumed = AtomicBoolean()
    private val queue = ArrayDeque<Incoming>()
    private val subscription = UUID.randomUUID().toString()
    private var connection: Connection? = null
    private var recovery: Recovery? = null
    private var deadline: Job? = null
    private var firstReady = false
    private var active = true
    private var callbackRunning = false
    private var demand = false
    private var failure: Throwable? = null
    private var last = ""
    private var connectionsStarted = 0
    private var lossBudgets = 0
    private var renewalWatchers = 0
    private lateinit var worker: Job

    suspend fun start(): RealtimeSubscription {
        synchronized(lock) {
            probe?.inspect?.invoke {
                synchronized(lock) {
                    mapOf("pendingReads" to if (demand) 1 else 0, "activeRenewalWatchers" to renewalWatchers,
                        "reprepareBudgetMs" to if (lossBudgets == 0) 0 else REPREPARE_MS.toInt(),
                        "budgetResets" to (lossBudgets - 1).coerceAtLeast(0), "connectionsStarted" to connectionsStarted)
                }
            }
            budget()
            worker = scope.launch { runConnections() }
            if (transport is HttpTransport) scope.launch { renewSession(transport) }
        }
        return try {
            ready.await()
            this
        } catch (error: CancellationException) {
            unsubscribe()
            throw error
        }
    }

    override val events: Flow<RealtimeEvent> = flow {
        if (!consumed.compareAndSet(false, true)) throw ClientError("failed_precondition")
        try {
            while (true) {
                val event = next() ?: break
                emit(event)
            }
        } finally {
            unsubscribe()
        }
    }

    private suspend fun next(): RealtimeEvent? {
        try {
            while (true) {
                synchronized(lock) {
                    if (!active) {
                        failure?.let { throw it }
                        return null
                    }
                    demand = true
                    recover()
                    if (recovery == null && connection?.prepared == true && queue.isNotEmpty()) {
                        val event = queue.removeFirst()
                        last = event.cursor
                        acknowledge()
                        return RealtimeEvent(channel, event.payload)
                    }
                }
                changed.receive()
            }
        } finally {
            synchronized(lock) { demand = false }
        }
    }

    override suspend fun unsubscribe() {
        synchronized(lock) {
            if (active) send("unsubscribe")
            stop()
        }
        withContext(NonCancellable) { worker.join() }
    }

    private fun wake() { changed.trySend(Unit) }

    private fun budget() {
        if (deadline != null) return
        if (firstReady) lossBudgets++
        deadline = scope.launch {
            delay(REPREPARE_MS)
            synchronized(lock) { if (deadline == coroutineContext[Job]) stop(ClientError("deadline_exceeded")) }
        }
    }

    private fun stop(error: Throwable? = null) {
        if (!active) return
        active = false
        failure = error
        recovery = null
        queue.clear()
        closeConnection()
        renewalWatchers = 0
        scope.cancel()
        ready.completeExceptionally(error ?: ClientError("canceled"))
        wake()
    }

    private fun closeConnection() {
        val old = connection ?: return
        connection = null
        queue.clear()
        old.closed.complete(Unit)
        old.socket?.cancel()
    }

    private fun lost(current: Connection) {
        if (connection !== current) return
        closeConnection()
        budget()
        wake()
    }

    private fun recover() {
        val current = recovery ?: return
        if (!active || current.complete || callbackRunning || (firstReady && !demand)) return
        callbackRunning = true
        scope.launch {
            val error = try {
                onGap!!(RealtimeGap(channel))
                null
            } catch (failure: Throwable) {
                failure
            }
            synchronized(lock) {
                if (active && recovery === current) {
                    if (error != null) {
                        stop(ClientError("unknown").apply { initCause(error) })
                    } else {
                        current.complete = true
                        last = current.cursor
                        closeConnection()
                    }
                }
                callbackRunning = false
                recover()
                wake()
            }
        }
    }

    private fun gap(cursor: String) {
        if (onGap == null) {
            stop(ClientError("data_loss"))
            return
        }
        recovery = Recovery(cursor)
        connection?.prepared = false
        queue.clear()
        budget()
        probe?.point?.invoke("gap")
        recover()
        wake()
    }

    private fun send(type: String, fields: JsonObjectBuilder.() -> Unit = {}): Boolean =
        connection?.socket?.send(buildJsonObject {
            put("type", type)
            put("channel", channel)
            fields()
        }.toString()) == true

    private fun acknowledge() {
        val current = connection ?: return
        val cursor = current.checkpoint ?: return
        if (current.prepared && recovery == null && queue.isEmpty()) {
            if (send("ack") { put("cursor", cursor) }) current.checkpoint = null
        }
    }

    private fun cursor(value: String?): Boolean = value != null && (value.isEmpty() || uuid.matches(value))

    private fun receive(current: Connection, text: String) {
        if (!active || connection !== current) {
            probe?.point?.invoke("discarded")
            return
        }
        val frame = if (text.toByteArray().size <= MAX_FRAME_BYTES) {
            try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
        } else null
        val type = frame?.text("type")
        val name = frame?.text("channel")
        val at = frame?.text("cursor")
        if (frame == null || (name != channel && !(type == "error" && "channel" !in frame))) {
            stop(ClientError("internal"))
            return
        }
        when (type) {
            "ready" -> {
                val confirmed = frame.text("confirmation")
                val ack = frame["acknowledge"]
                if (!cursor(at) || ("confirmation" in frame && (confirmed == null || !uuid.matches(confirmed))) ||
                    (ack != null && ((ack as? JsonPrimitive)?.isString != false || ack.booleanOrNull == null))) {
                    return stop(ClientError("internal"))
                }
                current.checkpoint = at.takeIf { ack == JsonPrimitive(true) }
                if (confirmed == null) {
                    if (current.confirmation == null) {
                        current.confirmation = UUID.randomUUID().toString()
                        if (!send("confirm") { put("confirmation", current.confirmation) }) lost(current)
                    }
                    return
                }
                if (current.confirmation != confirmed) return
                val resetting = recovery
                if (resetting != null) {
                    if (!resetting.complete || resetting.connection !== current) return
                    recovery = null
                    probe?.point?.invoke("recovered")
                }
                current.prepared = true
                firstReady = true
                deadline?.cancel()
                deadline = null
                ready.complete(Unit)
                acknowledge()
                wake()
            }
            "gap" -> if (cursor(at)) gap(at!!) else stop(ClientError("internal"))
            "event" -> {
                val previous = frame.text("previous")
                val payload = frame["payload"]
                if (at == null || !uuid.matches(at) || !cursor(previous) || payload == null) return stop(ClientError("internal"))
                if (at == last || queue.any { it.cursor == at }) return
                val expected = queue.lastOrNull()?.cursor ?: recovery?.cursor ?: last
                val full = queue.size >= if (probe == null) 256 else 512
                if (previous != expected || full) {
                    if (full) probe?.point?.invoke("overflow")
                    gap(at)
                } else {
                    queue.addLast(Incoming(at, payload))
                    wake()
                }
            }
            "error" -> {
                val code = frame.text("error")
                if (code == null || !errorCode.matches(code)) return stop(ClientError("internal"))
                stop(ClientError(when (code) {
                    "unauthorized_channel" -> "permission_denied"
                    "already_subscribed" -> "internal"
                    "too_many_channels" -> "resource_exhausted"
                    "replay_failed", "realtime_unavailable" -> "unavailable"
                    else -> "unknown"
                }))
            }
            else -> stop(ClientError("internal"))
        }
    }

    private fun listener(current: Connection) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (connection !== current || !active) {
                webSocket.cancel()
            } else {
                current.socket = webSocket
                if (!send("subscribe") {
                        put("cursor", recovery?.cursor ?: last)
                        put("subscription", subscription)
                    }) lost(current)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val deliver = { synchronized(lock) { receive(current, text) } }
            if (probe == null) deliver() else probe.dispatch(text, deliver)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (connection === current) stop(ClientError("internal"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) { lost(current) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) { lost(current) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) { lost(current) }
    }

    private suspend fun runConnections() {
        var backoff = 500L
        while (currentCoroutineContext().isActive) {
            val address = try {
                transport.invoke("realtime.ticket", buildJsonObject {}) { response ->
                    val ticket = response.text("ticket")
                    val endpoint = response.text("endpoint")
                    if (ticket == null || !uuid.matches(ticket) || endpoint == null) throw ClientError("internal")
                    (probe?.endpoint?.invoke(endpoint) ?: realtimeEndpoint(endpoint)) + "?ticket=" + ticket
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val retry = synchronized(lock) {
                    when (error) {
                        is ClientError -> error.code == "unavailable" || error.code == "deadline_exceeded"
                        is OperationError -> (error.status == 409 && !firstReady) || error.status == 429 || error.status >= 500
                        else -> false
                    }.also { if (!it) stop(if (error is ClientError || error is OperationError) error else ClientError("internal")) }
                }
                if (!retry) return
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5_000)
                continue
            }
            val current = synchronized(lock) {
                if (!active) return
                connectionsStarted++
                val current = Connection()
                connection = current
                recovery?.takeIf { it.complete }?.connection = current
                try {
                    val listener = listener(current)
                    current.socket = if (transport is HttpTransport) transport.realtimeSocket(address, listener)
                    else sockets.newWebSocket(Request.Builder().url(address).build(), listener)
                } catch (_: IllegalArgumentException) {
                    stop(ClientError("internal"))
                    return
                }
                current
            }
            current.closed.await()
            delay(500)
            backoff = 500
        }
    }

    private suspend fun renewSession(transport: HttpTransport) {
        if (!transport.hasRealtimeSession) return
        synchronized(lock) { if (!active) return; renewalWatchers = 1 }
        try {
            while (currentCoroutineContext().isActive) {
                val previous = transport.realtimeSession() ?: throw ClientError("unauthenticated")
                delay((java.time.Instant.parse(previous.expiresAt).toEpochMilli() - 60_000L - System.currentTimeMillis()).coerceAtLeast(0))
                if (transport.realtimeSession() == previous) {
                    transport.invoke("auth.refresh", buildJsonObject { put("refresh_token", previous.refreshToken) }) { Unit }
                    if (transport.realtimeSession() == previous) throw ClientError("internal")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            synchronized(lock) { stop(if (error is ClientError || error is OperationError) error else ClientError("internal")) }
        } finally {
            synchronized(lock) { renewalWatchers = 0 }
        }
    }
}
