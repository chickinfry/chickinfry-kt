package com.chickinfry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SessionTransportTest {
    @Test
    fun loginStorageBearerCopiesLogoutAndRevision() = runBlocking {
        val headers = mutableListOf<String?>()
        val first = credentials('a')
        val second = credentials('c')
        SessionPeer { exchange ->
            headers += exchange.requestHeaders.getFirst("Authorization")
            exchange.respond(when (exchange.requestURI.path) {
                "/auth.signup" -> wire(first).toString()
                "/auth.signin" -> wire(second).toString()
                else -> "{}"
            })
        }.use { peer ->
            val store = MemoryStore(peer.origin)
            val anonymous = HttpTransport(peer.origin)
            val session = anonymous.withSession(store)
            session.invokePublic("auth.signup", empty) { it }
            assertEquals(first, store.read())
            assertEquals(1L, store.snapshot().revision)
            session.invoke("probe.echo", empty) { it }
            anonymous.invoke("probe.echo", empty) { it }
            session.withBearer("x".repeat(43)).invoke("probe.echo", empty) { it }
            session.invokePublic("auth.signin", empty) { it }
            assertEquals(second, store.read())
            assertEquals(2L, store.snapshot().revision)
            session.invoke("auth.logout", empty) { it }
            assertNull(store.read())
            assertEquals(3L, store.snapshot().revision)
            val missing = assertFailsWith<ClientError> { session.invoke("probe.echo", empty) { it } }
            assertEquals("unauthenticated", missing.code)
            assertNull(missing.status)
            assertFalse(missing.outcomeUnknown)
            assertEquals(6, peer.requests.get())
            assertFalse(store.compareAndSet(first, null))
            assertEquals(3L, store.snapshot().revision)
            session.invokePublic("probe.list", empty) { it }
            session.invokePublic("auth.signin", empty) { it }
            session.invoke("probe.echo", empty) { it }
            session.invokePublic("probe.list", empty) { it }
            assertEquals(second, store.read())
            assertTrue(store.compareAndSet(second, second))
            assertEquals(5L, store.snapshot().revision)
            assertEquals(listOf(null, "Bearer ${first.accessToken}", null, "Bearer ${"x".repeat(43)}",
                "Bearer ${first.accessToken}", "Bearer ${second.accessToken}", null, null,
                "Bearer ${second.accessToken}", "Bearer ${second.accessToken}"), headers)
            listOf(first.toString(), first.copy().toString(), store.toString(), session.toString()).forEach {
                assertFalse(it.contains(first.accessToken))
                assertFalse(it.contains(first.refreshToken))
            }
            assertEquals("failed_precondition", assertFailsWith<ClientError> {
                anonymous.withSession(MemoryStore("https://other.example"))
            }.code)
            assertEquals(10, peer.requests.get())
            anonymous.close()
        }
    }

    @Test
    fun attachEmptyStoreWithoutSending() = runBlocking {
        SessionPeer { it.respond("{}") }.use { peer ->
            val transport = HttpTransport(peer.origin)
            val store = MemoryStore(peer.origin)
            val session = transport.withSession(store)
            val refresh = assertFailsWith<ClientError> {
                session.invoke("auth.refresh", refreshInput(credentials('a'))) { it }
            }
            assertEquals("unauthenticated", refresh.code)
            assertNull(refresh.status)
            assertNull(store.read())
            assertEquals(0, peer.requests.get())
            transport.close()
        }
    }

    @Test
    fun verificationSignupDoesNotRequireOrInstallCredentials() = runBlocking {
        SessionPeer { exchange -> exchange.respond("""{"accepted":true,"attempt":"pending"}""", 201) }.use { peer ->
            val transport = HttpTransport(peer.origin)
            val store = MemoryStore(peer.origin)
            val response = transport.withSession(store).invokePublic("auth.signup", empty) { it }
            assertEquals(JsonPrimitive(true), response["accepted"])
            assertNull(store.read())
            assertEquals(1, peer.requests.get())
            transport.close()
        }
    }

    @Test
    fun malformedCredentialsFailBeforeSending() = runBlocking {
        SessionPeer { it.respond("{}") }.use { peer ->
            val transport = HttpTransport(peer.origin)
            val invalid = assertFailsWith<ClientError> { credentials('a').copy(accessToken = "bad") }
            assertEquals("invalid_argument", invalid.code)
            assertNull(invalid.status)
            assertFalse(invalid.outcomeUnknown)
            val session = transport.withSession(MemoryStore(peer.origin, credentials('a')))
            val malformed = assertFailsWith<ClientError> { session.invoke("auth.refresh", empty) { it } }
            assertEquals("invalid_argument", malformed.code)
            assertNull(malformed.status)
            assertFalse(malformed.outcomeUnknown)
            assertEquals(0, peer.requests.get())
            transport.close()
        }
    }

    @Test
    fun refreshOnceReplayOnceAndFrozenBodyEvenWhenReplayRejects() = runBlocking {
        for (rejectReplay in listOf(false, true)) {
            val original = credentials('a')
            val rotated = credentials('c')
            val refreshes = AtomicInteger()
            val originals = Collections.synchronizedList(mutableListOf<String>())
            val values = mutableMapOf("value" to JsonPrimitive("\ud55c\uae00 🎉"))
            val input = JsonObject(values)
            val serialized = input.toString()
            SessionPeer { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                if (exchange.requestURI.path == "/auth.refresh") {
                    if (refreshes.incrementAndGet() == 1) {
                        assertEquals("{\"refresh_token\":\"${original.refreshToken}\"}", body)
                    }
                    values["value"] = JsonPrimitive("Changed while refresh was pending.")
                    exchange.respond(wire(rotated).toString())
                } else {
                    originals += body
                    if (rejectReplay || exchange.requestHeaders.getFirst("Authorization") == "Bearer ${original.accessToken}") {
                        exchange.respond("{\"error\":\"invalid_token\"}", 401)
                    } else exchange.respond("{\"value\":\"ok\"}")
                }
            }.use { peer ->
                val store = MemoryStore(peer.origin, original)
                val session = HttpTransport(peer.origin).withSession(store)
                if (rejectReplay) {
                    val failure = assertFailsWith<OperationError> { session.invokePublic("probe.echo", input) { it } }
                    assertEquals("invalid_token", failure.code)
                } else session.invokePublic("probe.echo", input) { it }
                assertEquals(1, refreshes.get())
                assertEquals(listOf(serialized, serialized), originals)
                assertEquals(rotated, store.read())
            }
        }
    }

    @Test
    fun refreshItselfAndOtherErrorsNeverTriggerNestedRefresh() = runBlocking {
        val original = credentials('a')
        for ((code, status) in listOf("invalid_token" to 403, "forbidden" to 401, "service_busy" to 500)) {
            SessionPeer { it.respond("{\"error\":\"$code\"}", status) }.use { peer ->
                val store = MemoryStore(peer.origin, original)
                val events = mutableListOf<CallEvent>()
                val failure = assertFailsWith<OperationError> {
                    withContext(CallObservation("original", events::add)) {
                        HttpTransport(peer.origin).withSession(store).invoke("probe.echo", empty) { it }
                    }
                }
                assertEquals(status, events.last().httpStatus)
                assertEquals(1, events.count { it.phase == "headers" })
                assertEquals(1, events.count { it.phase == "completed" })
                assertEquals(code, failure.code)
                assertEquals(1, peer.requests.get())
                assertEquals(original, store.read())
            }
        }
        SessionPeer { it.respond("{\"error\":\"invalid_token\"}", 401) }.use { peer ->
            val store = MemoryStore(peer.origin, original)
            val failure = assertFailsWith<OperationError> {
                HttpTransport(peer.origin).withSession(store).invoke("auth.refresh", refreshInput(original)) { it }
            }
            assertEquals(401, failure.status)
            assertNull(store.read())
            assertEquals(1, peer.requests.get())
        }
    }

    @Test
    fun uncertainRefreshIsSuspendedAcrossClientsAndSameCredentialWrites() = runBlocking {
        for (variant in listOf("400", "403", "429", "500", "corrupt-401", "corrupt-200", "timeout", "body-timeout", "disconnect")) {
            val original = credentials('a')
            val login = credentials('e')
            val refreshes = AtomicInteger()
            val release = CountDownLatch(1)
            SessionPeer { exchange ->
                if (exchange.requestURI.path == "/auth.signin") {
                    exchange.respond(wire(login).toString())
                } else if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${login.accessToken}") {
                    exchange.respond("{}")
                } else if (exchange.requestURI.path != "/auth.refresh") {
                    exchange.respond("{\"error\":\"invalid_token\"}", 401)
                } else {
                    refreshes.incrementAndGet()
                    when (variant) {
                        "timeout" -> { release.await(5, TimeUnit.SECONDS); exchange.close() }
                        "body-timeout" -> {
                            exchange.requestBody.readBytes()
                            exchange.sendResponseHeaders(200, 0)
                            exchange.responseBody.write('{'.code)
                            exchange.responseBody.flush()
                            release.await(5, TimeUnit.SECONDS)
                            exchange.close()
                        }
                        "disconnect" -> { exchange.requestBody.readBytes(); exchange.close() }
                        "corrupt-401" -> exchange.respond("private corrupt response", 401)
                        "corrupt-200" -> exchange.respond("{\"access_token\":null}")
                        else -> exchange.respond("{\"error\":\"service_busy\"}", variant.toInt())
                    }
                }
            }.use { peer ->
                try {
                    val store = MemoryStore(peer.origin, original)
                    val session = HttpTransport(peer.origin, 500).withSession(store)
                    val failure = assertFailsWith<ClientError> { session.invoke("probe.echo", empty) { it } }
                    assertEquals(when (variant) {
                        "timeout", "disconnect" -> null
                        "body-timeout", "corrupt-200" -> 200
                        "corrupt-401" -> 401
                        else -> variant.toInt()
                    }, failure.status, variant)
                    assertEquals(when (variant) {
                        "timeout", "body-timeout" -> "deadline_exceeded"
                        "disconnect" -> "unavailable"
                        "corrupt-401", "corrupt-200" -> "internal"
                        else -> "unauthenticated"
                    }, failure.code, variant)
                    assertTrue(failure.outcomeUnknown)
                    assertNull(failure.cause)
                    assertFalse(failure.toString().contains(original.refreshToken))
                    assertNull(store.read())
                    val emptyAfterUncertainRefresh = assertFailsWith<ClientError> {
                        session.invokePublic("probe.list", empty) { it }
                    }
                    assertEquals("unauthenticated", emptyAfterUncertainRefresh.code)
                    assertNull(emptyAfterUncertainRefresh.status)
                    assertTrue(store.compareAndSet(null, original))
                    assertTrue(store.compareAndSet(original, original))
                    val next = HttpTransport(peer.origin).withSession(store)
                    for (operation in listOf("probe.echo", "auth.refresh")) {
                        val blocked = assertFailsWith<ClientError> {
                            next.invoke(operation, if (operation == "auth.refresh") refreshInput(original) else empty) { it }
                        }
                        assertEquals("unauthenticated", blocked.code)
                        assertFalse(blocked.outcomeUnknown)
                        assertNull(blocked.status)
                    }
                    assertEquals(1, refreshes.get(), variant)
                    assertEquals(2, peer.requests.get(), variant)
                    assertTrue(store.compareAndSet(original, login))
                    next.invoke("probe.echo", empty) { it }
                    assertEquals(login, store.read())
                    assertEquals(1, refreshes.get())
                } finally { release.countDown() }
            }
        }
    }

    @Test
    fun twoClientsShareOneInFlightRefresh() = runBlocking {
        for (status in listOf(200, 401, 503, 307)) concurrentRefresh(cancelFirst = false, refreshStatus = status)
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelOrRepeatSharedRefresh() = runBlocking {
        concurrentRefresh(cancelFirst = true)
    }

    @Test
    fun leaderBarrierDeadlineDoesNotFailSuccessfulSharedRefresh() = runBlocking {
        val original = credentials('a')
        val rotated = credentials('c')
        val release = CountDownLatch(1)
        val leaderJoined = CompletableDeferred<Unit>()
        val followerJoined = CompletableDeferred<Unit>()
        val refreshes = AtomicInteger()
        SessionPeer { exchange ->
            if (exchange.requestURI.path == "/auth.refresh") {
                refreshes.incrementAndGet()
                release.await(3, TimeUnit.SECONDS)
                exchange.respond(wire(rotated).toString())
            } else if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${original.accessToken}") {
                exchange.respond("{\"error\":\"invalid_token\"}", 401)
            } else exchange.respond("{}")
        }.use { peer ->
            val store = MemoryStore(peer.origin, original)
            val transport = HttpTransport(peer.origin, 2500).withSession(store)
            val startedAt = System.nanoTime()
            try {
                val leader = async(SessionObservation("leader", { point ->
                    if (point.point == "refresh-joined") {
                        leaderJoined.complete(Unit)
                        val remaining = 1000 - (System.nanoTime() - startedAt) / 1_000_000
                        withTimeout(remaining) { CompletableDeferred<Unit>().await() }
                    }
                })) {
                    runCatching { withTimeout(1000) { transport.invoke("probe.echo", empty) { it } } }
                }
                withTimeout(2000) { leaderJoined.await() }
                val follower = async(SessionObservation("follower", { point ->
                    if (point.point == "refresh-joined") followerJoined.complete(Unit)
                })) {
                    runCatching { withTimeout(2000) { transport.invoke("probe.echo", empty) { it } } }
                }
                withTimeout(1000) { followerJoined.await() }
                assertTrue(withTimeout(1500) { leader.await() }.isFailure)
                release.countDown()
                withTimeout(2000) { follower.await() }.getOrThrow()
                assertEquals(rotated, store.read())
                assertEquals(1, refreshes.get())
            } finally { release.countDown(); transport.close() }
        }
    }

    private suspend fun concurrentRefresh(cancelFirst: Boolean, refreshStatus: Int = 200) = kotlinx.coroutines.coroutineScope {
        val original = credentials('a')
        val rotated = credentials('c')
        val originalsEntered = CountDownLatch(2)
        val refreshEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refreshes = AtomicInteger()
        val originals = AtomicInteger()
        val joins = Collections.synchronizedList(mutableListOf<Any>())
        val workers = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())
        val rejected = AtomicInteger()
        val sentAtJoin = Collections.synchronizedList(mutableListOf<Int>())
        val events = mapOf("first" to Collections.synchronizedList(mutableListOf<CallEvent>()), "second" to Collections.synchronizedList(mutableListOf<CallEvent>()))
        val observations = events.mapValues { (id, events) -> CallObservation(id, events::add) }
        val joined = CountDownLatch(2)
        SessionPeer { exchange ->
            if (exchange.requestURI.path == "/auth.refresh") {
                refreshes.incrementAndGet()
                refreshEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
                if (refreshStatus == 200) exchange.respond(wire(rotated).toString())
                else exchange.respond("{\"error\":\"invalid_token\"}", refreshStatus)
            } else {
                originals.incrementAndGet()
                if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${original.accessToken}") {
                    originalsEntered.countDown()
                    assertTrue(originalsEntered.await(3, TimeUnit.SECONDS))
                    exchange.respond("{\"error\":\"invalid_token\",\"padding\":\"${"x".repeat(600000)}\"}", 401)
                } else exchange.respond("{\"padding\":\"${"x".repeat(600000)}\"}")
            }
        }.use { peer ->
            try {
                val store = MemoryStore(peer.origin, original)
                fun observation(id: String) = SessionObservation(id, { event ->
                    when (event.point) {
                        "both-originals-rejected" -> rejected.incrementAndGet()
                        "refresh-joined" -> {
                            joins += event.flight!!
                            sentAtJoin += observations.getValue(id).transmissions().count { it == "auth.refresh" }
                            joined.countDown()
                        }
                    }
                }, worker = { workers += it })
                val first = async(observation("first") + observations.getValue("first")) {
                    runCatching { HttpTransport(peer.origin).withSession(store).invoke("probe.echo", empty) { it } }
                }
                val second = async(observation("second") + observations.getValue("second")) {
                    runCatching { HttpTransport(peer.origin).withSession(store).invoke("probe.echo", empty) { it } }
                }
                awaitLatch(joined)
                awaitLatch(refreshEntered)
                assertEquals(2, rejected.get())
                assertEquals(listOf(0, 1), sentAtJoin.sorted())
                if (cancelFirst) first.cancelAndJoin()
                release.countDown()
                val firstResult = if (!cancelFirst) withTimeout(3000) { first.await() } else null
                val secondResult = withTimeout(3000) { second.await() }
                assertEquals(1, refreshes.get())
                if (refreshStatus == 200) {
                    firstResult?.getOrThrow()
                    secondResult.getOrThrow()
                } else {
                    assertTrue(firstResult!!.isFailure)
                    assertTrue(secondResult.isFailure)
                }
                assertEquals(if (refreshStatus != 200) 2 else if (cancelFirst) 3 else 4, originals.get())
                assertTrue(joins[0] === joins[1])
                assertEquals(2, workers.size)
                assertTrue(workers[0] === workers[1])
                withTimeout(1000) { workers[0].join() }
                assertTrue(workers[0].isCompleted)
                if (refreshStatus == 200) assertEquals(rotated, store.read())
                else assertNull(store.read())
                if (cancelFirst) {
                    val completed = events.getValue("first").single { it.phase == "completed" }
                    assertNull(completed.httpStatus)
                    assertNull(completed.readBytes)
                    assertTrue(events.getValue("first").all { it.callId == "first" })
                }
                if (!cancelFirst) {
                    events.forEach { (id, observed) ->
                        assertEquals(1, observed.count { it.phase == "completed" })
                        assertTrue(observed.all { it.callId == id })
                        assertTrue(observed.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
                        assertTrue(observed.all { it.readBytes == null && it.retainedBytes == null && it.peakRetainedBytes == null })
                        assertEquals(if (refreshStatus == 200) 200 else refreshStatus, observed.last().httpStatus)
                        assertTrue(observed.filter { it.phase != "read" }.all { it.httpStatus == if (refreshStatus == 200) 200 else refreshStatus })
                        assertFalse(observed.any { it.phase == "limit" })
                        val ownedRefresh = observations.getValue(id).transmissions().count { it == "auth.refresh" }
                        val originalSize = "{\"error\":\"invalid_token\",\"padding\":\"${"x".repeat(600000)}\"}".toByteArray().size
                        val retrySize = if (refreshStatus == 200) "{\"padding\":\"${"x".repeat(600000)}\"}".toByteArray().size else 0
                        val refreshSize = when (refreshStatus) {
                            200 -> wire(rotated).toString().toByteArray().size
                            307 -> 0
                            else -> "{\"error\":\"invalid_token\"}".toByteArray().size
                        }
                        assertEquals(originalSize.toLong() + retrySize + ownedRefresh * refreshSize, observations.getValue(id).readBytes)
                        if (refreshStatus == 200) assertTrue(observations.getValue(id).readBytes > 1048576)
                        assertEquals(1, observed.count { it.phase == "headers" })
                    }
                    assertEquals(1, observations.values.sumOf { it.transmissions().count { name -> name == "auth.refresh" } })
                }
            } finally { release.countDown() }
        }
    }

    @Test
    fun pendingRefreshNeverErasesOrReplaysWithNewLogin() = runBlocking {
        for (reject in listOf(true, false)) {
            val original = credentials('a')
            val rotated = credentials('c')
            val login = credentials('e')
            val refreshEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val clears = Collections.synchronizedList(mutableListOf<ClearAttempt>())
            val originals = AtomicInteger()
            SessionPeer { exchange ->
                when (exchange.requestURI.path) {
                    "/auth.refresh" -> {
                        refreshEntered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        if (reject) exchange.respond("{\"error\":\"invalid_token\"}", 401)
                        else exchange.respond(wire(rotated).toString())
                    }
                    "/auth.signin" -> exchange.respond(wire(login).toString())
                    else -> { originals.incrementAndGet(); exchange.respond("{\"error\":\"invalid_token\"}", 401) }
                }
            }.use { peer ->
                try {
                    val store = MemoryStore(peer.origin, original)
                    val session = HttpTransport(peer.origin).withSession(store)
                    val oldCall = async(SessionObservation("old", {}, clears::add)) {
                        runCatching { session.invoke("probe.echo", empty) { it } }
                    }
                    awaitLatch(refreshEntered)
                    session.invokePublic("auth.signin", empty) { it }
                    assertEquals(login, store.snapshot().credentials)
                    assertEquals(2L, store.snapshot().revision)
                    release.countDown()
                    val failure = withTimeout(3000) { oldCall.await() }.exceptionOrNull()
                    assertTrue(if (reject) failure is OperationError else failure is ClientError)
                    assertEquals(login, store.read())
                    assertEquals(2L, store.snapshot().revision)
                    assertEquals(1, originals.get())
                    assertEquals(listOf(null, false), clears.map { it.removed })
                    assertTrue(clears.all { it.expected == original && it.callId == "old" })
                } finally { release.countDown() }
            }
        }
    }

    @Test
    fun malformedLoginAndStorageFailuresNeverBecomeAnonymousOrExposeDetails() = runBlocking {
        val original = credentials('a')
        SessionPeer { it.respond("{\"access_token\":\"${original.accessToken}\"}", 201) }.use { peer ->
            val store = MemoryStore(peer.origin, original)
            val failure = assertFailsWith<ClientError> {
                HttpTransport(peer.origin).withSession(store).invokePublic("auth.signin", empty) { it }
            }
            assertEquals("internal", failure.code)
            assertEquals(201, failure.status)
            assertEquals(original, store.read())
            var unreadable = true
            val broken = object : SessionStore {
                override val origin = peer.origin
                override suspend fun read(): SessionCredentials? {
                    if (unreadable) error("Private credential ${original.refreshToken}")
                    return null
                }
                override suspend fun compareAndSet(expected: SessionCredentials?, replacement: SessionCredentials?) = false
            }
            val transport = HttpTransport(peer.origin)
            assertEquals("failed_precondition", assertFailsWith<ClientError> { transport.withSession(broken) }.code)
            unreadable = false
            val attached = transport.withSession(broken)
            unreadable = true
            val storage = assertFailsWith<ClientError> { attached.invokePublic("probe.list", empty) { it } }
            assertEquals("failed_precondition", storage.code)
            assertNull(storage.status)
            assertFalse(storage.outcomeUnknown)
            assertNull(storage.cause)
            assertFalse(storage.toString().contains(original.refreshToken))
            assertEquals(1, peer.requests.get())
        }
    }

    @Test
    fun explicitRefreshSavesFullRecordAndSharesAutomaticRefresh() = runBlocking {
        val original = credentials('a')
        val rotated = credentials('c')
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val joins = CountDownLatch(2)
        val refreshes = AtomicInteger()
        SessionPeer { exchange ->
            if (exchange.requestURI.path == "/auth.refresh") {
                refreshes.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                exchange.respond(wire(rotated).toString(), 201)
            } else if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${original.accessToken}") {
                exchange.respond("{\"error\":\"invalid_token\"}", 401)
            } else exchange.respond("{}")
        }.use { peer ->
            try {
                val store = MemoryStore(peer.origin, original)
                val first = HttpTransport(peer.origin).withSession(store)
                val second = HttpTransport(peer.origin).withSession(store)
                fun observe(id: String) = SessionObservation(id, { if (it.point == "refresh-joined") joins.countDown() })
                val automatic = async(observe("auto")) { first.invoke("probe.echo", empty) { it } }
                awaitLatch(entered)
                val explicit = async(observe("explicit")) { second.invoke("auth.refresh", refreshInput(original)) { it } }
                awaitLatch(joins)
                release.countDown()
                automatic.await()
                assertEquals(wire(rotated), explicit.await())
                assertEquals(rotated, store.read())
                assertEquals(1, refreshes.get())
            } finally { release.countDown() }
        }
        SessionPeer { it.respond(wire(rotated).toString()) }.use { peer ->
            val store = MemoryStore(peer.origin)
            val refreshed = HttpTransport(peer.origin).invoke("auth.refresh", refreshInput(original)) { WireResponse(it, 200).credentials() }
            assertTrue(store.compareAndSet(null, refreshed))
            assertEquals(rotated, store.read())
            assertEquals(1, peer.requests.get())
        }
    }

    @Test
    fun refreshStorageFailureSuspendsConsumedTokenAndPreservesSafeError() = runBlocking {
        for ((timeout, refreshStatus) in listOf(false to 200, true to 200, false to 401, true to 401)) {
            val original = credentials('a')
            val rotated = credentials('c')
            SessionPeer { exchange ->
                if (exchange.requestURI.path == "/auth.refresh") {
                    if (refreshStatus == 401) exchange.respond("{\"error\":\"invalid_token\"}", 401)
                    else exchange.respond(wire(rotated).toString())
                }
                else exchange.respond("{\"error\":\"invalid_token\"}", 401)
            }.use { peer ->
                val backing = MemoryStore(peer.origin, original)
                val broken = object : SessionStore {
                    override val origin = peer.origin
                    override suspend fun read() = backing.read()
                    override suspend fun compareAndSet(expected: SessionCredentials?, replacement: SessionCredentials?): Boolean {
                        if (timeout) CompletableDeferred<Unit>().await()
                        throw AssertionError("Private storage detail ${original.refreshToken}")
                    }
                }
                val session = HttpTransport(peer.origin, 500).withSession(broken)
                val observed = mutableListOf<CallEvent>()
                val failure = assertFailsWith<ClientError> {
                    withContext(CallObservation("storage", observed::add)) { session.invoke("probe.echo", empty) { it } }
                }
                assertEquals(refreshStatus, observed.last().httpStatus)
                assertEquals(1, observed.count { it.phase == "completed" })
                assertTrue(observed.filter { it.phase != "read" }.all { it.httpStatus == refreshStatus })
                assertEquals(if (timeout && refreshStatus != 401) "deadline_exceeded" else "failed_precondition", failure.code)
                assertEquals(refreshStatus != 401, failure.outcomeUnknown)
                assertEquals(refreshStatus, failure.status)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains(original.refreshToken))
                assertTrue(backing.compareAndSet(original, original.copy(expiresAt = "changed")))
                val blocked = assertFailsWith<ClientError> {
                    HttpTransport(peer.origin).withSession(broken).invoke("probe.echo", empty) { it }
                }
                assertEquals("unauthenticated", blocked.code)
                assertNull(blocked.status)
                assertFalse(blocked.outcomeUnknown)
                assertEquals(2, peer.requests.get())
            }
        }
    }

    @Test
    fun timedOutRefreshAttemptsCleanupWithItsOwnFiniteDeadline() = runBlocking {
        for (cleanupTimesOut in listOf(false, true)) {
            val original = credentials('a')
            val release = CountDownLatch(1)
            val cleanupAttempts = AtomicInteger()
            val cleanupCancelled = CompletableDeferred<Unit>()
            SessionPeer { exchange ->
                if (exchange.requestURI.path == "/auth.refresh") {
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                    exchange.close()
                } else exchange.respond("{\"error\":\"invalid_token\"}", 401)
            }.use { peer ->
                val backing = MemoryStore(peer.origin, original)
                val store = object : SessionStore {
                    override val origin = peer.origin
                    override suspend fun read() = backing.read()
                    override suspend fun compareAndSet(expected: SessionCredentials?, replacement: SessionCredentials?): Boolean {
                        cleanupAttempts.incrementAndGet()
                        if (cleanupTimesOut) {
                            try { CompletableDeferred<Unit>().await() }
                            finally { cleanupCancelled.complete(Unit) }
                        }
                        return backing.compareAndSet(expected, replacement)
                    }
                }
                val transport = HttpTransport(peer.origin, timeout = 250)
                try {
                    val session = transport.withSession(store)
                    val failure = assertFailsWith<ClientError> {
                        withTimeout(3000) { session.invoke("probe.echo", empty) { it } }
                    }
                    assertEquals("deadline_exceeded", failure.code)
                    assertEquals(200, failure.status)
                    assertTrue(failure.outcomeUnknown)
                    assertEquals(1, cleanupAttempts.get())
                    assertEquals(cleanupTimesOut, cleanupCancelled.isCompleted)
                    if (cleanupTimesOut) assertEquals(original, backing.read()) else {
                        assertNull(backing.read())
                        assertTrue(backing.compareAndSet(null, original))
                    }
                    val blocked = assertFailsWith<ClientError> {
                        session.invoke("probe.echo", empty) { it }
                    }
                    assertEquals("unauthenticated", blocked.code)
                    assertNull(blocked.status)
                    assertFalse(blocked.outcomeUnknown)
                    assertEquals(2, peer.requests.get())
                    assertEquals(1, cleanupAttempts.get())
                } finally { release.countDown(); transport.close() }
            }
        }
    }

    @Test
    fun lateRejectionUsesCompletedRotationButNeverExternalLogin() = runBlocking {
        for (newLogin in listOf(false, true)) {
            val original = credentials('a')
            val rotated = credentials('c')
            val login = credentials('e')
            val lateRejected = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val refreshes = AtomicInteger()
            val originals = AtomicInteger()
            SessionPeer { exchange ->
                when (exchange.requestURI.path) {
                    "/auth.refresh" -> { refreshes.incrementAndGet(); exchange.respond(wire(rotated).toString()) }
                    "/auth.signin" -> exchange.respond(wire(login).toString())
                    else -> {
                        originals.incrementAndGet()
                        if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${original.accessToken}") {
                            exchange.respond("{\"error\":\"invalid_token\"}", 401)
                        } else exchange.respond("{}")
                    }
                }
            }.use { peer ->
                val store = MemoryStore(peer.origin, original)
                val session = HttpTransport(peer.origin).withSession(store)
                val late = async(SessionObservation("late", { event ->
                    if (event.point == "both-originals-rejected") {
                        lateRejected.complete(Unit)
                        release.await()
                    }
                })) { runCatching { session.invoke("probe.echo", empty) { it } } }
                try {
                    withTimeout(3000) { lateRejected.await() }
                    session.invoke("probe.echo", empty) { it }
                    if (newLogin) session.invokePublic("auth.signin", empty) { it }
                    release.complete(Unit)
                    val result = withTimeout(3000) { late.await() }
                    if (newLogin) assertTrue(result.exceptionOrNull() is ClientError) else result.getOrThrow()
                    assertEquals(1, refreshes.get())
                    assertEquals(if (newLogin) 3 else 4, originals.get())
                    assertEquals(if (newLogin) login else rotated, store.read())
                } finally { release.complete(Unit) }
            }
        }
    }

    @Test
    fun valueEqualStoresStillHaveIndependentRefreshState() = runBlocking {
        val original = credentials('a')
        val rotated = credentials('c')
        val refreshes = AtomicInteger()
        SessionPeer { exchange ->
            if (exchange.requestURI.path == "/auth.refresh") {
                refreshes.incrementAndGet()
                exchange.respond(wire(rotated).toString())
            } else if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${original.accessToken}") {
                exchange.respond("{\"error\":\"invalid_token\"}", 401)
            } else exchange.respond("{}")
        }.use { peer ->
            class ValueEqualStore : SessionStore {
                private val backing = MemoryStore(peer.origin, original)
                override val origin = peer.origin
                override suspend fun read() = backing.read()
                override suspend fun compareAndSet(expected: SessionCredentials?, replacement: SessionCredentials?) =
                    backing.compareAndSet(expected, replacement)
                override fun equals(other: Any?) = other is ValueEqualStore
                override fun hashCode() = 1
            }
            val first = ValueEqualStore()
            val second = ValueEqualStore()
            assertEquals(first, second)
            HttpTransport(peer.origin).withSession(first).invoke("probe.echo", empty) { it }
            HttpTransport(peer.origin).withSession(second).invoke("probe.echo", empty) { it }
            assertEquals(2, refreshes.get())
            assertEquals(rotated, first.read())
            assertEquals(rotated, second.read())
        }
    }
}

