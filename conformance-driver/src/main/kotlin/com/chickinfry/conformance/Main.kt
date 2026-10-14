package com.chickinfry.conformance

import com.chickinfry.HttpTransport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val result = try {
        if (args.size != 4 || args[0] != "--plain-sdk" || args[2] != "--auth-sdk") throw DriverFailure("invalid_args")
        // SDK paths bind the generated build inputs; fixture JARs belong to this distribution.
        val inputs = mapOf("plain" to Path.of(args[1]), "auth" to Path.of(args[3]))
        if (inputs.values.any { !Files.isRegularFile(it.resolve("src/main/kotlin/com/chickinfry/client/Client.kt")) }) throw DriverFailure("driver_failed")
        val jar = Path.of(Bridge::class.java.protectionDomain.codeSource.location.toURI())
        val root = jar.parent.parent
        val paths = inputs.mapValues { (fixture, _) ->
            Files.list(root.resolve("fixtures/$fixture")).use { files ->
                files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.sorted().toList()
            }
        }
        val references = References()
        CredentialPipe().use { pipe ->
            val frames = Frames(pipe, 64 * 1024, 1024 * 1024L)
            while (true) {
                val line = frames.next() ?: break
                line.fields(setOf("ref", "value"))
                references.register(line.string("ref"), line.string("value"))
            }
        }
        initializeRuntime()
        val fixtures = Fixtures(paths)
        val driver = Driver(fixtures, references, System.out)
        try { runBlocking { driver.run(System.`in`) } }
        // Live workers keep their loaders until process exit if bounded shutdown fails.
        finally { if (driver.stopped) fixtures.close() }
        0
    } catch (_: Throwable) {
        System.err.println("Conformance driver failed.")
        1
    }
    exitProcess(result)
}

internal fun initializeRuntime() {
    HttpTransport("http://127.0.0.1").close()
    // Load the pinned HTTP/1.1 execution path before per-request deadlines begin.
    for (name in listOf(
        "okhttp3.internal.connection.RealCall",
        "okhttp3.internal.connection.ConnectPlan",
        "okhttp3.internal.connection.RealConnection",
        "okhttp3.internal.http.BridgeInterceptor",
        "okhttp3.internal.http.CallServerInterceptor",
        "okhttp3.internal.http.RetryAndFollowUpInterceptor",
        "okhttp3.internal.http1.Http1ExchangeCodec",
    )) Class.forName(name)
}
