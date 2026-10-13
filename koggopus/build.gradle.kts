import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.adrcotfas"
version = providers.gradleProperty("VERSION_NAME").get()

val androidMinSdk = libs.versions.android.minSdk.get().toInt()

kotlin {
    explicitApi()

    android {
        namespace = "io.github.adrcotfas.koggopus"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = androidMinSdk

        // Both run the common tests as well as their own: on the build machine's JVM
        // against a host build of the native library, and on a device against the real one.
        withHostTestBuilder { sourceSetTreeName = "test" }
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        named("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        named("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Releases are signed in CI; local and snapshot builds work without a key.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // The version is the VERSION_NAME Gradle property, which the plugin reads itself.
    coordinates(group.toString(), "koggopus")

    pom {
        name = "koggopus"
        description = "Streaming Ogg Opus encoder and decoder for Kotlin Multiplatform (Android and iOS)."
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

// ---- Native code ------------------------------------------------------------------------
//
// native/CMakeLists.txt builds the C core and the bundled libraries. It is run from here
// once per platform: a JNI library for each Android ABI, static libraries for each iOS
// target, and a JNI library for the build machine so the host tests can run.

/** Configures and builds one CMake target, then copies what it produced to [outputDir]. */
abstract class CMakeBuild @Inject constructor(
    private val exec: ExecOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val cmake: Property<String>

    @get:Input
    abstract val arguments: ListProperty<String>

    @get:Input
    abstract val target: Property<String>

    /** Files to keep, relative to the CMake build directory. */
    @get:Input
    abstract val products: ListProperty<String>

    /** Subdirectory of [outputDir] to put them in; empty for none. */
    @get:Input
    abstract val subdirectory: Property<String>

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val work = workDir.get().asFile
        exec.exec {
            commandLine(listOf(cmake.get(), "-S", sourceDir.get().asFile.path, "-B", work.path) + arguments.get())
        }
        exec.exec {
            commandLine(cmake.get(), "--build", work.path, "--target", target.get(), "--parallel")
        }
        files.sync {
            from(products.get().map { work.resolve(it) })
            into(outputDir.get().asFile.resolve(subdirectory.get()))
        }
    }
}

val nativeDir = rootProject.layout.projectDirectory.dir("native")
val cmakeVersion = libs.versions.cmake.get()
val ndkVersion = libs.versions.ndk.get()
val sdkDir = extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>().sdkComponents.sdkDirectory

// The CMake that ships with the Android SDK when it is installed, otherwise the one on the PATH.
val cmakeExecutable = sdkDir.map { sdk ->
    sdk.file("cmake/$cmakeVersion/bin/cmake").asFile.takeIf { it.isFile }?.path ?: "cmake"
}

fun registerNativeBuild(name: String, configure: CMakeBuild.() -> Unit) = tasks.register<CMakeBuild>(name) {
    group = "native"
    sourceDir = nativeDir
    sources.from(nativeDir.file("CMakeLists.txt"), nativeDir.dir("core"), nativeDir.dir("jni"), nativeDir.dir("third_party"))
    cmake = cmakeExecutable
    workDir = layout.buildDirectory.dir("native/$name/cmake")
    subdirectory.convention("")
    configure()
}

// Host: used only by the tests that run on the build machine.
val buildHostNative = registerNativeBuild("buildHostNative") {
    description = "Builds the JNI library for the build machine."
    arguments = listOf("-DCMAKE_BUILD_TYPE=Release", "-DJAVA_HOME=${System.getProperty("java.home")}")
    target = "koggopus"
    products = listOf(System.mapLibraryName("koggopus"))
    outputDir = layout.buildDirectory.dir("native/buildHostNative/out")
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostNative)
    systemProperty("java.library.path", buildHostNative.get().outputDir.get().asFile.path)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Android: the Kotlin Multiplatform Android plugin has no native build of its own, so the
// libraries are built here with the NDK's toolchain and handed to it as JNI libraries.
val androidNativeBuilds = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").map { abi ->
    registerNativeBuild("buildAndroidNative-$abi") {
        description = "Builds the JNI library for Android $abi."
        val toolchain = sdkDir.map { it.file("ndk/$ndkVersion/build/cmake/android.toolchain.cmake").asFile }
        arguments = toolchain.map {
            listOf(
                "-DCMAKE_TOOLCHAIN_FILE=${it.path}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-$androidMinSdk",
                "-DCMAKE_BUILD_TYPE=Release",
            )
        }
        target = "koggopus"
        products = listOf("libkoggopus.so")
        subdirectory = abi
        doFirst {
            check(toolchain.get().isFile) {
                "Android NDK $ndkVersion is not installed. Install it with: sdkmanager \"ndk;$ndkVersion\""
            }
        }
    }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        androidNativeBuilds.forEach { variant.sources.jniLibs?.addGeneratedSourceDirectory(it, CMakeBuild::outputDir) }
    }
}

// iOS: static libraries, embedded into the Kotlin library by cinterop.
if (HostManager.hostIsMac) {
    kotlin.targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val simulator = konanTarget != KonanTarget.IOS_ARM64
        val build = registerNativeBuild("buildIosNative-$targetName") {
            description = "Builds the static libraries for $targetName."
            arguments = listOf(
                "-DCMAKE_SYSTEM_NAME=iOS",
                "-DCMAKE_OSX_SYSROOT=${if (simulator) "iphonesimulator" else "iphoneos"}",
                "-DCMAKE_OSX_ARCHITECTURES=${if (konanTarget == KonanTarget.IOS_X64) "x86_64" else "arm64"}",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=14.0",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DKOGG_BUILD_JNI=OFF",
            )
            target = "koggcore"
            products = listOf("libkoggcore.a", "libopusenc.a", "opus/libopus.a", "ogg/libogg.a")
            outputDir = layout.buildDirectory.dir("native/buildIosNative-$targetName/out")
        }
        compilations.getByName("main").cinterops.create("koggopus") {
            includeDirs(nativeDir.dir("core"))
            extraOpts("-libraryPath", build.get().outputDir.get().asFile.path)
            tasks.named(interopProcessingTaskName) { dependsOn(build) }
        }
    }
}
