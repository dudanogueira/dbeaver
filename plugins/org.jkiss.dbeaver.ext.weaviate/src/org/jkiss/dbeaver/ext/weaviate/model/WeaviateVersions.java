/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.weaviate.model;

import org.jkiss.code.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares a Weaviate server version against a minimum, for feature gating.
 * <p>
 * Deliberately not {@code WeaviateDataSourceInfo#getDatabaseVersion()}. That returns an OSGi
 * {@code Version}, and OSGi wants {@code major.minor.micro.qualifier} -- it rejects the hyphen in
 * {@code 1.30.0-rc.1} and the method falls back to {@code Version.emptyVersion}, i.e. {@code 0.0.0}.
 * Gating on that would hide every gated feature from anyone running a release candidate, and from
 * everyone during the window before the lazy {@code meta()} fetch has happened. It stays as it is
 * for display; gating parses the string itself.
 * <p>
 * <b>Unknown means available.</b> A version that is absent, unparseable, or the literal
 * {@code "unknown"} answers yes to every check. The alternative -- assuming "too old" -- fails in
 * the direction that hides working features with no explanation, and both are real:
 * <ul>
 *   <li>weaviate-studio's Data Explorer takes the strict route, and shipped a release where a
 *       broken version lookup returned null and the gate "always showed the requires v1.26+
 *       warning even on v1.37+ servers"</li>
 *   <li>its schema editor intends the permissive route but sends the sentinel string
 *       {@code "unknown"}, which is truthy, parses to {@code 0.0.0}, and disables every gated
 *       control -- which is why {@code "unknown"} is checked for by name below</li>
 * </ul>
 * When the guess is wrong the other way, the server answers with something legible: an endpoint
 * that does not exist yet returns {@code 404 path /v1/... was not found}.
 * <p>
 * Note this also means an RC compares equal to its release: {@code 1.37.0-rc.0} counts as having
 * everything {@code 1.37.0} has. That is the intended reading -- an RC is where you look for a
 * feature that just landed.
 */
public final class WeaviateVersions {

    /**
     * Leading {@code v} optional, pre-release and build metadata ignored. The suffix is matched
     * loosely on purpose: {@code 1.37.0-rc.1}, {@code 1.37.0+build.5} and {@code 1.37.0} all read
     * as 1.37.0.
     */
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)");

    /** What {@code WeaviateDataSourceInfo#getDatabaseProductVersion()} reports before connecting. */
    private static final String UNKNOWN = "unknown";

    private WeaviateVersions() {
        // Utility class
    }

    /**
     * Whether {@code serverVersion} is at least {@code major.minor.patch}.
     *
     * @param serverVersion the raw string from {@code meta().version()}, or null when it has not
     *                      been fetched yet
     * @return true when the version meets the minimum <em>or cannot be determined</em>
     */
    public static boolean isAtLeast(@Nullable String serverVersion, int major, int minor, int patch) {
        int[] parsed = parse(serverVersion);
        if (parsed == null) {
            return true;
        }
        if (parsed[0] != major) {
            return parsed[0] > major;
        }
        if (parsed[1] != minor) {
            return parsed[1] > minor;
        }
        return parsed[2] >= patch;
    }

    /**
     * The version as {@code {major, minor, patch}}, or null when it is absent or unrecognisable.
     * <p>
     * Null rather than {@code {0,0,0}} so callers cannot accidentally treat "no idea" as "very
     * old" -- the distinction is the whole point of this class.
     */
    @Nullable
    public static int[] parse(@Nullable String serverVersion) {
        if (serverVersion == null || serverVersion.isBlank()) {
            return null;
        }
        String trimmed = serverVersion.trim();
        if (UNKNOWN.equalsIgnoreCase(trimmed)) {
            return null;
        }
        Matcher m = VERSION.matcher(trimmed);
        if (!m.find()) {
            return null;
        }
        try {
            return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))};
        } catch (NumberFormatException e) {
            // A version with more digits than an int holds. Unrecognisable rather than old.
            return null;
        }
    }

    /**
     * {@code 1.37.0} for {@code (1, 37, 0)} -- the form used in the "Requires Weaviate" message,
     * which weaviate-studio writes with a full three-part version rather than a "1.37+" shorthand.
     */
    public static String format(int major, int minor, int patch) {
        return major + "." + minor + "." + patch;
    }
}
