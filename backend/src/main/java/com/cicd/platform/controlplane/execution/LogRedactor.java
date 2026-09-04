package com.cicd.platform.controlplane.execution;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogRedactor {

    private static final Pattern REDACT_PATTERN = Pattern.compile(
            "(?i)(password|passwd|token|secret|authorization|api[_-]?key)(\\s*[:=]\\s*)([^\\s,\"'&]+)", Pattern.CASE_INSENSITIVE);

    private LogRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = REDACT_PATTERN.matcher(value);
        return matcher.replaceAll("$1$2***");
    }
}