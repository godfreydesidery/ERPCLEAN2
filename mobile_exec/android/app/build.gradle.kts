plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

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

    buildTypes {
        release {
            // DEMO BUILD ONLY. A real release keystore (and a signingConfig that reads it
            // from a secret store, never from git) is REQUIRED before any non-demo
            // distribution of OrbixHQ — Play Store, client handover, or QA sideload.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}
