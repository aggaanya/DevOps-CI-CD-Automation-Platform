package com.cicd.platform.worker;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a tiny local git repository used as an offline CI/CD test workload.
 */
public final class TestGitRepo {

    private static final PersonIdent AUTHOR = new PersonIdent("cicd-test", "cicd-test@example.com");

    private TestGitRepo() {
    }

    /**
     * Creates a repo containing a minimal Maven project (JUnit 5 test) plus a
     * pipeline.yml, and commits it. Returns the full commit SHA.
     */
    public static String createMavenRepo(Path repoDir, boolean testPasses) throws Exception {
        Files.createDirectories(repoDir.resolve("src/main/java/com/example"));
        Files.createDirectories(repoDir.resolve("src/test/java/com/example"));

        Files.writeString(repoDir.resolve("pom.xml"), mavenPom(), StandardCharsets.UTF_8);
        Files.writeString(repoDir.resolve("src/main/java/com/example/App.java"),
                "package com.example;\npublic class App { public static String greeting() { return \"hi\"; } }\n",
                StandardCharsets.UTF_8);
        Files.writeString(repoDir.resolve("src/test/java/com/example/AppTest.java"),
                testPasses ? passingTest() : failingTest(), StandardCharsets.UTF_8);
        Files.writeString(repoDir.resolve("pipeline.yml"), mavenPipeline(), StandardCharsets.UTF_8);

        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            Repository repository = git.getRepository();
            org.eclipse.jgit.api.CommitCommand commit = git.commit()
                    .setAuthor(AUTHOR)
                    .setCommitter(AUTHOR)
                    .setMessage("fixture commit: tests " + (testPasses ? "pass" : "fail"));
            commit.setAll(true).call();
            return repository.resolve("HEAD").name();
        }
    }

    /**
     * Creates a repo whose pipeline runs a single (long-running) command.
     */
    public static String createShellRepo(Path repoDir, String command) throws Exception {
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("pipeline.yml"),
                "pipeline:\n"
                        + "  name: fixture-shell\n"
                        + "  stages:\n"
                        + "    - name: s1\n"
                        + "      jobs:\n"
                        + "        - name: long-job\n"
                        + "          steps:\n"
                        + "            - run: " + command + "\n",
                StandardCharsets.UTF_8);

        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setAuthor(AUTHOR).setCommitter(AUTHOR)
                    .setMessage("fixture shell repo")
                    .setAll(true).call();
            return git.getRepository().resolve("HEAD").name();
        }
    }

    public static String mavenPipeline() {
        return """
                pipeline:
                  name: fixture-app
                  stages:
                    - name: build-test
                      jobs:
                        - name: maven-build
                          steps:
                            - run: mvn -B clean package
                """;
    }

    private static String passingTest() {
        return """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class AppTest {
                  @Test void greeting() { assertEquals("hi", App.greeting()); }
                }
                """;
    }

    private static String failingTest() {
        return """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class AppTest {
                  @Test void greeting() { assertEquals("nope", App.greeting()); }
                }
                """;
    }

    private static String mavenPom() {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>fixture-app</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.2.5</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
    }
}
