plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kapt)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

android {
    namespace = "org.joefang.letterbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.joefang.letterbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
