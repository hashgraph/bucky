// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Bucky S3 Library"

testModuleInfo {
    requires("io.minio")
    requires("org.junit.jupiter.api")
    requires("org.assertj.core")
    requires("org.testcontainers")
    requires("jdk.httpserver")
}
