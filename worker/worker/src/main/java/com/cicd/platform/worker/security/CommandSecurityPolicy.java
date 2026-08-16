package com.cicd.platform.worker.security;

import com.cicd.platform.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * First line of defence against hostile pipeline YAML. The sandbox is the
 * real isolation boundary; this policy rejects obviously destructive or
 * exfiltration-oriented commands and blocks environment variables that could
 * leak credentials to the workload.
 *
 * <p>Modes: {@code STRICT} (default), {@code LENIENT} (only catastrophic
 * commands), {@code DISABLED} (no command checks — not recommended).</p>
 */
@Component
public class CommandSecurityPolicy {

    private static final Set<String> BLOCKED_ENV_KEY_PATTERNS = Set.of(
            "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AWS_ACCESS_KEY",
            "AWS_SECRET", "PRIVATE_KEY", "CLIENT_SECRET", "API_KEY", "GITHUB_TOKEN",
            "GITLAB_TOKEN", "AZURE_CLIENT_SECRET", "AUTHORIZATION");

    private static final Pattern CATASTROPHIC = Pattern.compile(
            "(?i)((^|[;|&])\\s*rm\\s+(-\\S+\\s+)*[~/\\\\]|mkfs\\.|dd\\s+if=.*of=/(dev/)?sd|:\\{\\s*\\|\\s*:&};|"
                    + "chmod\\s+(-\\S+\\s+)*(777|a=rwx|a\\+rwx)\\s+[/~\\\\]|"
                    + "chown\\s+-?R?\\s*(root|[0-9]+)\\s+[/~\\\\]|"
                    + "usermod\\s|useradd\\s|reboot\\s|shutdown\\s|halt\\s|killall\\s|pkill\\s|kill\\s+-9\\s+1\\s)");

    private static final Pattern STRICT_BLOCKED = Pattern.compile(
            "(?i)(\\bcurl\\b[^|&;]*\\|[^|&;]*\\b(sh|bash|zsh)\\b|"
                    + "\\bwget\\b[^|&;]*\\|[^|&;]*\\b(sh|bash|zsh)\\b|"
                    + "(^|[;|&])\\s*\\b(nc|netcat|ncat|socat)\\b[^;|&]*|"
                    + "(^|[;|&])\\s*\\bssh\\b[^;|&]*\\|\\s*(sh|bash)\\b|"
                    + "(^|[;|&])\\s*sudo\\s+|"
                    + "curl\\s+-[A-Za-z]*[A-Za-z][A-Za-z]*[kK]?.*(--output|-o|>)|"
                    + "\\b(openssl|gpg)\\b.*\\b(key|private|secret)\\b)");

    private final String mode;

    public CommandSecurityPolicy(WorkerProperties props) {
        this.mode = props.getCommandPolicy() == null ? "STRICT" : props.getCommandPolicy().toUpperCase();
    }

    public void validateCommand(String command) {
        if ("DISABLED".equals(mode)) {
            return;
        }
        if (command == null || command.isBlank()) {
            throw new SecurityViolationException("Empty command is not allowed");
        }
        if (command.length() > 4096) {
            throw new SecurityViolationException("Command exceeds 4096 characters");
        }
        if (containsControlChars(command)) {
            throw new SecurityViolationException("Command contains control characters");
        }
        if (CATASTROPHIC.matcher(command).find()) {
            throw new SecurityViolationException("Command blocked by security policy");
        }
        if ("STRICT".equals(mode) && STRICT_BLOCKED.matcher(command).find()) {
            throw new SecurityViolationException("Command blocked by strict security policy");
        }
    }

    /**
     * Validates environment variables coming from the untrusted pipeline.
     * Pipeline-defined variables are allowed only if they are not obvious
     * credential names; the backend-supplied job environment is trusted and
     * validated separately.
     */
    public void validateEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey().toUpperCase();
            for (String blocked : BLOCKED_ENV_KEY_PATTERNS) {
                if (key.contains(blocked)) {
                    throw new SecurityViolationException(
                            "Environment variable '" + entry.getKey() + "' is blocked by security policy");
                }
            }
            if (entry.getValue() != null && containsControlChars(entry.getValue())) {
                throw new SecurityViolationException(
                        "Environment variable '" + entry.getKey() + "' contains control characters");
            }
        }
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
