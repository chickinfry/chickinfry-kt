import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
    sourceSets {
        jvmMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp:${rootProject.extra["okhttpVersion"]}")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.extra["coroutinesVersion"]}")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["serializationVersion"]}")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

tasks.named<KotlinJvmCompile>("compileKotlinJvm") {
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=8")
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
