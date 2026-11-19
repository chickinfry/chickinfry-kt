@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

 package com.chickinfry.server

 import kotlinx.coroutines.CancellationException
 import kotlinx.coroutines.await
 import kotlin.js.JsAny
 import kotlin.js.JsString
 import kotlin.js.Promise
 import kotlin.js.toJsString

 private const val MAX_OBJECT_BYTES = 4 * 1024 * 1024

 // Object bytes cross the string-only WebAssembly boundary as JSON numbers in the frame.
 @JsFun("""(text) => { const frame = JSON.parse(text); const put = frame.bytes !== undefined;
    return (put ? globalThis.__chikResources.storage("put", frame.name, frame.key, Uint8Array.from(JSON.parse("[" + frame.bytes + "]")), frame.contentType, frame.rid) : globalThis.__chikResources.storage("get", frame.name, frame.key, undefined, undefined, frame.rid)).then(result => {
         if (result === null || typeof result !== "object" || Array.isArray(result) || result.error !== undefined) return JSON.stringify(result ?? {});
         if (put) return "{}";
         const value = result.value;
         if (value === null) return '{"value":null}';
         if (!(value instanceof Uint8Array)) return '{"error":"operation_failed"}';
         return '{"value":[' + Array.prototype.join.call(value, ", ") + ']}';
     }); }""")
 private external fun hostStorageCall(text: JsString): Promise<JsString>

 @JsFun("(value, name) => Object.hasOwn(value, name) && value[name] === null")
 private external fun storageIsNull(value: JsonObject, name: JsString): Boolean

 @JsFun("(value, name) => Object.hasOwn(value, name) ? value[name] : undefined")
 private external fun storageField(value: JsonObject, name: JsString): JsAny?

 @JsFun("(array, index) => Number.isInteger(array[index]) && array[index] >= 0 && array[index] <= 255 ? array[index] : NaN")
 private external fun storageInteger(array: JsAny, index: Int): Double

 /** A storage operation failure that is not a declared-binding availability problem. */
 class StorageError : Exception("Storage operation failed")

 private fun jsonBytes(bytes: ByteArray): String {
    val builder = StringBuilder()
     for (index in bytes.indices) {
         if (index > 0) builder.append(", ")
         builder.append(bytes[index].toInt().toString())
     }
     return builder.toString()
 }

 /**
  * A declared storage binding. [get] returns the stored object bytes, or null when the key is absent.
  */
 class Storage(private val requestId: Double, private val name: String) {
     private fun check(response: JsonObject) {
         val error = readString(response, "error".toJsString())?.toString()
         if (error == "service_unavailable") throw OperationError("service_unavailable", 503)
         if (error != null) throw StorageError()
     }

     private suspend fun call(text: String): JsonObject {
         val response = try {
             hostStorageCall(text.toJsString()).await().toString()
         } catch (error: CancellationException) {
             throw error
         } catch (_: Throwable) {
             throw StorageError()
         }
         return parseInput(response.toJsString()) ?: throw StorageError()
     }

     suspend fun get(key: String): ByteArray? {
         val response = call("{\"rid\":${requestId.toLong()},\"name\":${jsonValue(name)},\"key\":${jsonValue(key)}}")
         check(response)
         if (!hasField(response, "value".toJsString())) throw StorageError()
         if (storageIsNull(response, "value".toJsString())) return null
         val value = storageField(response, "value".toJsString()) ?: throw StorageError()
         val decoded = ByteArray(arraySize(value))
         for (index in decoded.indices) {
             val number = storageInteger(value, index)
             if (number.isNaN()) throw StorageError()
             decoded[index] = number.toInt().toByte()
         }
         if (decoded.size > MAX_OBJECT_BYTES) throw StorageError()
         return decoded
     }

     suspend fun put(key: String, bytes: ByteArray, contentType: String) {
         if (bytes.size > MAX_OBJECT_BYTES) throw StorageError()
         val response = call("{\"rid\":${requestId.toLong()},\"name\":${jsonValue(name)},\"key\":${jsonValue(key)},\"bytes\":${jsonValue(jsonBytes(bytes))},\"contentType\":${jsonValue(contentType)}}")
         check(response)
     }
 }
