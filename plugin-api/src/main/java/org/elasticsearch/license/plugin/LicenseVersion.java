/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.license.core.License;

import java.io.IOException;
import java.io.Serializable;

@SuppressWarnings("deprecation")
public class LicenseVersion implements Serializable {

    // The logic for ID is: XXYYZZAA, where XX is major version, YY is minor version, ZZ is revision, and AA is Beta/RC indicator
    // AA values below 50 are beta builds, and below 99 are RC builds, with 99 indicating a release
    // the (internal) format of the id is there so we can easily do after/before checks on the id

    public static final int V_1_0_0_ID = /*00*/1000099;
    public static final int V_2_0_0_ID = /*00*/2000099;
    public static final LicenseVersion V_1_0_0 = new LicenseVersion(V_1_0_0_ID, false, License.VERSION_START, Version.V_1_4_0_Beta1);
    public static final LicenseVersion V_2_0_0 = new LicenseVersion(V_2_0_0_ID, true, License.VERSION_START, Version.V_2_0_0);

    public static final LicenseVersion CURRENT = V_2_0_0;

    public static LicenseVersion readVersion(StreamInput in) throws IOException {
        return fromId(in.readVInt());
    }

    public static LicenseVersion fromId(int id) {
        switch (id) {
            case V_1_0_0_ID:
                return V_1_0_0;

            default:
                return new LicenseVersion(id, null, License.VERSION_CURRENT, Version.CURRENT);
        }
    }

    public static void writeVersion(LicenseVersion version, StreamOutput out) throws IOException {
        out.writeVInt(version.id);
    }

    /**
     * Returns the smallest version between the 2.
     */
    public static LicenseVersion smallest(LicenseVersion version1, LicenseVersion version2) {
        return version1.id < version2.id ? version1 : version2;
    }

    /**
     * Returns the version given its string representation, current version if the argument is null or empty
     */
    public static LicenseVersion fromString(String version) {
        if (!Strings.hasLength(version)) {
            return LicenseVersion.CURRENT;
        }

        String[] parts = version.split("\\.|\\-");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("the version needs to contain major, minor and revision, and optionally the build");
        }

        try {
            //we reverse the version id calculation based on some assumption as we can't reliably reverse the modulo
            int major = Integer.parseInt(parts[0]) * 1000000;
            int minor = Integer.parseInt(parts[1]) * 10000;
            int revision = Integer.parseInt(parts[2]) * 100;

            int build = 99;
            if (parts.length == 4) {
                String buildStr = parts[3];
                if (buildStr.startsWith("beta")) {
                    build = Integer.parseInt(buildStr.substring(4));
                }
                if (buildStr.startsWith("rc")) {
                    build = Integer.parseInt(buildStr.substring(2)) + 50;
                }
            }

            return fromId(major + minor + revision + build);

        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("unable to parse version " + version, e);
        }
    }

    public final int id;
    public final byte major;
    public final byte minor;
    public final byte revision;
    public final byte build;
    public final Boolean snapshot;
    public final int minSignatureVersion;
    public final Version minEsCompatibilityVersion;

    LicenseVersion(int id, @Nullable Boolean snapshot, int minSignatureVersion, Version minEsCompatibilityVersion) {
        this.id = id;
        this.major = (byte) ((id / 1000000) % 100);
        this.minor = (byte) ((id / 10000) % 100);
        this.revision = (byte) ((id / 100) % 100);
        this.build = (byte) (id % 100);
        this.snapshot = snapshot;
        this.minSignatureVersion = minSignatureVersion;
        this.minEsCompatibilityVersion = minEsCompatibilityVersion;
    }

    public boolean snapshot() {
        return snapshot != null && snapshot;
    }

    public boolean after(LicenseVersion version) {
        return version.id < id;
    }

    public boolean before(LicenseVersion version) {
        return version.id > id;
    }

    /**
     * Returns the minimum compatible version based on the current
     * version. Ie a node needs to have at least the return version in order
     * to communicate with a node running the current version. The returned version
     * is in most of the cases the smallest major version release unless the current version
     * is a beta or RC release then the version itself is returned.
     */
    public LicenseVersion minimumCompatibilityVersion() {
        return LicenseVersion.smallest(this, fromId(major * 1000000 + 99));
    }

    /**
     * @return  The minimum elasticsearch version this license version is compatible with.
     */
    public Version minimumEsCompatiblityVersion() {
        return minEsCompatibilityVersion;
    }

    /**
     * @return The minimum license signature version this license plugin is compatible with.
     */
    public int minimumSignatureVersion() {
        return minSignatureVersion;
    }

    /**
     * Just the version number (without -SNAPSHOT if snapshot).
     */
    public String number() {
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(revision);
        if (build < 50) {
            sb.append("-beta").append(build);
        } else if (build < 99) {
            sb.append("-rc").append(build - 50);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(number());
        if (snapshot()) {
            sb.append("-SNAPSHOT");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LicenseVersion that = (LicenseVersion) o;

        if (id != that.id) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
