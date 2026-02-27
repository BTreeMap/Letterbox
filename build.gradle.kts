plugins {
    alias(libs.plugins.androidApplication) apply false
}

val forcedDependencies = listOf(
    "com.google.protobuf:protobuf-java:4.34.0",
    "com.google.protobuf:protobuf-kotlin:4.34.0",
    "io.netty:netty-codec:4.2.10.Final",
    "io.netty:netty-codec-http:4.2.10.Final",
    "io.netty:netty-codec-http2:4.2.10.Final",
    "io.netty:netty-common:4.2.10.Final",
    "io.netty:netty-handler:4.2.10.Final",
    "org.bitbucket.b_c:jose4j:0.9.6",
    "org.jdom:jdom2:2.0.6.1",
    "org.apache.commons:commons-lang3:3.20.0",
    "org.apache.httpcomponents:httpclient:4.5.14",
)

gradle.beforeProject {
    buildscript.configurations.configureEach {
        resolutionStrategy.force(*forcedDependencies.toTypedArray())
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.force(*forcedDependencies.toTypedArray())
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
