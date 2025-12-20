plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kapt)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

/**
 * Versioning schema using 30-bit version code (7-7-7-9 bits for major-minor-patch-qualifier).
 * 
 * This schema ensures version codes always increase monotonically:
 * - Stable releases use qualifier=511 (max), so v1.2.3 = (1<<23)|(2<<16)|(3<<9)|511
 * - Dev builds increment the patch version and use commits-since-tag as qualifier
 *   so commits after v1.2.3 become v1.2.4-dev.N = (1<<23)|(2<<16)|(4<<9)|N
 * 
 * This guarantees: v1.2.3 < v1.2.4-dev.1 < v1.2.4-dev.2 < ... < v1.2.4 (or v1.3.0, v2.0.0)
 * 
 * | Component   | Bits | Range    | Shift Position | Description                           |
 * |-------------|------|----------|----------------|---------------------------------------|
 * | Major       | 7    | 0 – 127  | << 23          | Up to v127                            |
 * | Minor       | 7    | 0 – 127  | << 16          | Up to v127                            |
 * | Patch       | 7    | 0 – 127  | << 9           | Up to v127                            |
 * | Qualifier   | 9    | 0 – 511  | << 0           | 511 for stable, 0-510 for dev builds  |
 * 
 * See docs/versioning.md for full documentation.
 */
object Versioning {
    private const val MAX_MAJOR = 127
    private const val MAX_MINOR = 127
    private const val MAX_PATCH = 127
    private const val MAX_QUALIFIER = 511
    private const val STABLE_QUALIFIER = MAX_QUALIFIER
    
    /**
     * Build type determined by CI_BUILD_TYPE environment variable.
     * - "release": Official tagged release (vX.Y.Z)
     * - "prerelease": Main branch build (vX.Y.Z-dev.N+hash)
     * - "test": PR/untrusted build (ci-test-untrusted-X.Y.Z-dev.N+hash)
     * - "local": Local development build (same as prerelease format)
     */
    enum class BuildType {
        RELEASE,      // Tagged releases: v1.2.3
        PRERELEASE,   // Main branch: v1.2.3-dev.N+hash
        TEST,         // PRs: ci-test-untrusted-1.2.3-dev.N+hash
        LOCAL         // Local builds: same as prerelease
    }
    
    data class VersionInfo(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val isStable: Boolean,
        val commitsSinceTag: Int,
        val versionName: String,
        val versionCode: Int,
        val buildType: BuildType
    )
    
    /**
     * Calculates the version code using the 30-bit schema.
     */
    private fun calculateVersionCode(major: Int, minor: Int, patch: Int, qualifier: Int): Int {
        require(major in 0..MAX_MAJOR) { "Major version $major exceeds max $MAX_MAJOR" }
        require(minor in 0..MAX_MINOR) { "Minor version $minor exceeds max $MAX_MINOR" }
        require(patch in 0..MAX_PATCH) { "Patch version $patch exceeds max $MAX_PATCH" }
        require(qualifier in 0..MAX_QUALIFIER) { "Qualifier $qualifier exceeds max $MAX_QUALIFIER" }
        
        return (major shl 23) or (minor shl 16) or (patch shl 9) or qualifier
    }
    
