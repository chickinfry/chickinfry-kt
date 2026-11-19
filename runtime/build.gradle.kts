import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

kotlin {
    jvm {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    wasmJs {
        nodejs()
        compilerOptions {
            freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
        }
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.extra["coroutinesVersion"]}")
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp:${rootProject.extra["okhttpVersion"]}")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.extra["coroutinesVersion"]}")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["serializationVersion"]}")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
    rootProject.extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> {
        download.set(false)
    }
    rootProject.extensions.configure<WasmNodeJsRootExtension> {
        // The Node targets use Mocha and do not need the fork's browser startup fix.
        val karmaVersion = "6.4.4"
        versions.karma.version = karmaVersion
        toolingInstallTaskProvider.configure {
            // The plugin's lock records the Git fork as 6.4.4, so request a fresh registry resolution.
            args.addAll("karma@$karmaVersion", "--save-exact")
        }
    }
}

tasks.named<KotlinJvmCompile>("compileKotlinJvm") {
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=8")
}

tasks.named("check") {
    dependsOn(":server-test:checkRuntimeStringImports")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.release.set(8)
}

// CI-only override: Gradle stays on the mise JDK while tests run on the lower-bound JVM.
// Without the property the local default JVM runs the tests, as before.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("testJvmHome").orNull?.let { home ->
        executable = file("$home/bin/java").absolutePath
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = if (name == "kotlinMultiplatform") "chickinfry" else "chickinfry-$name"
    }
}
