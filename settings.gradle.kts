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

include(":conformance-driver")
val sdkProperties = mapOf(
    "plain" to "chickinfry.conformance.plainSdk",
    "auth" to "chickinfry.conformance.authSdk",
)
for ((fixture, property) in sdkProperties) {
    include(":conformance-driver:$fixture")
    providers.gradleProperty(property).orNull?.let { path ->
        include(":conformance-driver:${fixture}Sdk")
        project(":conformance-driver:${fixture}Sdk").projectDir = file(path)
    }
}