    /**
      * Resolves version info from git state using ProcessBuilder.
     * - Finds the latest v*.*.* tag
     * - Determines if HEAD is exactly on the tag (stable) or ahead (dev build)
     * - Uses CI_BUILD_TYPE env var to determine version name format
     * - Calculates versionCode and versionName accordingly
     */
    fun resolveFromGit(projectDir: java.io.File): VersionInfo {
        fun exec(vararg args: String): String {
            return try {
                val process = ProcessBuilder(*args)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText().trim()
            } catch (e: Exception) {
                ""
            }
        }
        
        // Determine build type from CI_BUILD_TYPE env var
        val ciBuildTypeEnv = System.getenv("CI_BUILD_TYPE") ?: "local"
        val buildType = when (ciBuildTypeEnv.lowercase()) {
            "release" -> BuildType.RELEASE
            "prerelease" -> BuildType.PRERELEASE
            "test" -> BuildType.TEST
            else -> BuildType.LOCAL
        }
        
        // Get the latest version tag matching v*.*.*
        val describeOutput = exec("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--abbrev=0")
        
        // Parse the version tag (format: vMAJOR.MINOR.PATCH)
        val versionRegex = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
        val match = versionRegex.matchEntire(describeOutput)
        
        val (major, minor, patch) = if (match != null) {
            Triple(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        } else {
            // Fallback to 0.0.0 if no valid tag found
            Triple(0, 0, 0)
        }
        
        // Count commits since the last tag
        val commitsSinceTag = if (describeOutput.isNotEmpty()) {
            val countOutput = exec("git", "rev-list", "--count", "HEAD", "^$describeOutput")
            countOutput.toIntOrNull() ?: 0
        } else {
            // If no tag exists, count all commits
            val countOutput = exec("git", "rev-list", "--count", "HEAD")
            countOutput.toIntOrNull() ?: 0
        }
        
        // Determine if this is a stable release (exactly on the tag)
        val isStable = commitsSinceTag == 0
        
        // For dev builds, increment patch to ensure version code is higher than last release
        // This way, commits after v1.2.3 become v1.2.4-dev.N (working towards v1.2.4)
        val effectivePatch = if (isStable) patch else patch + 1
        
        // Calculate qualifier
        val qualifier = if (isStable) {
            STABLE_QUALIFIER
        } else {
            // Ensure commits since tag doesn't exceed max qualifier
            if (commitsSinceTag > MAX_QUALIFIER) {
                throw GradleException(
                    "Commits since tag ($commitsSinceTag) exceeds max qualifier ($MAX_QUALIFIER). " +
                    "Please create a new version tag."
                )
            }
            commitsSinceTag
        }
        
        // Validate effective patch doesn't overflow (can happen if base patch is 127)
        if (effectivePatch > MAX_PATCH) {
            throw GradleException(
                "Patch version overflow: base patch is $patch, but dev builds require incrementing to ${patch + 1}. " +
                "Please create a new minor/major version tag."
            )
        }
        
        // Calculate version code using effective patch for dev builds
        val versionCode = calculateVersionCode(major, minor, effectivePatch, qualifier)
        
        // Get short commit hash for dev builds
        val shortHash = exec("git", "rev-parse", "--short", "HEAD")
        
        // Generate version name based on build type
        // Design doc specifies:
        // - Release: v1.2.3
        // - Pre-release: v1.2.3-dev.N+hash
        // - Test (PR): ci-test-untrusted-1.2.3-dev.N+hash (explicitly non-SemVer)
        val versionName = when (buildType) {
            BuildType.RELEASE -> {
                // Official release: vMAJOR.MINOR.PATCH
                "v$major.$minor.$patch"
            }
            BuildType.PRERELEASE, BuildType.LOCAL -> {
                if (isStable) {
                    // Exactly on a tag
                    "v$major.$minor.$patch"
                } else {
                    // Development build: vMAJOR.MINOR.PATCH-dev.N+hash
                    "v$major.$minor.$effectivePatch-dev.$commitsSinceTag+$shortHash"
                }
            }
            BuildType.TEST -> {
                // Untrusted PR build: ci-test-untrusted-MAJOR.MINOR.PATCH-dev.N+hash
                // Explicitly non-SemVer to make it clear this is an untrusted test build
                if (isStable) {
                    "ci-test-untrusted-$major.$minor.$patch+$shortHash"
                } else {
                    "ci-test-untrusted-$major.$minor.$effectivePatch-dev.$commitsSinceTag+$shortHash"
                }
            }
        }
        
        return VersionInfo(
            major = major,
            minor = minor,
            patch = effectivePatch,  // Use effective patch (incremented for dev builds)
            isStable = isStable,
            commitsSinceTag = commitsSinceTag,
            versionName = versionName,
            versionCode = versionCode,
            buildType = buildType
        )
    }
}

// Resolve version info from git
val versionInfo = Versioning.resolveFromGit(rootProject.projectDir)

// Log version info during configuration
logger.lifecycle("Version Info: name=${versionInfo.versionName}, code=${versionInfo.versionCode}, stable=${versionInfo.isStable}, buildType=${versionInfo.buildType}")

android {
    namespace = "org.joefang.letterbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.joefang.letterbox"
        minSdk = 26
        targetSdk = 34
        versionCode = versionInfo.versionCode
        versionName = versionInfo.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            // Production variant uses default applicationId (org.joefang.letterbox)
        }
        create("staging") {
            dimension = "channel"
            // Test variant uses .test suffix for co-installation with prod
            // applicationId: org.joefang.letterbox.test
            applicationIdSuffix = ".test"
            // Note: versionName already includes "ci-test-untrusted-" prefix when CI_BUILD_TYPE=test
            resValue("string", "app_name", "Letterbox (Test)")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.7.3"
    }
    kotlin {
        jvmToolchain(17)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    testOptions {
        managedDevices {
            localDevices {
                create("pixel7Api34") {
                    device = "Pixel 7"
                    apiLevel = 34
                    systemImageSource = "google"
                }
            }
            groups {
                create("ciPhones") {
                    targetDevices.add(devices["pixel7Api34"])
                }
            }
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }
}

val rustBuildEnabled = project.findProperty("rustBuild") == "true"
val cargoNdkOutput = rootProject.layout.projectDirectory.dir("app/src/main/jniLibs").asFile
val hostLibDir = rootProject.layout.projectDirectory.dir("target/release").asFile

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Build Rust shared library for Android via cargo-ndk"
    workingDir = rootProject.projectDir.resolve("rust")
    commandLine(
        "cargo",
        "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", cargoNdkOutput.absolutePath,
        "build",
        "--release"
    )
    onlyIf { rustBuildEnabled }
}

// Build Rust library for host OS (for unit tests)
val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    group = "build"
    description = "Build Rust shared library for host OS (for FFI unit tests)"
    workingDir = rootProject.projectDir.resolve("rust/letterbox-core")
    commandLine("cargo", "build", "--release", "--lib")
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

// Configure test task to use host-compiled library
tasks.withType<Test> {
    dependsOn(cargoHostBuild)
    
    // Set the library path for JNA to find the host-compiled Rust library
    // The file is built by cargoHostBuild task before tests run, so we always set the path
    val libFile = File(hostLibDir, "libletterbox_core.so")
    systemProperty("uniffi.component.letterbox_core.libraryOverride", libFile.absolutePath)
    systemProperty("jna.library.path", hostLibDir.absolutePath)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.jna) { artifact { type = "aar" } }
    kapt(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation("net.java.dev.jna:jna:5.14.0")

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
