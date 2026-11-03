plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

import java.util.Properties

// The release signing key, never the debug key (owner, 2026-11-03 - as OrbixPOS). Kept outside
// git: android/key.properties (git-ignored; dist/build-hq.ps1 copies it from dist/android-signing)
// names the keystore and its passwords. Every update of a brand's app must be signed with this
// same key, or phones refuse it as an update.
val keyProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keyProperties.getProperty("storeFile") != null

android {
    namespace = "net.otapp.orbix.hq"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // OrbixHQ application ID — owned by OTAPP (net.otapp.orbix.hq).
        applicationId = "net.otapp.orbix.hq"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // ONE FLAVOR PER BRAND, discovered from mobile_exec/brands/<id>/brand.json
    // — adding a customer is `python tool/brand.py new ...`, never an edit here.
    // `orbix` is the generic app and keeps net.otapp.orbix.hq; a customer brand
    // is its own app beside it (net.otapp.orbix.hq.<id>), with its own name and
    // icon from brands/<id>/generated/android/res. Build with dist/build-hq.ps1,
    // which also passes the brand's dart-defines (lib/app/brand.dart).
    flavorDimensions += "brand"
    val brandDirs = (file("../../brands").listFiles() ?: emptyArray())
        .filter { File(it, "brand.json").isFile }
        .sortedBy { it.name }
    productFlavors {
        brandDirs.forEach { dir ->
            create(dir.name) {
                dimension = "brand"
                if (dir.name != "orbix") applicationIdSuffix = "." + dir.name
            }
        }
    }
    sourceSets {
        brandDirs.forEach { dir ->
            val res = File(dir, "generated/android/res")
            if (res.isDirectory) getByName(dir.name).res.srcDir(res)
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Never the debug key. Without android/key.properties a release build stops below.
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// A release APK without the release key would be unsigned or debug-signed: refuse to build it.
gradle.taskGraph.whenReady {
    val release = allTasks.any { it.name.contains("Release") && it.project == project }
    if (release && !hasReleaseKey) {
        throw GradleException(
            "No release signing key: create android/key.properties (see dist/android-signing). " +
                "Release builds are never signed with the debug key.")
    }
}

flutter {
    source = "../.."
}
