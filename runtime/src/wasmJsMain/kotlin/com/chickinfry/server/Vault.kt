@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.chickinfry.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString

@JsFun("(text) => globalThis.__chikResources.vault(new TextEncoder().encode(text)).then(bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes.slice()))")
private external fun hostVault(text: JsString): Promise<JsString>

@JsFun("(value) => Object.keys(value)")
private external fun vaultKeys(value: JsonObject): JsAny?

@JsFun("(value, name) => { const item = value[name]; return item !== null && typeof item === 'object' && !Array.isArray(item) ? item : null; }")
private external fun vaultObject(value: JsonObject, name: JsString): JsonObject?

/** A vault binding that is unbound, malformed, or scoped to another project. */
class VaultBindingError : Exception("Vault is not available")

/**
 * Declared vault values for this release. The binding snapshot is validated against the bound
 * project identity and vault scope before any value is exposed.
 */
class Vault(private val requestId: Double) {
    // Plain arrays keep the snapshot away from collection classes whose string
    // rendering pulls unsupported text imports into the module.
    private var resolvedNames: Array<String>? = null
    private var resolvedValues: Array<String>? = null

    private suspend fun binding(name: String): String {
        val response = try {
            parseInput(hostVault("{\"rid\":${requestId.toLong()},\"name\":${jsonValue(name)}}".toJsString()).await().toString().toJsString())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw VaultBindingError()
        } ?: throw VaultBindingError()
        if (fieldCount(response) != 1) throw VaultBindingError()
        return readString(response, "value".toJsString())?.toString() ?: throw VaultBindingError()
    }

    private suspend fun resolve() {
        if (resolvedNames != null) return
        val projectId = binding("CHIK_PROJECT_ID")
        val scope = binding("CHIK_VAULT_SCOPE")
        if (projectId.isEmpty() || scope.isEmpty()) throw VaultBindingError()
        val raw = binding("CHIK_VAULT")
        val parsed = parseInput(raw.toJsString()) ?: throw VaultBindingError()
        if (readString(parsed, "projectId".toJsString())?.toString() != projectId ||
            readString(parsed, "vault".toJsString())?.toString() != scope) throw VaultBindingError()
        val values = vaultObject(parsed, "values".toJsString()) ?: throw VaultBindingError()
        val keys = vaultKeys(values) ?: throw VaultBindingError()
        val names = Array(arraySize(keys)) { index ->
            arrayString(keys, index)?.toString() ?: throw VaultBindingError()
        }
        val snapshot = Array(names.size) { index -> "" }
        for (index in 0 until arraySize(keys)) {
            snapshot[index] = readString(values, names[index].toJsString())?.toString() ?: throw VaultBindingError()
        }
        resolvedNames = names
        resolvedValues = snapshot
    }

    suspend fun get(secretName: String): String {
        resolve()
        val names = resolvedNames ?: throw VaultBindingError()
        val values = resolvedValues ?: throw VaultBindingError()
        for (index in names.indices) {
            if (names[index] == secretName) return values[index]
        }
        throw IllegalArgumentException("Secret is not bound to the vault.")
    }
}
