package com.chickinfry.conformance

import com.chickinfry.*
import java.io.InputStream
import java.io.OutputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private val capabilities = listOf("client.create", "client.copy", "client.call", "session.store", "barrier", "session.trace", "client.transport")
private val common = setOf("caseId", "timeoutMs")
private fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalidArgs()
private fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalidArgs()
private fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: invalidArgs()
private fun JsonObject.optionalObject(key: String): JsonObject = if (key in this) obj(key) else buildJsonObject {}
private fun JsonObject.ref(key: String): String = validReference(string(key))

internal class Driver(private val fixtures: Fixtures, private val references: References, private val output: OutputStream) {
    private data class Client(val fixture: String, val transport: HttpTransport, val bridge: Bridge, val storeRef: String?)
    private data class Barrier(val caseId: String, val point: String, val participants: Set<String>, val arrived: MutableSet<String> = hashSetOf())
    private data class ObservedCall(val caseId: String?, val events: MutableList<Pair<Long, CallEvent>> = mutableListOf(), var incomplete: Boolean = false)
    private data class Arrival(val barrierRef: String, val result: CompletableDeferred<JsonObject>)
    private val lock = Any()
    private val writer = Any()
    private val clients = mutableMapOf<String, Client>()
    private val stores = mutableMapOf<String, MemorySessionStore>()
    private val traces = mutableMapOf<String, MutableList<ClearAttempt>>()
    private val barriers = mutableMapOf<String, Barrier>()
    private val pending = mutableMapOf<String, Arrival>()
    private val seen = hashSetOf<String>()
    private val running = hashSetOf<String>()
    private val calls = mutableMapOf<String, ObservedCall>()
    private val flights = IdentityHashMap<Any, String>()
    private val refreshWorkers = mutableMapOf<Job, Long>()
    private val ids = AtomicLong()
    private var sequence = 0L
    private var handshaken = false
    private var shutdownDeadline = System.nanoTime()

    internal var stopped = false
        private set

    internal fun trackRefreshWorker(worker: Job, deadline: Long) = synchronized(lock) {
        refreshWorkers[worker] = maxOf(refreshWorkers[worker] ?: deadline, deadline)
    }

    suspend fun run(input: InputStream) = coroutineScope {
        val jobs = mutableListOf<Job>()
        try {
            val frames = Frames(input.buffered(4096), CONTROL_LIMIT)
            while (true) {
                val message = withContext(Dispatchers.IO) { frames.next() } ?: break
                val id = message.ref("id")
                if ("command" !in message) {
                    accept(id, message)
                    continue
                }
                message.fields(setOf("id", "command", "args"))
                if (!id.startsWith("c:") || !seen.add(id)) throw DriverFailure("protocol_violation")
                val command = message.string("command")
                val args = message.obj("args")
                if (!handshaken) {
                    if (command != "handshake") throw DriverFailure("protocol_violation")
                    respond(id) { handshake(args) }
                } else {
                    if (command == "handshake") throw DriverFailure("protocol_violation")
                    if (command == "client.call") synchronized(lock) { calls[id] = ObservedCall((args["caseId"] as? JsonPrimitive)?.takeIf { it.isString }?.content) }
                    synchronized(lock) { running.add(id) }
                    jobs += launch(Dispatchers.Default) {
                        respond(id) {
                            validReference(args.string("caseId"))
                            val timeout = args.long("timeoutMs")
                            if (timeout <= 0 || timeout > Long.MAX_VALUE / 1_000_000) invalidArgs()
                            val start = System.nanoTime()
                            synchronized(lock) { shutdownDeadline = maxOf(shutdownDeadline, start + timeout * 1_000_000) }
                            withTimeout(timeout) { execute(id, command, args, start, timeout) }
                        }
                    }
                }
            }
            if (synchronized(lock) { running.isNotEmpty() || pending.isNotEmpty() }) throw DriverFailure("driver_failed")
        } finally {
            input.close()
            withContext(NonCancellable) {
                jobs.forEach { it.cancel() }
                val transports = synchronized(lock) {
                    pending.values.forEach { it.result.cancel() }
                    clients.values.map { it.transport }
                }
                transports.forEach { it.close() }
                try {
                    val remaining = (synchronized(lock) { shutdownDeadline } - System.nanoTime()) / 1_000_000
                    if (jobs.any { !it.isCompleted }) withTimeout(maxOf(1, remaining)) { jobs.joinAll() }
                    val workers = synchronized(lock) { refreshWorkers.toMap() }
                    for ((worker, deadline) in workers) {
                        if (!worker.isCompleted) {
                            val workerRemaining = (deadline - System.nanoTime()) / 1_000_000
                            withTimeout(maxOf(1, workerRemaining)) { worker.join() }
                        }
                    }
                } catch (_: TimeoutCancellationException) { throw DriverFailure("driver_failed") }
            }
            stopped = true
            synchronized(lock) {
                clients.clear(); stores.clear(); traces.clear(); barriers.clear(); calls.clear(); flights.clear()
                refreshWorkers.clear()
                pending.values.forEach { it.result.cancel() }; pending.clear()
            }
            references.clear()
            synchronized(writer) { output.flush() }
        }
    }

