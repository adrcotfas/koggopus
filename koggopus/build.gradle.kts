import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.adrcotfas"
version = providers.gradleProperty("VERSION_NAME").get()

val nativeDir = rootProject.layout.projectDirectory.dir("native")
val cmakeVersion = libs.versions.cmake.get()

android {
    namespace = "io.github.adrcotfas.koggopus"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = nativeDir.file("CMakeLists.txt").asFile
            version = cmakeVersion
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// Unit tests run on the build machine's JVM, so they need the native library built for the
// host as well. This uses the CMake that ships with the Android SDK when it is installed.
val hostNativeDir = layout.buildDirectory.dir("hostNative")
val cmakeExecutable = androidComponents.sdkComponents.sdkDirectory.map { sdk ->
    sdk.file("cmake/$cmakeVersion/bin/cmake").asFile.takeIf { it.isFile }?.absolutePath ?: "cmake"
}

val configureHostNative = tasks.register<Exec>("configureHostNative") {
    inputs.file(nativeDir.file("CMakeLists.txt"))
    outputs.file(hostNativeDir.map { it.file("CMakeCache.txt") })
    commandLine(
        cmakeExecutable.get(),
        "-S", nativeDir.asFile.absolutePath,
        "-B", hostNativeDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJAVA_HOME=${System.getProperty("java.home")}",
    )
}

val buildHostNative = tasks.register<Exec>("buildHostNative") {
    dependsOn(configureHostNative)
    inputs.dir(nativeDir.dir("core"))
    inputs.dir(nativeDir.dir("jni"))
    inputs.dir(nativeDir.dir("third_party"))
    inputs.file(nativeDir.file("CMakeLists.txt"))
    outputs.dir(hostNativeDir)
    commandLine(
        cmakeExecutable.get(),
        "--build", hostNativeDir.get().asFile.absolutePath,
        "--target", "koggopus",
        "--parallel",
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostNative)
    systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

mavenPublishing {
    publishToMavenCentral()
    // Releases are signed in CI; local and snapshot builds work without a key.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "koggopus", version.toString())

    pom {
        name = "koggopus"
        description = "Streaming Ogg Opus encoder and decoder for Kotlin on Android."
        inceptionYear = "2026"
        url = "https://github.com/adrcotfas/koggopus"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "adrcotfas"
                name = "Adrian Cotfas"
                url = "https://github.com/adrcotfas"
            }
        }
        scm {
            url = "https://github.com/adrcotfas/koggopus"
            connection = "scm:git:https://github.com/adrcotfas/koggopus.git"
            developerConnection = "scm:git:ssh://git@github.com/adrcotfas/koggopus.git"
        }
    }
}
