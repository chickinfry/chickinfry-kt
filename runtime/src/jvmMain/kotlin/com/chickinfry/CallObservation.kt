package com.chickinfry

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class CallEvent(
    val callId: String,
    val phase: String,
    val elapsedMs: Long,
    val httpStatus: Int?,
    val readBytes: Long,
    val retainedBytes: Long,
    val peakRetainedBytes: Long,
)

internal class CallObservation(
    val callId: String,
    private val observer: (CallEvent) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallObservation>
    private val startedAt = System.nanoTime()
    private var readBytes = 0L
    private var peak = 0L
    private var last: Exchange? = null
    private var finished = false
    private val rawDetails = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    @Synchronized fun transmitted(operation: String) { sent.add(operation) }
    @Synchronized fun transmissions(): List<String> = sent.toList()
    var outcomeUnknown: Boolean? = false
        private set
    var httpStatus: Int? = null
        private set

    internal inner class Exchange(val deferred: Boolean) {
        var event: TransportEvent? = null
        var read = 0L
        val pending = linkedSetOf<String>()
    }

    @Synchronized
    fun exchange(deferred: Boolean): TransportObservation {
        val exchange = Exchange(deferred)
        last = exchange
        return TransportObservation { event -> accept(exchange, event) }
    }

    @Synchronized
    private fun accept(exchange: Exchange, event: TransportEvent) {
        readBytes += event.readBytes - exchange.read
        exchange.read = event.readBytes
        exchange.event = event
        peak = maxOf(peak, event.peakRetainedBytes)
        if (last === exchange) {
            outcomeUnknown = event.outcomeUnknown
            httpStatus = event.httpStatus
        }
        if (event.phase == "completed") return
        if (event.phase == "read" || event.phase == "limit" || !exchange.deferred) {
            publish(event.phase, event)
        } else exchange.pending += event.phase
    }

    @Synchronized
    fun completed() {
        if (finished) return
        finished = true
        last?.let { exchange -> exchange.event?.let { event ->
            exchange.pending.forEach { publish(it, event) }
        } }
        publish("completed", last?.event)
    }

    private fun publish(phase: String, event: TransportEvent?) {
        observer(CallEvent(callId, phase, (System.nanoTime() - startedAt) / 1_000_000,
            event?.httpStatus, readBytes, if (phase == "completed") 0 else event?.retainedBytes ?: 0, peak))
    }

    @Synchronized
    fun raw(value: String) {
        if (value.isNotEmpty()) rawDetails += value
    }

    @Synchronized
    fun exposesRawDetails(failure: Throwable): Boolean {
        val visible = generateSequence(failure) { it.cause }.take(16)
            .flatMap { sequenceOf(it.message.orEmpty(), it.toString()) }.toList()
        return visible.any { text -> rawDetails.any { it in text } }
    }
}

internal class NetworkCounter {
    private val count = AtomicLong()
    fun entered() { count.incrementAndGet() }
    fun snapshot(): Long = count.get()

    companion object {
        private val construction = ThreadLocal<NetworkCounter>()
        fun current(): NetworkCounter? = construction.get()
        fun <T> capture(counter: NetworkCounter, create: () -> T): T {
            val previous = construction.get()
            construction.set(counter)
            return try { create() } finally {
                if (previous == null) construction.remove() else construction.set(previous)
            }
        }
    }
}
