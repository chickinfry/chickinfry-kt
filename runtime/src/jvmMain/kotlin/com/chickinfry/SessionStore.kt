package com.chickinfry

/** Expiry timestamps and identifiers are kept exactly as the server returned them. */
data class SessionCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val refreshExpiresAt: String,
    val userId: String,
    val sessionId: String,
) {
    init {
        if (!credentialPattern.matches(accessToken) || !credentialPattern.matches(refreshToken)) {
            throw ClientException("invalid_token")
        }
    }

    override fun toString(): String = "SessionCredentials"
}

/** Clients in the same process must share one store instance. */
interface SessionStore {
    val origin: String
    suspend fun read(): SessionCredentials?

    /** Compares and replaces atomically; replacing with null deletes conditionally. */
    suspend fun compareAndSet(expected: SessionCredentials?, replacement: SessionCredentials?): Boolean
}

/** In-memory store; neither records nor unknown refresh outcomes survive a process restart. */
class MemorySessionStore(origin: String, initial: SessionCredentials? = null) : SessionStore {
    override val origin: String = validateOrigin(origin).toString().removeSuffix("/")
    private val lock = Any()
    private var credentials = initial
    private var revision = if (initial == null) 0L else 1L

    override suspend fun read(): SessionCredentials? = synchronized(lock) { credentials }

    override suspend fun compareAndSet(expected: SessionCredentials?, replacement: SessionCredentials?): Boolean =
        synchronized(lock) {
            if (credentials != expected) false else {
                credentials = replacement
                revision++
                true
            }
        }

    internal fun snapshot(): SessionSnapshot = synchronized(lock) { SessionSnapshot(credentials, revision) }

    override fun toString(): String = "MemorySessionStore"
}

internal data class SessionSnapshot(val credentials: SessionCredentials?, val revision: Long)
internal val credentialPattern = Regex("[A-Za-z0-9_-]{43}")
