package dev.callisto.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GitHub authentication and repository configuration.
 *
 * Two auth modes (D-28a priority order):
 *
 * PAT mode (default):
 *   --token CLI flag > GITHUB_TOKEN env var > github.token in callisto.json
 *   PR opens as the token owner (the user's own GitHub account).
 *   This is the standard pattern for personal CLI tools (same as gh CLI, Act, etc.).
 *
 * GitHub App mode (optional — requires user to create their own GitHub App):
 *   Set github.appId + github.privateKeyPath in callisto.json.
 *   PR opens as "{app-name}[bot]" on behalf of the app owner.
 *   Activated automatically when appId is present and no PAT is provided.
 *   Private key must be PKCS#8 PEM format (see docs/GITHUB_APP_SETUP.md).
 *
 * Repo priority (D-28b): --repo CLI flag > auto-detect via git remote > github.repo.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class GithubConfig {
    private String token = "";
    private String repo = "";         // "owner/repo" format, e.g. "myorg/myrepo"
    private String appId = "";        // GitHub App ID (numeric, from app settings page)
    private String privateKeyPath = ""; // Path to PKCS#8 PEM private key (see docs/GITHUB_APP_SETUP.md)

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

    /** True when GitHub App credentials are configured. */
    public boolean hasAppCredentials() {
        return appId != null && !appId.isBlank()
            && privateKeyPath != null && !privateKeyPath.isBlank();
    }
}
