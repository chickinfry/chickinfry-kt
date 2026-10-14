package com.chickinfry

import okio.Buffer

internal data class TransportEvent(
    val phase: String,
    val elapsedMs: Long,
    val httpStatus: Int?,
    val readBytes: Long,
    val retainedBytes: Long,
    val peakRetainedBytes: Long,
    val runtimeRetainedBytes: Long,
    val libraryRetainedBytes: Long,
    val transientBytes: Long,
    val transientByteBound: Long,
    val outcomeUnknown: Boolean?,
    val complete: Boolean,
    val limitReached: Boolean = false,
)

internal class TransportObservation(private val observer: (TransportEvent) -> Unit) {
    private val startedAt = System.nanoTime()
    private var status: Int? = null
    private var complete = true
    private var limitReached = false
    private var readBytes = 0L
    private var runtimeBytes = 0L
    private var libraryBytes = 0L
    private var transientBytes = 0L
    private var peak = 0L
    private var cancellationRequested = false
    private var cancellationObserved = false
    private var readerClosed = false
    private var outcomeUnknown: Boolean? = false

    @Synchronized
    fun unproven() { complete = false }

    @Synchronized
    fun outcome(value: Boolean?) {
        outcomeUnknown = value
    }

    @Synchronized
    fun cancellationRequested() {
        if (!cancellationRequested) {
            cancellationRequested = true
            emit("cancel-requested")
        }
    }

    @Synchronized
    fun cancellationObserved() {
        cancellationRequested()
        if (!cancellationObserved) {
            cancellationObserved = true
            emit("cancel-observed")
        }
    }

    @Synchronized
    fun headers(httpStatus: Int, proven: Boolean) {
        complete = complete && proven
        status = httpStatus
        emit("headers")
    }

    @Synchronized
    fun read(received: Long, retained: Long, buffer: Buffer, overflow: Boolean) {
        complete = false
        limitReached = overflow
        readBytes += received
        runtimeBytes = retained
        transientBytes = transientSize(buffer)
        emit("read")
        if (overflow) emit("limit")
        limitReached = false
    }

    @Synchronized
    fun retained(bytes: Long) {
        runtimeBytes = bytes
        emit("state")
    }

    @Synchronized
    fun emit(phase: String) {
        libraryBytes = if (transientBytes > 64 * 1024) transientBytes else 0
        val retained = runtimeBytes + libraryBytes
        peak = maxOf(peak, retained)
        observer(TransportEvent(phase, (System.nanoTime() - startedAt) / 1_000_000,
            status, readBytes, retained, peak, runtimeBytes, libraryBytes, transientBytes, 64 * 1024, outcomeUnknown, complete, limitReached))
    }

    @Synchronized
    fun closed() {
        if (!readerClosed) {
            readerClosed = true
            transientBytes = 0
            emit("reader-closed")
        }
    }

    @Synchronized
    fun completed() {
        runtimeBytes = 0
        transientBytes = 0
        emit("completed")
    }

    private fun transientSize(buffer: Buffer): Long = buffer.size
}
