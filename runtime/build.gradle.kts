import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("com.vanniktech.maven.publish") version "0.37.0"
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

// Central publishes through the Sonatype portal and requires a signed POM and
// artifacts. The release workflow supplies the credentials and the signing key
// from a publishing-only environment; a local build without them is unaffected.
mavenPublishing {
    publishToMavenCentral()
    // Signing is opt-out so the release workflow can stage the exact bytes first,
    // inspect them, and add the signatures as the last step before uploading.
    // Signatures are detached .asc files, so signing never changes the staged
    // artifacts the boundary scan approved.
    if (providers.gradleProperty("signPublications").orNull != "false") signAllPublications()
    coordinates("com.chickinfry", "chickinfry", project.version.toString())
    pom {
        name.set("Chickinfry")
        description.set("Kotlin runtime for Chickinfry generated clients and servers.")
        url.set("https://chickinfry.com")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }
        // Central requires developer information in the POM; without it the
        // portal rejects the first upload.
        developers {
            developer {
                id.set("chickinfry")
                name.set("Chickinfry")
                url.set("https://chickinfry.com")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/chickinfry/chickinfry-kt.git")
            developerConnection.set("scm:git:ssh://git@github.com/chickinfry/chickinfry-kt.git")
            url.set("https://github.com/chickinfry/chickinfry-kt")
        }
    }
}

// The release workflow publishes the signed artifacts into a local directory
// first, scans those exact bytes, and only then uploads. Without this repository
// the boundary scan would have to run against a rebuild, not the shipped files.
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}
