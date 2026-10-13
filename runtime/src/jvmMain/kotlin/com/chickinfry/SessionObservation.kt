package com.chickinfry

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class SessionObservation(
    val callId: String,
    val point: suspend (SessionPoint) -> Unit,
    val clear: (ClearAttempt) -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SessionObservation>
}

internal data class SessionPoint(
    val point: String,
    val operation: String,
    val expected: SessionCredentials? = null,
    val flight: Any? = null,
)

internal data class ClearAttempt(
    val callId: String,
    val expected: SessionCredentials?,
    val removed: Boolean?,
)
