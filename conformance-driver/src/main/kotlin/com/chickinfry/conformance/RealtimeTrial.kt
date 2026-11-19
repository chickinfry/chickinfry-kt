package com.chickinfry.conformance

import com.chickinfry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

internal class RealtimeTrial {
    private class DecodeFailure : Exception("Customer decode failed.")
    private class RecoveryFailure(cause: Throwable) : Exception("Customer recovery failed.", cause)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val changed = MutableStateFlow(0)
    private val points = mutableSetOf<String>()
    private val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val frames = mutableMapOf<String, () -> Unit>()
    private val eventIds = mutableListOf<String>()
    private val facts = mutableMapOf<String, JsonElement>(
        "state" to JsonPrimitive("ready"), "callbackStarts" to JsonPrimitive(0),
        "callbackCompletions" to JsonPrimitive(0), "callbackOverlapAtMost" to JsonPrimitive(0),
        "dbReads" to JsonPrimitive(0), "pendingReads" to JsonPrimitive(0),
        "activeRenewalWatchers" to JsonPrimitive(0), "deliveredAfterEnd" to JsonPrimitive(0),
        "publicWireOperations" to JsonPrimitive(0), "terminalErrors" to JsonPrimitive(0),
        "staleFramesHeld" to JsonPrimitive(0), "staleFramesDiscarded" to JsonPrimitive(0),
    )
    private var running: Job? = null
    private var subscription: RealtimeSubscription? = null
    private var inspect: (() -> Map<String, Int>)? = null
    private var holdingFrames = false
    private var callbacks = 0
    private var gaps = 0
    private var customerError: RecoveryFailure? = null
    private var customerCause: DecodeFailure? = null

    private fun mark(point: String) {
        points.add(point)
        changed.value++
    }

    private fun increment(key: String) {
        facts[key] = JsonPrimitive((facts[key] as JsonPrimitive).int + 1)
    }

    private fun hold(name: String, point: String): CompletableDeferred<Unit> = synchronized(lock) {
        val gate = CompletableDeferred<Unit>()
        check(held.put(name, gate) == null)
        mark(point)
        gate
    }

    fun start(args: JsonObject, bridge: Bridge, origin: String) {
        val mode = args.string("onGap")
        val handler = args.string("eventHandler")
        val channel = args.string("channel")
        if (args.string("transport") != "plain" || mode !in setOf("hold", "absent", "fail", "cause") ||
            handler !in setOf("normal", "hold-a") || !origin.startsWith("http://127.0.0.1:")) invalidArgs()
        val probe = RealtimeProbe(
            endpoint = {
                if (it != "ws://" + origin.removePrefix("http://") + "/realtime") throw DriverFailure("invalid_args")
                it
            },
            point = { point -> synchronized(lock) {
                when (point) {
                    "gap" -> mark(if (++gaps == 1) "gap-received" else "gap-received-again")
                    "overflow" -> mark("overflow-received")
                    "recovered" -> mark("recovered")
                    "discarded" -> increment("staleFramesDiscarded")
                }
            } },
            inspect = { reader -> synchronized(lock) { inspect = reader } },
            dispatch = { text, receive ->
                val frame = Json.parseToJsonElement(text) as? JsonObject
                val type = (frame?.get("type") as? JsonPrimitive)?.content
                val defer = synchronized(lock) {
                    if (holdingFrames && type in setOf("ready", "event") && type !in frames) {
                        frames[type!!] = receive
                        increment("staleFramesHeld")
                        if (frames.size == 2) mark("stale-frames-held")
                        true
                    } else false
                }
                if (!defer) receive()
            },
        )
        synchronized(lock) {
            if (running != null) invalidArgs()
            running = scope.launch(probe) {
                var phase = "subscribe"
                try {
                    val options = if (mode == "absent") SubscribeOptions() else SubscribeOptions {
                        val count = synchronized(lock) {
                            increment("callbackStarts")
                            callbacks++
                            facts["callbackOverlapAtMost"] = JsonPrimitive(maxOf((facts["callbackOverlapAtMost"] as JsonPrimitive).int, callbacks))
                            (facts["callbackStarts"] as JsonPrimitive).int
                        }
                        try {
                            bridge.call("probe.list", buildJsonObject {})
                            synchronized(lock) { increment("dbReads") }
                            if (mode == "fail" || mode == "cause") {
                                throw synchronized(lock) {
                                    val cause = DecodeFailure()
                                    customerCause = cause
                                    RecoveryFailure(cause).also { customerError = it }
                                }
                            }
                            hold("gap", if (count == 1) "gap-entered" else "gap-entered-again").await()
                            synchronized(lock) { increment("callbackCompletions") }
                        } finally { synchronized(lock) { callbacks-- } }
                    }
                    val result = bridge.subscribe(channel, options)
                    synchronized(lock) { subscription = result; mark("subscribed") }
                    phase = "events"
                    result.events.collect { event ->
                        val id = (event.payload as? JsonObject)?.string("id") ?: invalidArgs()
                        if (id !in setOf("A", "B", "C", "overflow")) invalidArgs()
                        synchronized(lock) {
                            if ((facts["state"] as JsonPrimitive).content != "ready") increment("deliveredAfterEnd")
                            eventIds += id
                        }
                        if (id == "A" && handler == "hold-a") hold("event-a", "event-a-entered").await()
                        synchronized(lock) { mark("event-${id.lowercase()}-complete") }
                    }
                    synchronized(lock) { facts["state"] = JsonPrimitive("ended") }
                } catch (_: CancellationException) {
                    synchronized(lock) { facts["state"] = JsonPrimitive("canceled") }
                } catch (error: Throwable) {
                    synchronized(lock) {
                        facts["state"] = JsonPrimitive("error")
                        facts["errorPhase"] = JsonPrimitive(phase)
                        facts["errorCode"] = JsonPrimitive(when (error) {
                            is ClientError -> error.code
                            is OperationError -> error.code
                            else -> "unknown"
                        })
                        facts["httpStatus"] = (error as? OperationError)?.status?.let(::JsonPrimitive) ?: JsonNull
                        increment("terminalErrors")
                        val cause = error.cause
                        if (cause is RecoveryFailure) facts["errorType"] = JsonPrimitive("RecoveryFailure")
                        if (cause?.cause is DecodeFailure) facts["causeType"] = JsonPrimitive("DecodeFailure")
                        if (customerError != null) facts["causePreserved"] = JsonPrimitive(cause === customerError && cause?.cause === customerCause)
                    }
                } finally { synchronized(lock) { mark("ended") } }
            }
        }
    }

