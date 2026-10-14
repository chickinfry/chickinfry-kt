plugins { kotlin("jvm") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22) } }
java { sourceCompatibility = JavaVersion.VERSION_22; targetCompatibility = JavaVersion.VERSION_22 }
dependencies {
    implementation(project(":conformance-driver"))
    val sdk = findProject(":conformance-driver:plainSdk")
    if (sdk != null) implementation(sdk)
}
