# Kotlin session transport

Create a transport for one origin and share a session store between clients:

```kotlin
import com.chickinfry.HttpTransport
import com.chickinfry.MemorySessionStore

val anonymous = HttpTransport("https://api.example.com")
val store = MemorySessionStore("https://api.example.com")
val signedIn = anonymous.withSession(store)
```

Pass `signedIn` to the generated SDK's `Client` constructor. Successful
`auth.signup`, `auth.signin`, and `auth.refresh` responses are stored atomically.
Subsequent calls use the current access token; successful `auth.logout` clears
only the credentials used by that call. The anonymous transport stays anonymous.
`withBearer(token)` creates a copy with a fixed bearer and no session refresh.
An origin mismatch is rejected before a network request.

Implement `SessionStore` for application-owned persistence. Its immutable
`origin` identifies the store's canonical origin. `read()` returns null only for
an absent session; failures must throw. `compareAndSet(expected, replacement)`
must compare the full record and apply the replacement atomically. A null
replacement deletes only the matching record. `SessionCredentials` preserves
the response's `access_token`, `refresh_token`, `expires_at`,
`refresh_expires_at`, `user_id`, and `session_id`; timestamps remain strings.
Never put credentials in logs. Credential and memory-store string representations
omit their contents.

A normal `invalid_token`/401 response permits one refresh and one replay of the
same serialized request. Other errors and refresh calls do not trigger nested
refresh. Calls sharing the **same store object** in one process share an in-flight
refresh, including explicit refresh of those credentials. Cancelling a waiting
call does not cancel the shared refresh. A refresh has a finite deadline based on
its initiating transport's request timeout.

Only a normal error envelope with HTTP 401 confirms refresh rejection and permits
conditional session deletion. All other refresh failures, including 400, 403,
429, 5xx, malformed responses, timeout, connection loss, or storage failure, leave
the old credentials suspended. Later calls with those credentials fail locally
with `invalid_token`, `outcomeUnknown = true`, and no HTTP status. Rewriting the
same credentials or making another client does not lift the suspension. A
successful new login with different credentials allows calls to proceed.
An older refresh cannot overwrite or clear a newer login, and its original
request is never replayed using that newer login.

Sharing persistence through different store objects, across processes, or across
separate runtime classloaders does not coordinate refresh. Suspension is kept in
memory while the store object lives and is not restored after process restart.
Persistent implementations need their own policy for that boundary; this runtime
does not provide a recovery command or a disk/Keystore implementation.
