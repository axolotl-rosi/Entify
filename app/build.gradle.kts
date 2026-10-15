plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val releaseSigningMode = providers.gradleProperty("ENTIFY_SIGNING_MODE")
    .orElse(providers.environmentVariable("ENTIFY_SIGNING_MODE"))
    .orElse("legacy")
    .get()
val productionSigningValues = mapOf(
    "storeFile" to providers.gradleProperty("ENTIFY_RELEASE_STORE_FILE")
        .orElse(providers.environmentVariable("ENTIFY_RELEASE_STORE_FILE")).orNull,
    "storePassword" to providers.gradleProperty("ENTIFY_RELEASE_STORE_PASSWORD")
        .orElse(providers.environmentVariable("ENTIFY_RELEASE_STORE_PASSWORD")).orNull,
    "keyAlias" to providers.gradleProperty("ENTIFY_RELEASE_KEY_ALIAS")
        .orElse(providers.environmentVariable("ENTIFY_RELEASE_KEY_ALIAS")).orNull,
    "keyPassword" to providers.gradleProperty("ENTIFY_RELEASE_KEY_PASSWORD")
        .orElse(providers.environmentVariable("ENTIFY_RELEASE_KEY_PASSWORD")).orNull,
)

if (releaseSigningMode !in setOf("legacy", "production")) {
    throw GradleException("ENTIFY_SIGNING_MODE must be either 'legacy' or 'production'.")
}
if (releaseSigningMode == "production" && productionSigningValues.values.any { it.isNullOrBlank() }) {
    throw GradleException(
        "Production signing requested, but ENTIFY_RELEASE_STORE_FILE, " +
            "ENTIFY_RELEASE_STORE_PASSWORD, ENTIFY_RELEASE_KEY_ALIAS, and " +
            "ENTIFY_RELEASE_KEY_PASSWORD are not all configured."
    )
}

android {
    namespace = "com.example.entify_again"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.entify_again"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningMode == "production") {
            create("production") {
                storeFile = rootProject.file(productionSigningValues.getValue("storeFile")!!)
                storePassword = productionSigningValues.getValue("storePassword")
                keyAlias = productionSigningValues.getValue("keyAlias")
                keyPassword = productionSigningValues.getValue("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName(
                if (releaseSigningMode == "production") "production" else "debug"
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // WebView
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing
    implementation("org.json:json:20231013")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SVG rendering
    implementation("com.caverock:androidsvg:1.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
