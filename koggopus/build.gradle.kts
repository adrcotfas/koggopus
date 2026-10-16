import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.adrcotfas"
version = providers.gradleProperty("VERSION_NAME").get()

val androidMinSdk = libs.versions.android.minSdk.get().toInt()

// Native libraries built elsewhere, by native/build-linux.sh: <dir>/jvm is laid out like
// the jvm jar, and <dir>/static/<target> holds a Kotlin/Native Linux target's static libraries.
val prebuiltNatives = providers.gradleProperty("koggopus.prebuiltNatives").orNull?.let { rootProject.file(it) }
val hostArch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"

/** Prebuilt static libraries for a Kotlin/Native Linux target, if there are any. */
fun prebuiltStatic(target: String) = prebuiltNatives?.resolve("static/$target")?.takeIf { it.isDirectory }

// A Linux target needs its static libraries, which only a Linux machine of that architecture
// can build. Elsewhere it is left out, so the other targets still build; publishing to Maven
// Central checks that it is there.
val linuxTargets = mapOf("linuxX64" to "x64", "linuxArm64" to "arm64").filter { (target, arch) ->
    (prebuiltStatic(target) != null || (HostManager.hostIsLinux && arch == hostArch)).also {
        if (!it) logger.warn("koggopus: leaving out $target, which needs -Pkoggopus.prebuiltNatives (see native/build-linux.sh)")
    }
}

