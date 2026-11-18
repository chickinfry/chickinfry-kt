pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "chickinfry"
include(":runtime")
include(":server-test")
project(":server-test").projectDir = file("tests/server")

include(":conformance-driver")
val sdkProperties = mapOf(
    "plain" to "chickinfry.conformance.plainSdk",
    "auth" to "chickinfry.conformance.authSdk",
    "emptySession" to "chickinfry.conformance.emptySessionSdk",
)
for ((fixture, property) in sdkProperties) {
    if (fixture != "emptySession") include(":conformance-driver:$fixture")
    providers.gradleProperty(property).orNull?.let { path ->
        include(":conformance-driver:$fixture", ":conformance-driver:${fixture}Sdk")
        project(":conformance-driver:${fixture}Sdk").projectDir = file(path)
    }
}
