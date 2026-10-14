import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
    application
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_22) } }
java { sourceCompatibility = JavaVersion.VERSION_22; targetCompatibility = JavaVersion.VERSION_22 }
application {
    mainClass.set("com.chickinfry.conformance.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
dependencies {
    implementation(project(":runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.extra["coroutinesVersion"]}")
    testImplementation(kotlin("test-junit"))
}
evaluationDependsOn(":runtime")
val runtimeCompile = project(":runtime").tasks.named<KotlinJvmCompile>("compileKotlinJvm")
tasks.withType<KotlinJvmCompile>().configureEach {
    dependsOn(runtimeCompile)
    friendPaths.from(runtimeCompile.flatMap { it.destinationDirectory })
    friendPaths.from(project(":runtime").tasks.named<Jar>("jvmJar").flatMap { it.archiveFile })
}
for (fixture in listOf("plain", "auth")) {
    evaluationDependsOn(":conformance-driver:$fixture")
    val bridge = project(":conformance-driver:$fixture")
    val sdk = findProject(":conformance-driver:${fixture}Sdk")
    if (sdk != null) {
        evaluationDependsOn(sdk.path)
        val jars = listOf(bridge.tasks.named<Jar>("jar"), sdk.tasks.named<Jar>("jar"))
        distributions.main { contents { into("fixtures/$fixture") { from(jars) } } }
        tasks.named<Test>("test") {
            dependsOn(jars)
            doFirst { systemProperty("fixture.$fixture", jars.joinToString(File.pathSeparator) { it.get().archiveFile.get().asFile.absolutePath }) }
        }
    }
}
tasks.named<Test>("test") { jvmArgs("--enable-native-access=ALL-UNNAMED") }
tasks.named<Zip>("distZip") { archiveFileName.set("conformance-driver.zip") }

tasks.matching { it.name in listOf("test", "distZip") }.configureEach {
    doFirst {
        require(findProject(":conformance-driver:plainSdk") != null && findProject(":conformance-driver:authSdk") != null) {
            "Both generated fixture SDK paths must be supplied through the conformance Gradle properties."
        }
    }
}