    private fun handshake(args: JsonObject): JsonObject {
        args.fields(setOf("protocolVersions", "runId", "requiredCapabilities", "target"))
        validReference(args.string("runId"))
        val versions = args["protocolVersions"] as? JsonArray ?: invalidArgs()
        if (versions.any { it !is JsonPrimitive || it.isString || it.intOrNull == null }) invalidArgs()
        if (JsonPrimitive(1) !in versions) throw DriverFailure("protocol_mismatch")
        val target = args.obj("target")
        target.fields(setOf("language", "role", "environment"))
        if (target.string("language") != "kotlin" || target.string("role") != "sdk" || target.string("environment") != "jvm") {
            throw DriverFailure("unimplemented")
        }
        val required = args["requiredCapabilities"] as? JsonArray ?: invalidArgs()
        required.forEach { if (it !is JsonPrimitive || !it.isString) invalidArgs() }
        if (required.any { (it as JsonPrimitive).content !in capabilities }) throw DriverFailure("unimplemented")
        handshaken = true
        return buildJsonObject {
            put("protocolVersion", 1); put("driverVersion", "kotlin-jvm-v1")
            put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
        }
    }

    private suspend fun execute(id: String, command: String, args: JsonObject, startedAt: Long, timeout: Long): JsonObject = when (command) {
        "client.create" -> {
            args.fields(common + setOf("clientRef", "fixture", "origin", "requestTimeoutMs"), setOf("storeRef", "bearerRef"))
            if ("storeRef" in args && "bearerRef" in args) invalidArgs()
            val ref = args.ref("clientRef")
            val fixture = args.string("fixture")
            if (fixture !in setOf("plain", "auth")) invalidArgs()
            synchronized(lock) { if (ref in clients) throw DriverFailure("protocol_violation") }
            val counter = NetworkCounter()
            val observation = CallObservation(id) {}
            references.observeRaw(observation)
            observation.raw(args.string("origin"))
            try {
                val client = NetworkCounter.capture(counter) {
                    var transport = HttpTransport(args.string("origin"), args.long("requestTimeoutMs"))
                    val storeRef = if ("storeRef" in args) args.ref("storeRef") else null
                    if (storeRef != null) {
                        val store = store(storeRef)
                        observation.raw(store.origin)
                        transport = transport.withSession(store)
                    }
                    if ("bearerRef" in args) transport = transport.withBearer(references.get(args.ref("bearerRef")))
                    Client(fixture, transport, fixtures.create(fixture, transport), storeRef)
                }
                synchronized(lock) {
                    if (clients.putIfAbsent(ref, client) != null) { client.transport.close(); throw DriverFailure("protocol_violation") }
                }
                buildJsonObject { put("clientRef", ref); put("constructorNetworkRequests", counter.snapshot()) }
            } catch (failure: ClientException) { throw ConstructorFailure(failure, counter.snapshot(), observation) }
        }
        "client.copy" -> {
            args.fields(common + setOf("sourceClientRef", "clientRef", "bearerRef"))
            val source = client(args.ref("sourceClientRef"))
            val ref = args.ref("clientRef")
            val observation = CallObservation(id) {}
            references.observeRaw(observation)
            val transport = try { source.transport.withBearer(references.get(args.ref("bearerRef"))) }
            catch (failure: ClientException) { throw ObservedFailure(failure, observation) }
            val copy = Client(source.fixture, transport, fixtures.create(source.fixture, transport), null)
            synchronized(lock) { if (clients.putIfAbsent(ref, copy) != null) throw DriverFailure("protocol_violation") }
            buildJsonObject { put("clientRef", ref) }
        }
        "client.call" -> {
            args.fields(common + setOf("clientRef", "operation", "input"), setOf("inputRefs", "outputRefs"))
            val client = client(args.ref("clientRef"))
            val input = args.obj("input").toMutableMap()
            val inputRefs = args.optionalObject("inputRefs")
            if (input.keys.any { it in inputRefs }) invalidArgs()
            inputRefs.forEach { (field, _) -> input[field] = JsonPrimitive(references.get(inputRefs.ref(field))) }
            val observation = CallObservation(id) { event -> synchronized(lock) {
                val call = calls.getValue(id)
                call.incomplete = call.incomplete || event.readBytes == null || event.retainedBytes == null || event.peakRetainedBytes == null
                call.events.add(++sequence to event)
            } }
            references.observeRaw(observation)
            val session = SessionObservation(id, { point -> arrive(id, args.string("caseId"), startedAt, timeout, point) }, { attempt ->
                client.storeRef?.let { ref -> synchronized(lock) {
                    val trace = traces.getValue(ref)
                    if (attempt.removed == null) trace.add(attempt) else {
                        val index = trace.indexOfLast { it.callId == attempt.callId && it.expected == attempt.expected && it.removed == null }
                        if (index < 0) throw DriverFailure("driver_failed")
                        trace[index] = attempt
                    }
                } }
            }, { worker -> trackRefreshWorker(worker, startedAt + timeout * 1_000_000) })
            try {
                val result = withContext(observation + session) { client.bridge.call(args.string("operation"), JsonObject(input)) }
                JsonObject(references.output(result, args.optionalObject("outputRefs")) + ("httpStatus" to (observation.httpStatus?.let(::JsonPrimitive) ?: JsonNull)))
            } catch (failure: ClientException) { throw ObservedFailure(failure, observation) }
            catch (failure: OperationError) { throw ObservedFailure(failure, observation) }
        }
        "client.observe" -> {
            args.fields(common + setOf("callId", "afterSequence"))
            val callId = args.ref("callId")
            val after = args.long("afterSequence")
            if (after < 0) invalidArgs()
            synchronized(lock) {
                val call = calls[callId] ?: throw DriverFailure("invalid_reference")
                if (call.caseId != args.string("caseId")) throw DriverFailure("invalid_reference")
                if (after > sequence) invalidArgs()
                buildJsonObject {
                    put("callId", callId); put("throughSequence", sequence); put("complete", !call.incomplete)
                    put("events", buildJsonArray { call.events.filter { it.first > after }.forEach { (number, event) ->
                        add(buildJsonObject {
                            put("sequence", number); put("callId", callId); put("elapsedMs", event.elapsedMs)
                            put("phase", event.phase); put("httpStatus", event.httpStatus?.let(::JsonPrimitive) ?: JsonNull)
                            put("readBytes", event.readBytes?.let(::JsonPrimitive) ?: JsonNull)
                            put("retainedBytes", event.retainedBytes?.let(::JsonPrimitive) ?: JsonNull)
                            put("peakRetainedBytes", event.peakRetainedBytes?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    } })
                }
            }
        }
        "session.store.create" -> {
            args.fields(common + setOf("storeRef", "origin"), setOf("recordRef", "accessRef", "refreshRef"))
            val supplied = setOf("recordRef", "accessRef", "refreshRef").count { it in args }
            if (supplied != 0 && supplied != 3) invalidArgs()
            val ref = args.ref("storeRef")
            val origin = args.string("origin")
            val observation = CallObservation(id) {}
            references.observeRaw(observation)
            observation.raw(origin)
            val store = try { MemorySessionStore(origin, if (supplied == 3) references.record(args, origin) else null) }
            catch (failure: ClientException) { throw ObservedFailure(failure, observation) }
            synchronized(lock) {
                if (stores.putIfAbsent(ref, store) != null) throw DriverFailure("protocol_violation")
                traces[ref] = mutableListOf()
            }
            buildJsonObject { put("storeRef", ref); put("revision", store.snapshot().revision) }
        }
        "session.store.write" -> {
            args.fields(common + setOf("storeRef", "recordRef", "accessRef", "refreshRef"))
            val store = store(args.ref("storeRef"))
            val replacement = references.record(args, store.origin)
            if (!store.compareAndSet(store.read(), replacement)) throw DriverFailure("driver_failed")
            JsonObject(state(args.ref("storeRef"), false) - "present")
        }
        "session.store.read" -> {
            args.fields(common + setOf("storeRef", "includeTrace"))
            state(args.ref("storeRef"), args.boolean("includeTrace"))
        }
        "barrier.arm" -> {
            args.fields(common + setOf("barrierRef", "point", "participants"))
            val ref = args.ref("barrierRef")
            val point = args.string("point")
            if (point !in setOf("sdk-entered", "both-originals-rejected", "refresh-joined")) throw DriverFailure("unimplemented")
            val values = args["participants"] as? JsonArray ?: invalidArgs()
            val participants = values.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::validReference) ?: invalidArgs() }
            if (participants.isEmpty() || participants.toSet().size != participants.size || participants.any { !it.startsWith("c:") }) invalidArgs()
            synchronized(lock) {
                if (ref in barriers || barriers.values.any { it.point == point && it.participants.intersect(participants.toSet()).isNotEmpty() }) throw DriverFailure("protocol_violation")
                barriers[ref] = Barrier(args.string("caseId"), point, participants.toSet())
            }
            buildJsonObject { put("barrierRef", ref); put("state", "armed") }
        }
        else -> throw DriverFailure("unimplemented")
    }

    private fun client(ref: String): Client = synchronized(lock) { clients[ref] ?: throw DriverFailure("invalid_reference") }
    private fun store(ref: String): MemorySessionStore = synchronized(lock) { stores[ref] ?: throw DriverFailure("invalid_reference") }
    private fun state(ref: String, includeTrace: Boolean): JsonObject {
        val snapshot = store(ref).snapshot()
        return buildJsonObject {
            put("present", snapshot.credentials != null); put("revision", snapshot.revision)
            put("accessRef", snapshot.credentials?.let { JsonPrimitive(references.find(it.accessToken)) } ?: JsonNull)
            put("refreshRef", snapshot.credentials?.let { JsonPrimitive(references.find(it.refreshToken)) } ?: JsonNull)
            if (includeTrace) put("clearAttempts", buildJsonArray { synchronized(lock) {
                traces.getValue(ref).forEach { attempt -> add(buildJsonObject {
                    put("callId", attempt.callId)
                    put("expectedAccessRef", attempt.expected?.let { JsonPrimitive(references.find(it.accessToken)) } ?: JsonNull)
                    put("expectedRefreshRef", attempt.expected?.let { JsonPrimitive(references.find(it.refreshToken)) } ?: JsonNull)
                    put("removed", attempt.removed?.let(::JsonPrimitive) ?: JsonNull)
                }) }
            } })
        }
    }

    private suspend fun arrive(callId: String, caseId: String, startedAt: Long, timeout: Long, point: SessionPoint) {
        val selected = synchronized(lock) {
            barriers.entries.firstOrNull { it.value.caseId == caseId && it.value.point == point.point && callId in it.value.participants }
                ?.let { (ref, barrier) ->
                    if (!barrier.arrived.add(callId)) throw DriverFailure("protocol_violation")
                    ref
                }
        } ?: return
        val facts = buildJsonObject {
            when (point.point) {
                "sdk-entered" -> put("entered", true)
                "both-originals-rejected" -> put("httpStatus", 401)
                "refresh-joined" -> put("refreshRef", synchronized(lock) {
                    val flight = point.flight ?: throw DriverFailure("driver_failed")
                    flights.getOrPut(flight) { "refresh:${ids.incrementAndGet()}" }
                })
            }
        }
        val id = "d:${ids.incrementAndGet()}"
        val result = CompletableDeferred<JsonObject>()
        synchronized(lock) { pending[id] = Arrival(selected, result) }
        val remaining = timeout - (System.nanoTime() - startedAt) / 1_000_000
        if (remaining <= 0) throw DriverFailure("deadline_exceeded")
        write(buildJsonObject {
            put("id", id); put("command", "barrier.arrive"); put("args", buildJsonObject {
                put("barrierRef", selected); put("callId", callId); put("point", point.point); put("caseId", caseId)
                put("timeoutMs", remaining); put("facts", facts)
            })
        })
        try { withTimeout(remaining) { result.await() } } finally { synchronized(lock) { pending.remove(id) } }
    }

    private fun accept(id: String, message: JsonObject) {
        message.fields(setOf("id", "result", "error"))
        val arrival = synchronized(lock) { pending.remove(id) } ?: throw DriverFailure("protocol_violation")
        if (!id.startsWith("d:") || !seen.add(id) || message["error"] != JsonNull) throw DriverFailure("protocol_violation")
        val result = message.obj("result")
        result.fields(setOf("barrierRef", "state", "sequence"))
        if (result.ref("barrierRef") != arrival.barrierRef || result.string("state") != "released" || result.long("sequence") <= 0) throw DriverFailure("protocol_violation")
        arrival.result.complete(result)
    }

    private suspend fun respond(id: String, action: suspend () -> JsonObject) {
        val response = try { buildJsonObject { put("id", id); put("result", action()); put("error", JsonNull) } }
        catch (failure: CancellationException) {
            if (failure !is TimeoutCancellationException) throw failure
            envelope(id, DriverFailure("deadline_exceeded"))
        } catch (failure: Throwable) { envelope(id, failure) }
        synchronized(lock) { running.remove(id) }
        write(response)
    }

    private fun envelope(id: String, failure: Throwable): JsonObject {
        val sdk = when (failure) { is ConstructorFailure -> failure.failure; is ObservedFailure -> failure.failure; is ClientException, is OperationError -> failure; else -> null }
        val observation = when (failure) {
            is ObservedFailure -> failure.observation
            is ConstructorFailure -> failure.observation
            else -> null
        }
        val code = when (sdk) { is ClientException -> sdk.code; is OperationError -> sdk.code; else -> (failure as? DriverFailure)?.code ?: "driver_failed" }
        val status = when (sdk) { is ClientException -> sdk.httpStatus; is OperationError -> sdk.httpStatus; else -> null }
        val unknown = when (sdk) {
            is ClientException -> if (sdk.code in setOf("transport_error", "request_timeout")) observation?.outcomeUnknown else sdk.outcomeUnknown
            is OperationError -> sdk.outcomeUnknown
            else -> null
        }
        return buildJsonObject {
            put("id", id); put("result", JsonNull); put("error", buildJsonObject {
                put("kind", if (sdk != null) "sdk" else if (code == "unimplemented") "unimplemented" else "driver")
                put("code", if (Regex("[a-z_]+").matches(code) && !references.containsSecret(code)) JsonPrimitive(code) else JsonNull)
                put("httpStatus", status?.let(::JsonPrimitive) ?: JsonNull)
                put("outcomeUnknown", unknown?.let(::JsonPrimitive) ?: JsonNull)
                if (sdk != null) put("rawTransportDetails", (observation ?: CallObservation(id) {}.also(references::observeRaw)).exposesRawDetails(sdk))
                if (failure is ConstructorFailure) put("constructorNetworkRequests", failure.count)
            })
        }
    }

    private fun write(message: JsonObject) {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > CONTROL_LIMIT || references.containsSecret(message.toString())) throw DriverFailure("driver_failed")
        synchronized(writer) { output.write(bytes); output.flush() }
    }
}

private class ConstructorFailure(val failure: ClientException, val count: Long, val observation: CallObservation) : Exception("SDK construction failed.")
private class ObservedFailure(val failure: Throwable, val observation: CallObservation) : Exception("SDK call failed.")
