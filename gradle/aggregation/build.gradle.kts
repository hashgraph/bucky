// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.version")
    id("org.hiero.gradle.report.code-coverage")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-kotlin")
    id("org.hiero.gradle.feature.publish-maven-central-aggregation")
}

dependencies { published(project(":bucky-client")) }
