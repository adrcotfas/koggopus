import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A minimal app that uses the library with R8 shrinking and obfuscation turned on. Its
// instrumented test proves the library's consumer ProGuard rules are sufficient. Not published.
plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "io.github.adrcotfas.koggopus.check"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.adrcotfas.koggopus.check"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A release-like build (not debuggable, fully optimised) that can still be installed
    // and tested: it is signed with the debug key.
    buildTypes {
        create("minified") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "app-rules.pro")
            testProguardFiles("test-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    testBuildType = "minified"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":koggopus"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
