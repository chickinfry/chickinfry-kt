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
`settings.gradle.kts`. Generated
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
`session.store`, `barrier`, `session.trace`, and `client.transport`. Observations use the
existing `client.observe` command; it is not a separate capability. Quantitative
completeness is restricted to unencoded 3xx responses on a direct loopback
HTTP/1.1 connection with the default socket factory, Temurin 27+35, and the exact
OkHttp 5.5.0 / Okio 3.18.1 JARs below. The driver obtains these facts from the
runtime connection and response, never from case IDs. HTTP/2 remains disabled.

For that early rejection path, the conservative fixed active-body buffer sum
is F <= 49,152 bytes, within the 65,536-byte transient allowance. Each of six
buffers reserves 8,192 bytes: JDK temporary direct read, Okio socket segment,
response buffered source, optional observation wrapper, close-time discard,
and SDK read chunk. The SDK chunk, accumulation and decode are not reached by
3xx rejection. The sum concerns active response bytes, not RSS, cached direct
buffer capacity or the global segment pool. The pool's 65,536-byte limit is per
bucket, not the sum of all active buffers.

| Pinned source | Active-body bound |
| --- | --- |
| JDK `sun/nio/ch/NioSocketImpl.java:244-256`, `Util.java:136-173,221-245,292-312` | Temporary direct read: 8,192 bytes; cache reuse limits the active region, not capacity. |
| Okio `internal/DefaultSocket.kt:112-140`, `Segment.kt:61-64,179-181` | Socket segment: 8,192 bytes; each socket read/write is segment-bounded. |
| Okio `internal/RealBufferedSource.kt:38-49,135-145`, `RealBufferedSource.kt:153-173` | Body source and optional wrapper: 8,192 bytes each. |
| OkHttp `internal/http1/Http1ExchangeCodec.kt:460-470,530-538`, `internal/-UtilJvm.kt:155-180,196-203` | Discard: 8,192 bytes, cleared after each read. Framing has no separate payload array. |
| Runtime `HttpTransport.readResponse` | SDK chunk: 8,192 bytes reserved; SDK accumulation and body copies: zero on early 3xx rejection. |

OkHttp sources are pinned to `a94bdf152084d11acecd44dcd09ffef203f4f0aa`
under `okhttp/src/commonJvmAndroid/kotlin/okhttp3/`. Okio sources are pinned to
`9f564fd76310120e35b4ffb94b62d6ab2a26207d` under `okio/src/`;
`DefaultSocket.kt` and `RealBufferedSource.kt` use `jvmMain/kotlin/okio/`,
while the internal buffered-source implementation and `Segment.kt` use
`commonMain/kotlin/okio/`. JDK evidence is the installed Temurin 27+35 `lib/src.zip`.

| Artifact | SHA-256 |
| --- | --- |
| `okhttp-jvm-5.5.0.jar` | `234194a04aac54858df0a750d243af7b2e39df5e4eb86e9912043ad33a9f9a52` |
| `okio-jvm-3.18.1.jar` | `b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be` |

Gzip/other content encodings, HTTPS, other JVM/library binaries, proxies/custom
socket factories and ordinary body decode/copy paths are unproven: all three
byte fields are null and `complete` is false. Earlier measured events are
preserved. An empty valid interval can be complete before any exchange exists;
`complete` describes instrumentation, not SDK completion. Shared refresh
followers receive actual result metadata without another call's body bytes.

The shared C-04 check trusts driver-reported retained bytes; under-reporting is
caught only by the runtime oracle test, not by the shared verdict.
The JVM response-boundary test independently reads the real SDK/source buffers;
the redirect test covers all five statuses and both framings with held tails.
The same packaged driver is used by the shared SDK profile. Passing this narrow
transport check does not establish complete SDK or Kotlin product support.

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
