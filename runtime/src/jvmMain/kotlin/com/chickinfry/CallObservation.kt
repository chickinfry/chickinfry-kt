package com.chickinfry

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class CallEvent(
    val callId: String,
    val phase: String,
    val elapsedMs: Long,
    val httpStatus: Int?,
    val readBytes: Long?,
    val retainedBytes: Long?,
    val peakRetainedBytes: Long?,
)

internal class CallObservation(
    val callId: String,
    private val observer: (CallEvent) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallObservation>
    private var startedAt: Long? = null
    internal var readBytes = 0L
        private set
    internal var peak = 0L
        private set
    private val exchanges = mutableListOf<Exchange>()
    private var selected: Exchange? = null
    private var finished = false
    private var complete = true
    private val rawDetails = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    @Synchronized fun entered() { if (startedAt == null) startedAt = System.nanoTime() }
    @Synchronized fun transmitted(operation: String) { sent.add(operation) }
    @Synchronized fun transmissions(): List<String> = sent.toList()
    var outcomeUnknown: Boolean? = false
        private set
    var httpStatus: Int? = null
        private set

    internal data class ResultMetadata(val exchangeId: Any, val event: TransportEvent?, val phases: List<String>)

    internal inner class Exchange(observer: ((TransportEvent) -> Unit)? = null) {
        val exchangeId = Any()
        val owner = this@CallObservation
        var event: TransportEvent? = null
        var read = 0L
        val phases = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val transport = TransportObservation { event ->
            observer?.invoke(event)
            accept(this, event)
        }
        fun metadata(): ResultMetadata = synchronized(owner) { ResultMetadata(exchangeId, event, phases.toList()) }
    }

    @Synchronized
    fun exchange(deferred: Boolean, observer: ((TransportEvent) -> Unit)? = null): Exchange = Exchange(observer).also {
        exchanges += it
        if (!deferred) selected = it
    }

    @Synchronized
    fun select(exchange: Exchange?) {
        selected = exchange
        selected?.let { flush(it) }
        outcomeUnknown = selected?.event?.outcomeUnknown
        httpStatus = selected?.event?.httpStatus
    }

    fun selectResult(metadata: ResultMetadata?) {
        synchronized(this) {
            val exchange = exchanges.firstOrNull { it.exchangeId === metadata?.exchangeId }
            if (exchange != null || metadata == null) select(exchange) else {
                selected = Exchange().also {
                    it.event = metadata.event?.copy(readBytes = 0, retainedBytes = 0, peakRetainedBytes = 0, complete = false)
                    it.pending += metadata.phases
                }
                complete = false
                selected?.let { flush(it) }
                outcomeUnknown = selected?.event?.outcomeUnknown
                httpStatus = selected?.event?.httpStatus
            }
        }
    }

    @Synchronized
    fun incomplete() { complete = false }

    @Synchronized
    private fun accept(exchange: Exchange, event: TransportEvent) {
        readBytes += event.readBytes - exchange.read
        exchange.read = event.readBytes
        exchange.event = event
        complete = complete && event.complete && !finished
        peak = maxOf(peak, exchanges.sumOf { it.event?.retainedBytes ?: 0 })
        if (selected === exchange && !finished) {
            outcomeUnknown = event.outcomeUnknown
            httpStatus = event.httpStatus
        }
        if (event.phase == "completed" || event.phase == "state") return
        if (event.phase == "limit") return
        if (!finished && event.phase == "headers" && event.httpStatus in 300..399) selected = exchange
        if (event.phase == "read") {
            publish("read", event)
            if (event.limitReached) {
                publish("limit", event)
                if (!finished) {
                    selected = exchange
                    flush(exchange)
                }
            }
        } else {
            exchange.phases += event.phase
            if (selected === exchange) publish(event.phase, event) else exchange.pending += event.phase
        }
    }

    private fun flush(exchange: Exchange) {
        exchange.pending.forEach { publish(it, exchange.event) }
        exchange.pending.clear()
    }

    @Synchronized
    fun completed() {
        if (finished) return
        if (exchanges.any { it.event?.httpStatus != null && "reader-closed" !in it.phases }) complete = false
        finished = true
        publish("completed", selected?.event)
    }

    private fun publish(phase: String, event: TransportEvent?) {
        val measured = complete && (event?.complete != false)
        observer(CallEvent(callId, phase, startedAt?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0,
            event?.httpStatus, readBytes.takeIf { measured },
            exchanges.sumOf { it.event?.retainedBytes ?: 0 }.takeIf { measured }, peak.takeIf { measured }))
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
