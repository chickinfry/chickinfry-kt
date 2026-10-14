plugins {
    kotlin("multiplatform")
}

kotlin {
    wasmJs {
        nodejs()
        binaries.library()
        compilerOptions.freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
    }
    sourceSets.wasmJsMain.dependencies {
        implementation(project(":runtime"))
    }
}

tasks.register<Exec>("checkRuntimeStringImports") {
    dependsOn("wasmJsProductionLibraryCompileSync")
    commandLine("node", "check-imports.mjs", layout.buildDirectory.dir("compileSync/wasmJs/main/productionLibrary/optimized").get().asFile)
}
