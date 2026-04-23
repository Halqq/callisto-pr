package dev.callisto.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigLoader.load() — D-01, D-02, D-05, T-02-02-01.
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_missingFile_returnsDefaults() {
        Path missing = tempDir.resolve("nonexistent").resolve("callisto.json");
        CallistoConfig config = ConfigLoader.load(missing);

        assertNotNull(config);
        assertEquals(".callisto", config.getOutputDir());
        assertNotNull(config.getProjectPackagePrefix());
        assertTrue(config.getProjectPackagePrefix().isEmpty());
    }

    @Test
    void load_validJson_loadsOutputDir() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        Files.write(configFile,
            "{\"outputDir\": \"custom-output\", \"projectPackagePrefix\": [\"com.example\"]}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertEquals("custom-output", config.getOutputDir());
        assertEquals(List.of("com.example"), config.getProjectPackagePrefix());
    }

    @Test
    void load_singleStringPrefix_acceptedAsList() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        // ACCEPT_SINGLE_VALUE_AS_ARRAY: single string should be wrapped in list
        Files.write(configFile,
            "{\"projectPackagePrefix\": \"com.myapp\"}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertEquals(1, config.getProjectPackagePrefix().size());
        assertEquals("com.myapp", config.getProjectPackagePrefix().get(0));
    }

    @Test
    void load_pathTraversalInOutputDir_returnsDefaults() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        Files.write(configFile,
            "{\"outputDir\": \"../evil-dir\"}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        // Path traversal blocked — falls back to defaults
        assertEquals(".callisto", config.getOutputDir());
    }

    @Test
    void load_unknownField_returnsDefaults() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        // FAIL_ON_UNKNOWN_PROPERTIES=true — unknown field triggers fallback
        Files.write(configFile,
            "{\"unknownField\": \"value\"}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertEquals(".callisto", config.getOutputDir());
    }

    @Test
    void load_githubTokenLoaded() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        Files.write(configFile,
            "{\"github\": {\"token\": \"ghp_secret\", \"repo\": \"owner/repo\"}}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertNotNull(config.getGithub());
        assertEquals("ghp_secret", config.getGithub().getToken());
        assertEquals("owner/repo", config.getGithub().getRepo());
    }

    @Test
    void load_malformedJson_returnsDefaults() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        Files.write(configFile,
            "{ not valid json!!!".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertEquals(".callisto", config.getOutputDir());
    }

    @Test
    void load_emptyJson_returnsDefaults() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        Files.write(configFile, "{}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertEquals(".callisto", config.getOutputDir());
        assertTrue(config.getProjectPackagePrefix().isEmpty());
    }

    @Test
    void load_multiplePackagePrefixes() throws Exception {
        Path configFile = tempDir.resolve("callisto.json");
        Files.write(configFile,
            "{\"projectPackagePrefix\": [\"com.myapp\", \"org.mylib\"]}".getBytes(StandardCharsets.UTF_8));

        CallistoConfig config = ConfigLoader.load(configFile);

        assertEquals(List.of("com.myapp", "org.mylib"), config.getProjectPackagePrefix());
    }
}
