# JVM conformance driver

This unpublished test application calls two generated Kotlin SDKs through typed
bridges. A single distribution contains parent `lib/` JARs and isolated
`fixtures/plain/` and `fixtures/auth/` JARs. Runtime, session storage, Kotlin,
coroutines and JSON classes are defined once by the parent loader. Each child
uses parent-first delegation and one fixed ServiceLoader provider. Operations
are direct generated method calls, never reflective dispatch.

Generate both fixtures with the product CLI first. Supply their generated Kotlin
module directories using `chickinfry.conformance.plainSdk` and
`chickinfry.conformance.authSdk`. The property mapping is centralized in
`settings.gradle.kts`; these names await coordinator integration. Generated
sources are neither copied into this repository nor edited.

```sh
gradle --offline \
  -Pchickinfry.conformance.plainSdk=/absolute/plain/client/generated/kotlin \
  -Pchickinfry.conformance.authSdk=/absolute/auth/client/generated/kotlin \
  :runtime:jvmTest :conformance-driver:check :conformance-driver:distZip
```

The SDK runtime still targets Java 8. Only the driver and its test bridges target
Java 22. Driver compilation uses the runtime output directory and JVM JAR as
[friend modules](https://kotlinlang.org/api/kotlin-gradle-plugin/kotlin-gradle-plugin-api/org.jetbrains.kotlin.gradle.tasks/-base-kotlin-compile/friend-paths.html).
None of these modules has a Maven publication.

Unpack `build/distributions/conformance-driver.zip`. Invoke the selected JDK
executable directly, with the explicit parent JAR classpath, without a shell or
Gradle launcher:

```sh
java --enable-native-access=ALL-UNNAMED -cp '<parent JAR classpath>' \
  com.chickinfry.conformance.MainKt \
  --plain-sdk /absolute/plain/client/generated/kotlin \
  --auth-sdk /absolute/auth/client/generated/kotlin
```

The SDK arguments identify the generated build inputs; runtime loading uses only
the packaged fixture JARs. Descriptor 3 must be an inherited anonymous credential
pipe, consumed through EOF and closed before handshake success. The driver uses
[JDK FFM call-state capture](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/lang/foreign/Linker.Option.html)
for libc `read` and `close`, retries only interrupted reads, and never reopens a
path. The supported ABI is 64-bit macOS/Linux; Linux execution is unverified.
Bootstrap lines include LF in their 64 KiB bound, with a 1 MiB total bound.
Control lines include LF in their 16 MiB bound. UTF-8 is strict, duplicate JSON
keys are rejected before object construction, and references cannot be replaced.
Protocol JSON nesting is bounded at 256 to reject stack exhaustion.

Calls have independent coroutines. Only complete stdout lines are serialized.
Runtime entry, actual 401 handling and refresh participation drive barriers;
the leader notification follows the actual flushed refresh request, before its
response is required. Store state and revision come from MemorySessionStore.
Clear traces preserve incomplete null and actual CAS booleans. No driver code
implements refresh merging, conditional deletion, case expectations or verdicts.

Advertised capabilities are `client.create`, `client.copy`, `client.call`,
`session.store`, `barrier`, and `session.trace`. `client.transport` is deliberately
absent: the complete fixed-buffer sum for OkHttp 5.5.0 / Okio 3.18.1, including
TLS, buffered-source wrapping, decompression and body copying, is **unproven**.
There is no passing bound F to report. `client.observe` remains diagnostic only:
read bytes and lifecycle events are observed, retention/peak values are null,
and `complete` is false. It cannot supply passing transport evidence. HTTP/2 is
disabled by the runtime's existing HTTP/1.1-only policy.

Constructor counters capture the actual OkHttp call-start, DNS-start and
socket-connect entry hooks. They are installed in the same HttpTransport
construction path before the OkHttp client is built, and the count is read at
construction success or SDK rejection. The construction-local ThreadLocal
binds only that synchronous counter; call IDs and transport/session observations
use coroutine context elements, including explicitly captured refresh ownership.
The counter is tested as zero during construction and positive on a real
loopback send; it is not a constant zero or command count.

Pinned OkHttp 5.5.0 source evidence (commit
`a94bdf152084d11acecd44dcd09ffef203f4f0aa`, path prefix
`okhttp/src/commonJvmAndroid/kotlin/okhttp3/`):

| Source | Construction / network boundary |
| --- | --- |
| `OkHttpClient.kt:135-140,272-302,1385` | Copies builder state, allocates a pool, initializes TLS factories and verifies local state; build constructs the client. |
| `Dispatcher.kt:97-114` | Executor allocation is lazy; constructing the dispatcher does not enqueue a request. |
| `ConnectionPool.kt:71-83` | The default pool constructs local pool state. |
| `Dns.kt:290-296` | SYSTEM delegates DNS only when lookup/newCall is invoked; initialization does not resolve a host. |
| `internal/platform/Platform.kt:98-109,217-226` | Default trust-manager and SSLContext initialization do not create a connected socket. |
| `internal/connection/RealCall.kt:182-206` | execute/enqueue invokes callStart before running or dispatching the network chain. |

The constructor source examination closes the pre-hook boundary for the selected
JVM/default providers; it is not proof of complete body-buffer retention or of
arbitrary user-installed security providers.

The process-level pipe and actual typed SDK smoke checks can be run after distZip:

```sh
python3 conformance-driver/src/test/python/pipe_test.py \
  /absolute/jdk/bin/java conformance-driver/build/distributions/conformance-driver.zip \
  /absolute/plain/client/generated/kotlin /absolute/auth/client/generated/kotlin \
  /owned/scratch/pipe
```

The pipe script uses synthetic values only and prints counts, never payloads.
The supplied scratch directory contains the unpacked test distribution.
