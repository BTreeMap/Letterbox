plugins {
    alias(libs.plugins.androidApplication) apply false
}

val forcedDependencies = listOf(
    "com.google.protobuf:protobuf-java:4.33.4",
    "com.google.protobuf:protobuf-kotlin:4.33.4",
    "io.netty:netty-codec:4.2.9.Final",
    "io.netty:netty-codec-http:4.2.9.Final",
    "io.netty:netty-codec-http2:4.2.9.Final",
    "io.netty:netty-common:4.2.9.Final",
    "io.netty:netty-handler:4.2.9.Final",
    "org.bitbucket.b_c:jose4j:0.9.6",
    "org.jdom:jdom2:2.0.6.1",
    "org.apache.commons:commons-lang3:3.20.0",
    "org.apache.httpcomponents:httpclient:4.5.14",
)

buildscript {
    val buildscriptForcedDependencies = listOf(
        "com.google.protobuf:protobuf-java:4.33.4",
        "com.google.protobuf:protobuf-kotlin:4.33.4",
        "io.netty:netty-codec:4.2.9.Final",
        "io.netty:netty-codec-http:4.2.9.Final",
        "io.netty:netty-codec-http2:4.2.9.Final",
        "io.netty:netty-common:4.2.9.Final",
        "io.netty:netty-handler:4.2.9.Final",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.jdom:jdom2:2.0.6.1",
        "org.apache.commons:commons-lang3:3.20.0",
        "org.apache.httpcomponents:httpclient:4.5.14",
    )

    configurations.configureEach {
        resolutionStrategy.force(*buildscriptForcedDependencies.toTypedArray())
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
