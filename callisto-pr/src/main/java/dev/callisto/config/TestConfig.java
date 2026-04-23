package dev.callisto.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Test runner configuration used for validation before opening a PR.
 *
 * D-05: @JsonIgnoreProperties(ignoreUnknown = false) — unknown fields cause an error.
 * D-01: command overrides auto-detection of the runner (pom.xml → mvn test, build.gradle → ./gradlew test).
 * D-05: maxAttempts: default 3 (configurable via callisto.json field test.maxAttempts).
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // forward-compat: unknown keys ignored, not fatal
public class TestConfig {
    private String command;
    private int maxAttempts = 3;

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
