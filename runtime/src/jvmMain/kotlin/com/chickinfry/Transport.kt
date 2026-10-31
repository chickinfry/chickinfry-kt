package com.chickinfry

import kotlinx.serialization.json.JsonObject

interface Transport {
    suspend fun <T> invoke(operation: String, input: JsonObject, decode: (JsonObject) -> T): T
}

class OperationError(
    val code: String,
    val status: Int,
) : Exception("Operation failed ($code, HTTP $status).")

class ClientError(
    val code: String,
    val status: Int? = null,
    val outcomeUnknown: Boolean = false,
) : Exception("Client failed ($code${status?.let { ", HTTP $it" } ?: ""}).")
