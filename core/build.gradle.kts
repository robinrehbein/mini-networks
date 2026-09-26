import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM game logic: no Android dependency, fast JVM tests, portable to other front ends.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Lets the root `./gradlew testDebugUnitTest` (the definition of done) run the core tests as well.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the :core unit tests; alias so the Android-wide test task covers this module."
    dependsOn(tasks.named("test"))
}
