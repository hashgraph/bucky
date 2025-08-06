// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.build") version "0.4.10" }

rootProject.name = "bucky"

javaModules {
    directory(".") {
        group = "com.hedera.bucky"
        module("client") { artifact = "bucky-client" }
    }
}