    suspend fun command(command: String, args: JsonObject): JsonObject {
        when (command) {
            "realtime.observe" -> {
                val point = args.string("point")
                if (point != "snapshot") changed.first { synchronized(lock) { point in points } }
                val metrics = synchronized(lock) { inspect }?.invoke().orEmpty()
                return synchronized(lock) {
                    buildJsonObject {
                        put("facts", buildJsonObject {
                            facts.forEach { (key, value) -> put(key, value) }
                            metrics.filterKeys { it != "connectionsStarted" }.forEach { (key, value) -> put(key, value) }
                            put("eventIds", JsonArray(eventIds.map(::JsonPrimitive)))
                        })
                    }
                }
            }
            "realtime.release" -> synchronized(lock) { held.remove(args.string("point")) ?: invalidArgs() }.complete(Unit)
            "realtime.unsubscribe" -> {
                val result = synchronized(lock) { subscription } ?: invalidArgs()
                result.unsubscribe()
                result.unsubscribe()
                synchronized(lock) { running }?.join()
            }
            "realtime.cancel" -> synchronized(lock) { running }?.cancelAndJoin() ?: invalidArgs()
            "realtime.second-consumer" -> {
                val result = synchronized(lock) { subscription } ?: invalidArgs()
                val probe = synchronized(lock) { inspect }
                val before = probe?.invoke()?.get("connectionsStarted")
                val failure = try {
                    result.events.first()
                    null
                } catch (error: ClientError) { error }
                val after = probe?.invoke()?.get("connectionsStarted")
                synchronized(lock) {
                    facts["exclusiveConsumption"] = JsonPrimitive(failure?.code == "failed_precondition")
                    facts["consumptionRejection"] = JsonPrimitive("runtime")
                    if (before != null && after != null) facts["newConnections"] = JsonPrimitive(after - before)
                }
            }
            "realtime.hold-dispatch" -> synchronized(lock) { holdingFrames = true }
            "realtime.release-dispatch" -> {
                val release = synchronized(lock) {
                    holdingFrames = false
                    frames.values.toList().also { frames.clear() }
                }
                release.forEach { it() }
            }
            else -> invalidArgs()
        }
        return buildJsonObject { put("accepted", true) }
    }

    suspend fun close() {
        scope.cancel()
        synchronized(lock) { running }?.join()
    }
}
