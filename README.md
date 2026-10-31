# Chickinfry Kotlin

Kotlin runtime for Chickinfry's generated JVM client SDKs and Kotlin/Wasm
servers. Kotlin support is in development. This package is not published to
Maven Central yet.

## Usage

Generate a Kotlin client with the Chickinfry CLI and pass a runtime transport
to its generated `createClient(transport)` function. Use HTTPS for remote origins or an
explicit loopback IP with HTTP for local development.

```kotlin
import com.chickinfry.HttpTransport
import com.chickinfry.MemoryStore

val origin = "https://api.example.com"
val transport = HttpTransport(origin)
// After signing in with the anonymous transport, save the returned credentials.
val store = MemoryStore(origin, credentials)
val signedIn = transport.withSession(store)
```

Call the suspending `withSession` function from a coroutine. It rejects a store
without credentials before sending a request. Share the same store object between clients that share a session. The memory
store does not persist credentials across process restarts. See the
[runtime guide](runtime/README.md) for session behavior, custom stores, and
Kotlin/Wasm server restrictions.

## Build and test

Use JDK 27 and Gradle 9.8.1. The JVM client runtime targets Java 8 bytecode.
The project uses Kotlin 2.4.21; Gradle resolves the compiler and dependencies.

```sh
gradle :runtime:jvmTest --no-daemon
```

To build and test both runtime targets, also make Node.js available on `PATH`:

```sh
gradle :runtime:build --no-daemon
```

## Contributing

Report bugs with a minimal reproduction, the JVM and Kotlin versions, and the
expected and actual behavior. Include tests for behavior changes. Keep
credentials and private application data out of reports and test fixtures.

## License

Apache-2.0 — see [LICENSE](LICENSE).
