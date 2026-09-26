import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Monetization ids (docs/PLAN.md 5.1): the repo only holds Google's official test ids. Real ids come from a Gradle
// property (-P, ~/.gradle/gradle.properties, CI) or from local.properties, under the same names.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun monetizationProperty(name: String, testValue: String): String =
    (findProperty(name) as String?) ?: localProperties.getProperty(name) ?: testValue
val admobAppId = monetizationProperty("mininetworks.admob.appId", "ca-app-pub-3940256099942544~3347511713")
val admobInterstitialId = monetizationProperty("mininetworks.admob.interstitialId", "ca-app-pub-3940256099942544/1033173712")
val admobRewardedId = monetizationProperty("mininetworks.admob.rewardedId", "ca-app-pub-3940256099942544/5224354917")
val playMonetizationInDebug = monetizationProperty("mininetworks.playMonetizationInDebug", "false").toBoolean()

android {
    namespace = "com.mininetworks.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mininetworks.game"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"$admobRewardedId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Debug builds run without ads and billing (NoOpMonetization) unless asked for.
            buildConfigField("boolean", "PLAY_MONETIZATION", playMonetizationInDebug.toString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "PLAY_MONETIZATION", "true")
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
            test.systemProperty("sounds.dir", projectDir.resolve("src/main/res/raw").absolutePath)
            System.getenv("REGENERATE_SOUNDS")?.let { test.systemProperty("sounds.regenerate", it) }
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
    // Monetization (P3.4, docs/PLAN.md 5.1): AdMob, UMP consent and Play Billing.
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing:9.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
