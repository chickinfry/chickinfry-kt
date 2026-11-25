plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
}

extra["okhttpVersion"] = "5.5.1"
extra["coroutinesVersion"] = "1.11.0"
extra["serializationVersion"] = "1.11.0"

allprojects {
    group = "com.chickinfry"
    version = "0.5.0"
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.chickinfry:chickinfry:${rootProject.version}")).using(project(":runtime"))
        }
        // The publish plugin brings BouncyCastle 1.84, which carries two high-severity
        // advisories. Force the first release that clears them.
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle" && requested.version == "1.84") {
                useVersion("1.85")
            }
        }
    }
}
