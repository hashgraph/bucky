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

val log4j = "2.25.0"
val junit5 = "5.10.3"
val mockito = "5.18.0"
val testContainers = "1.21.3"

dependencies.constraints {
    api("com.squareup.okio:okio-jvm:3.16.0") { because("okio") }
    api("org.jetbrains:annotations:26.0.2") { because("org.jetbrains.annotations") }

    api("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j") {
        because("org.apache.logging.log4j.slf4j2.impl")
    }

    api("com.github.spotbugs:spotbugs-annotations:4.9.3") {
        because("com.github.spotbugs.annotations")
    }

    api("org.junit.jupiter:junit-jupiter-api:$junit5") { because("org.junit.jupiter.api") }
    api("org.junit.jupiter:junit-jupiter-engine:$junit5") { because("org.junit.jupiter.engine") }

    api("org.assertj:assertj-core:3.27.3") { because("org.assertj.core") }

    api("org.mockito:mockito-core:$mockito") { because("org.mockito") }
    api("org.mockito:mockito-junit-jupiter:$mockito") { because("org.mockito.junit.jupiter") }

    api("org.testcontainers:testcontainers:$testContainers") { because("org.testcontainers") }
    api("org.testcontainers:junit-jupiter:$testContainers") {
        because("org.testcontainers.junit.jupiter")
    }

    api("io.minio:minio:8.5.17") { because("io.minio") }

    api("com.google.guava:guava:33.4.8-jre") { because("com.google.common") }
}
