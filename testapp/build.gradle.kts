// Test harness: mimics a YouTube Shorts player and video page with the same Android views
// (RecyclerView pager, ViewPager2) and view ids, so the guard can be tested without YouTube.
// Debug builds of the guard watch this package. Not part of the shipped app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// `-PaltBuildDir=build-alt` builds into another folder, for when an IDE's language server
// holds a lock on files in build/ (happens on Windows with VS Code's Kotlin extension).
providers.gradleProperty("altBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.socialmediaguide.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.socialmediaguide.testapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
