plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// `-PaltBuildDir=build-alt` builds into another folder, for when an IDE's language server
// holds a lock on files in build/ (happens on Windows with VS Code's Kotlin extension).
providers.gradleProperty("altBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.socialmediaguide"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.socialmediaguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.9.1"
    }

    buildTypes {
        debug {
            // The test harness in testapp/ mimics YouTube; debug builds guard it too.
            buildConfigField("String", "TEST_PACKAGE", "\"com.socialmediaguide.testapp\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "TEST_PACKAGE", "\"\"")
        }
    }
    compileOptions {
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
