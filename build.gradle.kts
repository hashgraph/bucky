// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Bucky S3 Library"

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.assertj.core")
    requires("org.mockito")
    requires("org.testcontainers")
    requires("io.minio")
    requires("junit")
}
