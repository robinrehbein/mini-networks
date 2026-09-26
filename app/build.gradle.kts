plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mininetworks.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mininetworks.game"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("screenshots.dir", rootDir.resolve("docs/screenshots").absolutePath)
            // Optional: point Robolectric at pre-downloaded android-all jars (CI / sandboxed containers).
            System.getenv("ROBOLECTRIC_DEPS_DIR")?.let {
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", it)
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
