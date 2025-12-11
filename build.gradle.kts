plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kapt) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
