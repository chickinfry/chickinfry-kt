# Kotlin session transport

Create a transport for one origin and share a session store between clients:

```kotlin
import com.chickinfry.HttpTransport
import com.chickinfry.MemoryStore

val anonymous = HttpTransport("https://api.example.com")
val store = MemoryStore("https://api.example.com", credentials)
val signedIn = anonymous.withSession(store)
```

First call signup or signin using the anonymous transport and record the returned
`SessionCredentials` in the store. Call the suspending `withSession` function
from a coroutine; an empty store is rejected with `ClientError("unauthenticated")`
and no HTTP status. Store-read failures use `failed_precondition` unless the
store already throws a `ClientError`.

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
its initiating transport's request timeout. A failed refresh gets a separate
cleanup period with the same timeout so conditional session deletion is still
attempted after a network timeout. Refresh may therefore take about twice the
configured timeout; cleanup never retries the request. If cleanup also times
out, matching credentials remain stored but blocked.

A normal error envelope with HTTP 401 confirms refresh rejection, conditionally
clears the session, and preserves the original `OperationError`. A storage failure
while clearing that confirmed rejection uses `failed_precondition`, status 401,
and `outcomeUnknown = false`.

Other refresh failures block the old credentials and attempt to clear only the
matching record. Existing `ClientError` values are preserved; other failures use
`unauthenticated` with a received status, if any, and `outcomeUnknown = true`.
A cleanup failure never replaces the original uncertain refresh failure.
Later calls that restore those blocked credentials fail locally with
`unauthenticated`, `outcomeUnknown = false`, and no HTTP status. Rewriting the
same credentials or making another client does not lift the suspension. A
successful new login with different credentials allows calls to proceed.
An older refresh cannot overwrite or clear a newer login, and its original
request is never replayed using that newer login.

Sharing persistence through different store objects, across processes, or across
separate runtime classloaders does not coordinate refresh. Suspension is kept in
memory while the store object lives and is not restored after process restart.
Persistent implementations need their own policy for that boundary; this runtime
does not provide a recovery command or a disk/Keystore implementation.

## Transport failures and timeout

`HttpTransport(origin, timeout = 30_000)` takes one timeout in milliseconds.
The same limit covers the whole call, including queued requests, connection,
writing, and reading. The accepted range is 1 through 2,147,483,647.

`Transport` is the replaceable client interface. Remote failures use
`OperationError(code, status)`; local failures use
`ClientError(code, status = null, outcomeUnknown = false)`. The local codes are
`invalid_argument`, `failed_precondition`, `resource_exhausted`,
`deadline_exceeded`, `canceled`, `unavailable`, `data_loss`, `unimplemented`,
`internal`, `unknown`, and `unauthenticated`. Only received HTTP statuses are
retained, including when a timeout happens after response headers. A timeout
before headers has no status. Caller coroutine cancellation continues to throw
`CancellationException`; a transport abort without caller cancellation uses
`ClientError("canceled")`.

Invalid origin, timeout, operation, input, or bearer arguments use
`invalid_argument`; an attached store with another origin uses
`failed_precondition`. Size limits use `resource_exhausted`; invalid responses
and redirects use `internal`. Fixed exception messages contain only the code
and a received status, never the original transport or storage exception text.
`outcomeUnknown` is false for failures proven to occur before transmission
and true when the server may have performed the operation. Business operations
are not automatically retried.

## Kotlin server: unsupported text functions

Some Kotlin text functions cannot run in Chickinfry servers yet. If the server
build needs them, Chickinfry rejects it before starting or deploying it. Replace
the calls and rebuild; changing your handler's inputs does not fix the build.
This restriction applies to Kotlin/Wasm servers, not the JVM client transport.

| Function | Alternative |
| --- | --- |
| `padStart` | Add string prefixes in a loop, as in `padWithZeros` below. |
| `lowercase`, `uppercase` | For an ASCII-only domain, map letters using string slices. Use the opposite alphabet/range for uppercase. This does not replace Unicode case conversion. |
| `Regex` | For fixed formats, check length, separators, character ranges and numeric bounds directly. Keep every validation rule. |
| `StringBuilder.append(Char)` | Append a string slice: `builder.append(text.substring(index, index + 1))`. String append and concatenation remain usable. |

```kotlin
fun padWithZeros(value: String, width: Int): String {
    var result = value
    while (result.length < width) result = "0" + result
    return result
}

fun asciiLowercase(value: String): String = buildString {
    require(value.all { it.code < 128 })
    val alphabet = "abcdefghijklmnopqrstuvwxyz"
    for (index in value.indices) {
        val char = value[index]
        append(if (char in 'A'..'Z') alphabet.substring(char - 'A', char - 'A' + 1)
            else value.substring(index, index + 1))
    }
}
```

The server build checks these prefix and ASCII-lowercase examples along with its
database runtime. The runtime uses fixed-format validation for dates, timestamps
and UUIDs, including calendar and offset bounds.

Standard map and set construction can also retain unsupported text functions
through the collections' default formatting, even without an explicit `toString`
call. Generated database code avoids those paths internally. Keep application
logic on the generated typed rows and repositories; a custom collection or text
operation can still make a server build fail. These alternatives do not establish
support for other standard-library text functions.