kotlin {
    explicitApi()

    android {
        namespace = "io.github.adrcotfas.koggopus"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = androidMinSdk

        // Runs the common tests on a device as well as its own. The jvm target runs them on
        // the build machine.
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

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    if ("linuxX64" in linuxTargets) linuxX64()
    if ("linuxArm64" in linuxTargets) linuxArm64()

    // jniMain: the JNI binding, shared by Android and the desktop JVM.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withCompilations { it.platformType == KotlinPlatformType.jvm || it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
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
        description = "Streaming Ogg Opus encoder and decoder for Kotlin Multiplatform (Android, iOS and desktop JVM)."
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
// once per platform: a JNI library for each Android ABI, static libraries for each
// Kotlin/Native target, and a JNI library for each desktop OS and architecture.

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

// Desktop JVM: a JNI library per OS and architecture, in the jar under
// io/github/adrcotfas/koggopus/native/<platform>/, where Native.jni.kt looks for it. A Mac
// builds both macOS libraries and a Linux machine its own. The rest come from
// -Pkoggopus.prebuiltNatives. A prebuilt library replaces the local build, so CI tests
// exactly what it publishes.
val jvmPlatforms = listOf("darwin-arm64", "darwin-x64", "linux-x64", "linux-arm64")
val jvmNativePath = "io/github/adrcotfas/koggopus/native"
val prebuiltJvm = prebuiltNatives?.resolve("jvm")
val localJvmBuilds = when {
    HostManager.hostIsMac -> mapOf(
        "darwin-arm64" to listOf("-DCMAKE_SYSTEM_NAME=Darwin", "-DCMAKE_SYSTEM_PROCESSOR=arm64", "-DCMAKE_OSX_ARCHITECTURES=arm64"),
        "darwin-x64" to listOf("-DCMAKE_SYSTEM_NAME=Darwin", "-DCMAKE_SYSTEM_PROCESSOR=x86_64", "-DCMAKE_OSX_ARCHITECTURES=x86_64"),
    )
    HostManager.hostIsLinux -> mapOf("linux-$hostArch" to emptyList())
    else -> emptyMap()
}.filterKeys { prebuiltJvm?.resolve("$jvmNativePath/$it")?.isDirectory != true }

val jvmNativeBuilds = localJvmBuilds.map { (platform, platformArguments) ->
    registerNativeBuild("buildJvmNative-$platform") {
        description = "Builds the JNI library for the desktop JVM on $platform."
        arguments = platformArguments + listOf(
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0",
            "-DJAVA_HOME=${System.getProperty("java.home")}",
        )
        target = "koggopus"
        products = listOf(System.mapLibraryName("koggopus"))
        subdirectory = "$jvmNativePath/$platform"
        outputDir = layout.buildDirectory.dir("native/buildJvmNative-$platform/out")
    }
}

kotlin.sourceSets.named("jvmMain") {
    jvmNativeBuilds.forEach { resources.srcDir(it.flatMap(CMakeBuild::outputDir)) }
    prebuiltJvm?.takeIf { it.isDirectory }?.let { resources.srcDir(it) }
}

tasks.named<Jar>("jvmJar") {
    // The keep rules matter to desktop apps shrunk with R8 as much as to Android ones.
    from("consumer-rules.pro") {
        into("META-INF/proguard")
        rename { "koggopus.pro" }
    }
}

// A release must work everywhere it claims to, so Maven Central gets one only when the jvm
// jar holds every desktop platform's library and no Linux target was left out.
val checkNatives = tasks.register("checkNatives") {
    description = "Fails unless every platform's native library is part of the build."
    val jar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }
    val expected = jvmPlatforms.map { "$jvmNativePath/$it/" }
    val missingTargets = listOf("linuxX64", "linuxArm64") - linuxTargets.keys
    inputs.file(jar)
    doLast {
        check(missingTargets.isEmpty()) { "These Kotlin/Native targets were left out: $missingTargets" }
        val entries = ZipFile(jar.get().asFile).use { zip -> zip.entries().toList().map { it.name } }
        val missing = expected.filter { dir -> entries.none { it.startsWith(dir) && !it.endsWith("/") } }
        check(missing.isEmpty()) { "The jvm jar has no native library in: $missing" }
    }
}
tasks.matching { it.name.endsWith("ToMavenCentralRepository") }.configureEach {
    dependsOn(checkNatives)
}

tasks.withType<Test>().configureEach {
    // The tests load the library from the jvm resources, the same way an app does.
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

// Kotlin/Native: static libraries, embedded into the Kotlin library by cinterop. Apple
// targets are built on a Mac, Linux ones on Linux unless they are prebuilt.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val targetName = name
    val isLinux = konanTarget.family == Family.LINUX
    if (!isLinux && !HostManager.hostIsMac) return@configureEach
    val prebuilt = prebuiltStatic(targetName)
    val build = if (prebuilt != null) null else registerNativeBuild("buildNative-$targetName") {
        description = "Builds the static libraries for $targetName."
        val simulator = konanTarget != KonanTarget.IOS_ARM64
        arguments = when {
            isLinux -> emptyList()
            konanTarget == KonanTarget.MACOS_ARM64 -> listOf(
                "-DCMAKE_SYSTEM_NAME=Darwin",
                "-DCMAKE_SYSTEM_PROCESSOR=arm64",
                "-DCMAKE_OSX_ARCHITECTURES=arm64",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0",
            )
            else -> listOf(
                "-DCMAKE_SYSTEM_NAME=iOS",
                "-DCMAKE_OSX_SYSROOT=${if (simulator) "iphonesimulator" else "iphoneos"}",
                "-DCMAKE_OSX_ARCHITECTURES=${if (konanTarget == KonanTarget.IOS_X64) "x86_64" else "arm64"}",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=14.0",
            )
        } + listOf("-DCMAKE_BUILD_TYPE=Release", "-DKOGG_BUILD_JNI=OFF")
        target = "koggcore"
        products = listOf("libkoggcore.a", "libopusenc.a", "opus/libopus.a", "ogg/libogg.a")
        outputDir = layout.buildDirectory.dir("native/buildNative-$targetName/out")
    }
    compilations.getByName("main").cinterops.create("koggopus") {
        includeDirs(nativeDir.dir("core"))
        extraOpts("-libraryPath", prebuilt?.path ?: build!!.get().outputDir.get().asFile.path)
        if (build != null) tasks.named(interopProcessingTaskName) { dependsOn(build) }
    }
}
