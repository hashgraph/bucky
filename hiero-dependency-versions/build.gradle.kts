// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.jpms-modules")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-kotlin")
}

dependencies {
    runtime("org.apache.logging.log4j:log4j-slf4j2-impl") {
        because("org.apache.logging.log4j.slf4j2.impl")
    }
}

val log4j = "2.25.3"
    val junit5 = "6.0.3"
val testContainers = "1.21.4"

dependencies.constraints {
    api("com.squareup.okio:okio-jvm:3.17.0") { because("okio") }
    api("org.jetbrains:annotations:26.1.0") { because("org.jetbrains.annotations") }

    api("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j") {
        because("org.apache.logging.log4j.slf4j2.impl")
    }

    api("com.github.spotbugs:spotbugs-annotations:4.9.8") {
        because("com.github.spotbugs.annotations")
    }

    api("org.junit.jupiter:junit-jupiter-api:$junit5") { because("org.junit.jupiter.api") }
    api("org.junit.jupiter:junit-jupiter-engine:$junit5") { because("org.junit.jupiter.engine") }

    api("org.assertj:assertj-core:3.27.7") { because("org.assertj.core") }

    api("org.testcontainers:testcontainers:$testContainers") { because("org.testcontainers") }

    api("io.minio:minio:8.6.0") { because("io.minio") }

    api("com.google.guava:guava:33.5.0-jre") { because("com.google.common") }
}
