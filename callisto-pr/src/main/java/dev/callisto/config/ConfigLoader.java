package dev.callisto.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads callisto.json from the working directory.
 *
 * D-01: reads from the provided Path (caller uses user.dir/callisto.json)
 * D-02: missing file → warn on System.err + silently returns defaults
 * D-04: no env var support in Phase 2
 * D-05: FAIL_ON_UNKNOWN_PROPERTIES=true; ACCEPT_SINGLE_VALUE_AS_ARRAY=true for projectPackagePrefix
 *
 * T-02-02-01 (Tampering): outputDir validated to not contain "../" — path traversal prevention
 */
public class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private ConfigLoader() {}

    /**
     * Loads callisto.json from the given path.
     *
     * @param configPath absolute path to callisto.json
     * @return loaded CallistoConfig or defaults if the file is absent
     */
    public static CallistoConfig load(Path configPath) {
        if (!Files.exists(configPath)) {
            System.err.println("[Callisto] WARN: callisto.json not found at " + configPath +
                " — using defaults. Create callisto.json to configure.");
            CallistoConfig defaults = new CallistoConfig();
            applySystemPropertyOverrides(defaults);
            return defaults;
        }
        try {
            CallistoConfig config = MAPPER.readValue(configPath.toFile(), CallistoConfig.class);
            validateConfig(config, configPath);
            applySystemPropertyOverrides(config);
            return config;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[Callisto] WARN: Failed to parse/validate callisto.json: " + e.getMessage() +
                " — using defaults.");
            CallistoConfig defaults = new CallistoConfig();
            applySystemPropertyOverrides(defaults);
            return defaults;
        }
    }

    /**
     * Placeholder for future system property overrides.
     * LLM provider/model/apiKey overrides removed — classification now uses Claude Code CLI subprocess.
     */
    private static void applySystemPropertyOverrides(CallistoConfig config) {
        // No overrides currently — LLM config removed in favor of ClaudeCodeClassifier subprocess
    }

    /**
     * Validates security fields after deserialization.
     * T-02-02-01: prevents path traversal via outputDir.
     */
    private static void validateConfig(CallistoConfig config, Path configPath) {
        if (config.getOutputDir() != null && config.getOutputDir().contains("..")) {
            throw new IllegalArgumentException(
                "[Callisto] Invalid outputDir in " + configPath +
                ": path traversal sequences ('..') are not allowed. Got: " + config.getOutputDir()
            );
        }
    }
}
