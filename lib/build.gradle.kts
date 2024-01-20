plugins {
    // Apply the java-library plugin for API and implementation separation.
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // This dependency is exported to consumers, that is to say found on their compile classpath.
    // api(libs.commons.math3)

    // This dependency is used internally, and not exposed to consumers on their own compile classpath.
    // implementation(libs.guava)

    jmh("com.github.luben:zstd-jni:1.5.5-11")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(9))
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
    maxHeapSize = "8192m"
}

jmh {
    warmupForks = 0
    fork = 1
    iterations = 3
    forceGC = true
    profilers.addAll("mempool", "stack")
    jmhVersion = "1.37"
}
