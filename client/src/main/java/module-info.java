// SPDX-License-Identifier: Apache-2.0
module com.hedera.bucky {
    exports com.hedera.bucky;
    exports com.hedera.bucky.utils;

    requires java.base;
    requires java.net.http;
    requires java.xml;
    requires static transitive com.github.spotbugs.annotations;
}
