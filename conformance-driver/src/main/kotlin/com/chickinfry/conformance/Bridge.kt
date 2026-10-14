package com.chickinfry.conformance

import com.chickinfry.ClientTransport
import java.io.Closeable
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader
import kotlinx.serialization.json.*

interface BridgeFactory {
    fun create(transport: ClientTransport): Bridge
}

interface Bridge {
    suspend fun call(operation: String, input: JsonObject): JsonObject
}

class DriverFailure(val code: String) : Exception("Driver request failed.")
fun invalidArgs(): Nothing = throw DriverFailure("invalid_args")
fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidArgs()
fun JsonObject.fields(required: Set<String>, optional: Set<String> = emptySet()) {
    if (!keys.containsAll(required) || !required.plus(optional).containsAll(keys)) invalidArgs()
}

internal class Fixtures(paths: Map<String, List<Path>>) : Closeable {
    private val loaders = paths.mapValues { (_, jars) ->
        URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), BridgeFactory::class.java.classLoader)
    }
    private val factories = loaders.mapValues { (fixture, loader) ->
        val providers = ServiceLoader.load(BridgeFactory::class.java, loader).toList()
        if (providers.size != 1 || providers.single().javaClass.name != "com.chickinfry.conformance.$fixture.Factory") {
            throw DriverFailure("driver_failed")
        }
        providers.single()
    }
    fun create(fixture: String, transport: ClientTransport): Bridge =
        (factories[fixture] ?: invalidArgs()).create(transport)
    override fun close() { loaders.values.forEach { it.close() } }
}
