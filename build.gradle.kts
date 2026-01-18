plugins {
    alias(libs.plugins.androidApplication) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
