plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.synergic.cloakroom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.synergic.cloakroom"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // UAT server (API_GUIDE.md §0), HTTPS.
            buildConfigField("String", "API_BASE_URL", "\"https://cloakroom.synergicdatabank.com\"")
        }
        release {
            // Set the production URL here before the first release build.
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.invalid\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // java.time on Android 7 (API 24/25) needs core library desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Exported Room schemas, read by MigrationTestHelper in the migration tests. Robolectric
        // only sees the app's own assets, so they go into debug builds (a few KB; not in release).
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

room {
    // Exported schema JSON files, one per database version, used for migrations.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)

    // GC039 POS SDK: printer power and port (hdx.HdxUtil). Its native part, libhdxutil.so, is
    // part of the POS firmware; libserial_port.so (src/main/jniLibs) opens the printer port.
    implementation(files("libs/HdxUtil.jar"))

    // Live API (API_GUIDE.md): HTTP client, JSON, tokens in encrypted storage.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.room.testing)
}
