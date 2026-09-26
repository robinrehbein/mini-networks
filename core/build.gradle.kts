import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM game logic: no Android dependency, fast JVM tests, portable to other front ends.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    // Save games: the world snapshot is written as JSON (docs/PLAN.md 4.1).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Balancing report (docs/BALANCING.md): `BALANCING_REPORT=1 ./gradlew :core:test --tests '*BalancingTest*'` rewrites it.
    systemProperty("balancing.file", rootDir.resolve("docs/BALANCING.md").absolutePath)
    System.getenv("BALANCING_REPORT")?.let { systemProperty("balancing.report", it) }
    System.getenv("BALANCING_SEEDS")?.let { systemProperty("balancing.seeds", it) }
}

// Lets the root `./gradlew testDebugUnitTest` (the definition of done) run the core tests as well.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the :core unit tests; alias so the Android-wide test task covers this module."
    dependsOn(tasks.named("test"))
}
