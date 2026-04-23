package dev.callisto.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.List;

/**
 * Root configuration POJO read from callisto.json.
 *
 * WR-01: ignoreUnknown=true for compatibility with configs from previous versions
 *        (e.g. the "llm" key in an old callisto.json no longer causes a deserialization error).
 *        Explicit validation of required fields should be done in ConfigLoader if necessary.
 *
 * D-11: projectPackagePrefix accepts a single String or List<String> via
 *       ACCEPT_SINGLE_VALUE_AS_ARRAY on the ObjectMapper.
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // forward-compat: unknown keys ignored, not fatal
public class CallistoConfig {
    private List<String> projectPackagePrefix = Collections.emptyList();
    private String outputDir = ".callisto";
    private GithubConfig github = new GithubConfig();
    private TestConfig test = new TestConfig();

    public List<String> getProjectPackagePrefix() { return projectPackagePrefix; }
    public void setProjectPackagePrefix(List<String> projectPackagePrefix) {
        this.projectPackagePrefix = projectPackagePrefix != null ? projectPackagePrefix : Collections.emptyList();
    }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public GithubConfig getGithub() { return github; }
    public void setGithub(GithubConfig github) { this.github = github != null ? github : new GithubConfig(); }

    public TestConfig getTest() { return test; }
    public void setTest(TestConfig test) { this.test = test != null ? test : new TestConfig(); }
}
