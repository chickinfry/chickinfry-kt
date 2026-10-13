package com.chickinfry

import kotlinx.serialization.json.JsonObject

interface ClientTransport {
    suspend fun <T> invoke(operation: String, input: JsonObject, decode: (JsonObject) -> T): T
}

class OperationError(
    val code: String,
    val httpStatus: Int,
    val outcomeUnknown: Boolean = false,
) : Exception("Operation failed.")

class ClientException(
    val code: String,
    val httpStatus: Int? = null,
    val outcomeUnknown: Boolean = false,
) : Exception(
    when (code) {
        "invalid_origin" -> "Invalid operation origin."
        "invalid_timeout" -> "Invalid request timeout."
        "invalid_operation" -> "Invalid operation name."
        "invalid_request" -> "Invalid operation request."
        "invalid_token" -> "Invalid access token."
        "session_storage_error" -> "Session storage failed."
        "request_too_large" -> "Operation request is too large."
        "response_too_large" -> "Operation response is too large."
        "redirect_not_allowed" -> "Operation redirects are not allowed."
        "request_timeout" -> "Operation request timed out."
        "transport_error" -> "Operation transport failed."
        else -> "Invalid operation response."
    },
)
