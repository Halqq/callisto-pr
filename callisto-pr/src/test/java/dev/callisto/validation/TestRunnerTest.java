package dev.callisto.validation;

import dev.callisto.config.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class TestRunnerTest {

    private final TestRunner runner = new TestRunner();

    @Test
    void detectsMaven(@TempDir Path projectDir) throws Exception {
        // REQ-3-01: pom.xml present -> mvn test
        Files.createFile(projectDir.resolve("pom.xml"));
        String cmd = runner.detectTestCommand(projectDir, new TestConfig());
        assertEquals("mvn test", cmd);
    }

    @Test
    void detectsGradle(@TempDir Path projectDir) throws Exception {
        // REQ-3-01: build.gradle present -> ./gradlew test
        Files.createFile(projectDir.resolve("build.gradle"));
        String cmd = runner.detectTestCommand(projectDir, new TestConfig());
        assertEquals("./gradlew test", cmd);
    }

    @Test
    void detectsGradleKts(@TempDir Path projectDir) throws Exception {
        // REQ-3-01: build.gradle.kts present -> ./gradlew test
        Files.createFile(projectDir.resolve("build.gradle.kts"));
        String cmd = runner.detectTestCommand(projectDir, new TestConfig());
        assertEquals("./gradlew test", cmd);
    }

    @Test
    void usesCommandOverride(@TempDir Path projectDir) throws Exception {
        // D-01: explicit override via test.command overrides auto-detect
        Files.createFile(projectDir.resolve("pom.xml")); // Maven presente
        TestConfig cfg = new TestConfig();
        cfg.setCommand("npm test");
        String cmd = runner.detectTestCommand(projectDir, cfg);
        assertEquals("npm test", cmd); // override wins
    }

    @Test
    void noRunnerReturnsNull(@TempDir Path projectDir) {
        // D-01: no file detected and no config -> null (caller throws clear error)
        String cmd = runner.detectTestCommand(projectDir, new TestConfig());
        assertNull(cmd);
    }

    @Test
    void parseCommandSplitsTokens() {
        // T-3-01: custom command parsed into tokens, not as a single string
        TestRunner tr = new TestRunner();
        java.util.List<String> tokens = tr.parseCommand("mvn test -pl my-module");
        assertEquals("mvn", tokens.get(0));
        assertEquals("test", tokens.get(1));
        assertEquals("-pl", tokens.get(2));
        assertEquals("my-module", tokens.get(3));
    }
}
