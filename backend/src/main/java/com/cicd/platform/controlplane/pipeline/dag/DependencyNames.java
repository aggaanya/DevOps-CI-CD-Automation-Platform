package com.cicd.platform.controlplane.pipeline.dag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Canonical encoding for dependency name lists.
 *
 * <p>Dependencies are declared in pipeline YAML as free-form identifiers whose
 * casing is not significant. They are persisted on {@code pipeline_stages} and
 * {@code pipeline_jobs} as a single comma-separated, lower-cased column so the
 * resolved DAG survives as an immutable property of a run.
 *
 * <p>Names are normalised to lower case because stage and job references are
 * matched case-insensitively throughout the scheduler and the validators.
 */
public final class DependencyNames {

    private static final char SEPARATOR = ',';

    private DependencyNames() {}

    /** Normalises a single dependency reference; {@code null}/blank yields {@code null}. */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Encodes a collection of dependency references into the persisted form.
     * Returns {@code null} for an empty/null collection so the column stays
     * {@code NULL} (meaning "no declared dependencies") rather than an empty
     * string.
     */
    public static String encode(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            String normalized = normalize(name);
            if (normalized == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(normalized);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Decodes the persisted form back into a list of lower-cased names. */
    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : encoded.split(String.valueOf(SEPARATOR))) {
            String normalized = normalize(part);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
