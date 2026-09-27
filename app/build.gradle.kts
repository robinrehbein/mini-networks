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
/** Publisher id of Google's sample ad ids; release builds warn (or fail) while any id still uses it. */
val admobTestPublisher = "ca-app-pub-3940256099942544"
val admobAppId = monetizationProperty("mininetworks.admob.appId", "$admobTestPublisher~3347511713")
val admobInterstitialId = monetizationProperty("mininetworks.admob.interstitialId", "$admobTestPublisher/1033173712")
val admobRewardedId = monetizationProperty("mininetworks.admob.rewardedId", "$admobTestPublisher/5224354917")
val admobTestIds = listOf(admobAppId, admobInterstitialId, admobRewardedId).filter { it.startsWith(admobTestPublisher) }
val playMonetizationInDebug = monetizationProperty("mininetworks.playMonetizationInDebug", "false").toBoolean()
// Play Games Services and In-App Review (docs/TOP100.md C2, C3, C6, D1): no-op in debug builds unless asked for.
val playServicesInDebug = monetizationProperty("mininetworks.playServicesInDebug", "false").toBoolean()
/** games-ids.xml still holds the placeholders of the repo (docs/RELEASE.md 11): Play Games then stays off at runtime. */
val gamesIdsArePlaceholders = file("src/main/res/values/games-ids.xml").readText().contains(">TODO_")

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
    // Google Play requires target API 36 for new apps and updates since 31 Aug 2026 (docs/RELEASE.md 10).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mininetworks.game"
        minSdk = 26
        targetSdk = 36
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
            buildConfigField("boolean", "PLAY_SERVICES", playServicesInDebug.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "PLAY_MONETIZATION", "true")
            buildConfigField("boolean", "PLAY_SERVICES", "true")
        }
    }

    lint {
        // The definition of done runs lintDebug and lintRelease; any error fails the build. lint.xml says what is ignored and why.
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
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
    // 25.x: its breaking changes only touch mediation, native ads and banners, none of which the game uses. 25.3 and
    // later are built with Kotlin 2.3 metadata, which the project's Kotlin 2.1 cannot read (app/lint.xml).
    implementation("com.google.android.gms:play-services-ads:25.2.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing:9.1.0")
    // Baseline Profile (docs/TOP100.md A4): installs src/main/baseline-prof.txt at install time on devices where Play
    // does not ship cloud profiles yet (sideloads, early installs), so startup and the game loop run AOT-compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Play Games Services v2 (docs/TOP100.md C2, C3, C6): sign-in, achievements, leaderboards, Saved Games. Behind
    // games/GameServices.kt; debug builds and tests use NoOpGameServices, release builds only with real ids (games-ids.xml).
    implementation("com.google.android.gms:play-services-games-v2:22.1.0")
    // In-App Review (D1), behind review/ReviewPrompt.kt; when to ask is decided by :core ReviewPolicy.
    implementation("com.google.android.play:review:2.0.2")
    // FileProvider for the share card (D2); pinned to the version play-services-ads already resolves (newer androidx.core
    // releases are built with a newer Kotlin than the project's 2.1, see the note on play-services-ads above).
    //noinspection GradleDependency
    implementation("androidx.core:core:1.15.0")
    testImplementation(testFixtures(project(":core")))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

gradle.taskGraph.whenReady {
    val releaseBuild = allTasks.any {
        it.project == project && it.name.contains("Release") && (it.name.startsWith("bundle") || it.name.startsWith("assemble"))
    }
    if (!releaseBuild) return@whenReady
    if (!hasUploadKey) {
        logger.warn("Mini Networks: no upload key set (docs/RELEASE.md), the release build is signed with the DEBUG key and cannot go to Play.")
    }
    if (admobTestIds.isNotEmpty()) {
        val message = "Mini Networks: the release build still uses Google's test ad ids (${admobTestIds.joinToString()}); " +
            "set mininetworks.admob.appId, .interstitialId and .rewardedId (docs/RELEASE.md) or it earns nothing."
        // A build signed for Play must never ship test ads; a local debug-signed release build only warns.
        if (hasUploadKey) throw GradleException(message) else logger.warn(message)
    }
    if (gamesIdsArePlaceholders) {
        // Play Games is optional (docs/TOP100.md A8): the build works and the game runs without it, but say so.
        logger.warn("Mini Networks: res/values/games-ids.xml still holds TODO_ placeholders (docs/RELEASE.md 11); " +
            "this build runs without Play Games (no leaderboards, achievement sync or cloud save).")
    }
}
