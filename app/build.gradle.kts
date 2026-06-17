plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")          // KSP = Kotlin Symbol Processing, used by Room to generate DB code
}

android {
    namespace  = "com.irin.flowlog"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.irin.flowlog"
        minSdk          = 28     // Android 8.1 — getScrollDeltaX/Y require API 28
        targetSdk       = 35
        versionCode     = 14
        versionName     = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Tell Room to export the schema to a JSON file in the repo so migrations are auditable.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }

    // Required to run Room tests on the JVM without an emulator (via Robolectric).
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues     = true
    }
}

dependencies {
    // ── Jetpack Compose ─────────────────────────────────────────────────────────
    // BOM (Bill of Materials) pins all Compose library versions to a compatible set
    // so you don't have to specify versions for every compose-* artifact individually.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── Core AndroidX ────────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ── Room (SQLite) ────────────────────────────────────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")       // adds suspend/Flow support to DAOs
    ksp("androidx.room:room-compiler:$roomVersion")             // generates the actual SQL code at build time

    // ── JSON ─────────────────────────────────────────────────────────────────────
    // Gson is straightforward and well-documented — no compiler plugin required.
    implementation("com.google.code.gson:gson:2.11.0")

    // ── Coroutines ───────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── Testing ──────────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Mockito lets unit tests stub Android classes (e.g. AppDatabase) without Robolectric.
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // Robolectric lets unit tests (in src/test/) use Android APIs without a device or emulator.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation(composeBom)
}
