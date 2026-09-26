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

// Version scheme (docs/RELEASE.md): versionName is MAJOR.MINOR.PATCH, versionCode = MAJOR * 10000 + MINOR * 100 + PATCH,
// so every new name uploads with a higher code. CI may pass -Pmininetworks.versionCode=<n> to upload a rebuild of the same name.
val appVersionName = "0.9.0"
val appVersionCode = (findProperty("mininetworks.versionCode") as String?)?.toInt()
    ?: appVersionName.split('.').map(String::toInt).let { (major, minor, patch) ->
        require(minor < 100 && patch < 100) { "versionName $appVersionName: MINOR and PATCH must stay below 100" }
        major * 10000 + minor * 100 + patch
    }

// Upload key (docs/RELEASE.md): only from Gradle properties (-P, ~/.gradle/gradle.properties) or environment variables,
// never from a file in the repo. Without all four values, release builds are signed with the debug key so that
// assembleRelease/bundleRelease still work locally; Play rejects such a bundle, so nothing debug-signed can ship by accident.
fun signingValue(property: String, env: String): String? =
    (findProperty(property) as String?)?.takeIf(String::isNotBlank) ?: System.getenv(env)?.takeIf(String::isNotBlank)
val uploadStoreFile = signingValue("mininetworks.upload.storeFile", "MININETWORKS_UPLOAD_STORE_FILE")
val uploadStorePassword = signingValue("mininetworks.upload.storePassword", "MININETWORKS_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = signingValue("mininetworks.upload.keyAlias", "MININETWORKS_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = signingValue("mininetworks.upload.keyPassword", "MININETWORKS_UPLOAD_KEY_PASSWORD")
val hasUploadKey = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword).all { it != null }

android {
    namespace = "com.mininetworks.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mininetworks.game"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"$admobRewardedId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            // Debug builds run without ads and billing (NoOpMonetization) unless asked for.
            buildConfigField("boolean", "PLAY_MONETIZATION", playMonetizationInDebug.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "PLAY_MONETIZATION", "true")
        }
    }

    lint {
        // The definition of done runs lintDebug and lintRelease; any error fails the build.
        abortOnError = true
        checkReleaseBuilds = true
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

if (!hasUploadKey) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("bundle") || it.name.startsWith("assemble")) }) {
            logger.warn("Mini Networks: no upload key set (docs/RELEASE.md), the release build is signed with the DEBUG key and cannot go to Play.")
        }
    }
}
