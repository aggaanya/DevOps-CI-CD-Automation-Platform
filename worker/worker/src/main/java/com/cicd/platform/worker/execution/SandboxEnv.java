package com.cicd.platform.worker.execution;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Last-chance sanitation of environment variables passed to the sandbox:
 * only safe POSIX-style names are forwarded, values are length- and
 * character-bounded. Secrets are never present here because the
 * {@link com.cicd.platform.worker.security.CommandSecurityPolicy} blocks
 * credential-named variables from the untrusted pipeline.
 */
@Component
public class SandboxEnv {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    private static final int MAX_VALUE_LENGTH = 1024;

    public Map<String, String> sanitize(Map<String, String> environment) {
        Map<String, String> result = new LinkedHashMap<>();
        if (environment == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !KEY_PATTERN.matcher(key).matches()) {
                continue;
            }
            if (value == null || value.length() > MAX_VALUE_LENGTH || containsControlChars(value)) {
                continue;
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == 0) {
                return true;
            }
        }
        return false;
    }
}
