plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kapt)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

/**
 * Versioning schema using 30-bit version code (7-7-7-9 bits for major-minor-patch-qualifier).
 * 
 * | Component   | Bits | Range    | Shift Position | Description                           |
 * |-------------|------|----------|----------------|---------------------------------------|
 * | Major       | 7    | 0 – 127  | << 23          | Up to v127                            |
 * | Minor       | 7    | 0 – 127  | << 16          | Up to v127                            |
 * | Patch       | 7    | 0 – 127  | << 9           | Up to v127                            |
 * | Qualifier   | 9    | 0 – 511  | << 0           | Reserved for stability/test status    |
 * 
 * - For stable releases (exactly on a v*.*.* tag): qualifier = 511 (max)
 * - For dev/beta builds: qualifier = commits since last tag (must be < 511)
 * - This ensures: beta.X < stable < next-version.beta.1
 */
object Versioning {
    private const val MAX_MAJOR = 127
    private const val MAX_MINOR = 127
    private const val MAX_PATCH = 127
    private const val MAX_QUALIFIER = 511
    private const val STABLE_QUALIFIER = MAX_QUALIFIER
    
    data class VersionInfo(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val isStable: Boolean,
        val commitsSinceTag: Int,
        val versionName: String,
        val versionCode: Int
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
        
        // Calculate qualifier
        val qualifier = if (isStable) {
            STABLE_QUALIFIER
        } else {
            // Ensure commits since tag doesn't exceed max qualifier
            if (commitsSinceTag >= MAX_QUALIFIER) {
                throw GradleException(
                    "Commits since tag ($commitsSinceTag) exceeds max qualifier ($MAX_QUALIFIER). " +
                    "Please create a new version tag."
                )
            }
            commitsSinceTag
        }
        
        // Calculate version code
        val versionCode = calculateVersionCode(major, minor, patch, qualifier)
        
        // Generate version name
        val versionName = if (isStable) {
            "$major.$minor.$patch"
        } else {
            // Get short commit hash for dev builds
            val shortHash = exec("git", "rev-parse", "--short", "HEAD")
            "$major.$minor.$patch-dev.$commitsSinceTag+$shortHash"
        }
        
        return VersionInfo(
            major = major,
            minor = minor,
            patch = patch,
            isStable = isStable,
            commitsSinceTag = commitsSinceTag,
            versionName = versionName,
            versionCode = versionCode
        )
    }
}

// Resolve version info from git
val versionInfo = Versioning.resolveFromGit(rootProject.projectDir)

// Log version info during configuration
logger.lifecycle("Version Info: name=${versionInfo.versionName}, code=${versionInfo.versionCode}, stable=${versionInfo.isStable}")

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
            // Production variant uses default applicationId
        }
        create("staging") {
            dimension = "channel"
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
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
