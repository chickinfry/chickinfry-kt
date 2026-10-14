plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
}

extra["okhttpVersion"] = "5.5.0"
extra["coroutinesVersion"] = "1.11.0"
extra["serializationVersion"] = "1.11.0"

allprojects {
    group = "com.chickinfry"
    version = "0.0.0"
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.chickinfry:chickinfry:0.0.0")).using(project(":runtime"))
        }
    }
}