private val empty = JsonObject(emptyMap())
private fun credentials(seed: Char) = SessionCredentials(seed.toString().repeat(43), (seed + 1).toString().repeat(43),
    "2026-10-14T12:15:00.123456789Z", "2026-10-21T12:00:00.123456789Z", "user", "session")

private fun refreshInput(credentials: SessionCredentials) = buildJsonObject { put("refresh_token", credentials.refreshToken) }
private fun wire(credentials: SessionCredentials) = buildJsonObject {
    put("access_token", credentials.accessToken)
    put("refresh_token", credentials.refreshToken)
    put("expires_at", credentials.expiresAt)
    put("refresh_expires_at", credentials.refreshExpiresAt)
    put("user_id", credentials.userId)
    put("session_id", credentials.sessionId)
}

private suspend fun awaitLatch(latch: CountDownLatch) = withContext(Dispatchers.IO) {
    assertTrue(latch.await(3, TimeUnit.SECONDS), "Server barrier did not arrive.")
}

private class SessionPeer(handler: (HttpExchange) -> Unit) : AutoCloseable {
    val requests = AtomicInteger()
    private val errors = Collections.synchronizedList(mutableListOf<Throwable>())
    private val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@SessionPeer.executor
        createContext("/") {
            requests.incrementAndGet()
            try { handler(it) } catch (failure: Throwable) {
                errors += failure
                it.close()
            }
        }
        start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        errors.firstOrNull()?.let { throw it }
    }
}

private fun HttpExchange.respond(body: String, status: Int = 200) {
    requestBody.readBytes()
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
