package dev.callisto.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub App authentication — exchanges App ID + RSA private key for an installation
 * access token. This token is then used as a Bearer token for all GitHub API calls,
 * causing PRs and issues to open as "{app-name}[bot]".
 *
 * Auth flow:
 *   1. Sign a JWT with the app's RSA private key (RS256, 10-min TTL)
 *   2. GET /repos/{owner}/{repo}/installation — auto-detect installation ID
 *   3. POST /app/installations/{id}/access_tokens — exchange JWT for installation token
 *   4. Return installation token (valid ~1 hour)
 *
 * Private key format:
 *   GitHub generates PKCS#1 keys (BEGIN RSA PRIVATE KEY) by default.
 *   Java requires PKCS#8 (BEGIN PRIVATE KEY). Convert once with:
 *
 *     openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
 *       -in app-private-key.pem -out app-private-key-pkcs8.pem
 *
 *   Then set github.privateKeyPath in callisto.json to the PKCS#8 file path.
 *   See docs/GITHUB_APP_SETUP.md for the full setup walkthrough.
 */
public class GitHubAppAuth {

    private final String appId;
    private final Path privateKeyPath;

    public GitHubAppAuth(String appId, Path privateKeyPath) {
        this.appId = appId;
        this.privateKeyPath = privateKeyPath;
    }

    /**
     * Returns an installation access token for the given repository.
     * Valid for ~1 hour.
     *
     * @param ownerRepo "owner/repo" format
     * @throws IOException if app is not installed on the repo, key is invalid, or network fails
     */
    public String resolveInstallationToken(String ownerRepo) throws IOException {
        String jwt = buildJwt();
        String installationId = getInstallationId(jwt, ownerRepo);
        return createInstallationToken(jwt, installationId);
    }

    // --- JWT construction ---

    private String buildJwt() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        String header  = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(
            "{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + appId + "\"}"
        );
        String signingInput = header + "." + payload;
        try {
            PrivateKey key = loadPrivateKey();
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IOException("Failed to sign GitHub App JWT — check appId and privateKeyPath: " + e.getMessage(), e);
        }
    }

    private PrivateKey loadPrivateKey() throws Exception {
        String pem = Files.readString(privateKeyPath);
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IOException(
                "Private key is PKCS#1 format (BEGIN RSA PRIVATE KEY). " +
                "Callisto requires PKCS#8. Convert with:\n" +
                "  openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \\\n" +
                "    -in " + privateKeyPath + " -out app-private-key-pkcs8.pem\n" +
                "Then update github.privateKeyPath in callisto.json to the new file."
            );
        }
        String stripped = pem
            .replaceAll("-----BEGIN PRIVATE KEY-----", "")
            .replaceAll("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // --- Installation ID lookup ---

    private String getInstallationId(String jwt, String ownerRepo) throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/" + ownerRepo + "/installation"))
            .header("Authorization", "Bearer " + jwt)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                throw new IOException(
                    "GitHub App (ID " + appId + ") is not installed on " + ownerRepo + ". " +
                    "Install it at: https://github.com/settings/apps — then click 'Install App'."
                );
            }
            if (resp.statusCode() != 200) {
                throw new IOException(
                    "Failed to look up GitHub App installation. HTTP " + resp.statusCode() + ": " + resp.body()
                );
            }
            return extractField(resp.body(), "id");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while looking up installation ID", e);
        }
    }

    // --- Installation token exchange ---

    private String createInstallationToken(String jwt, String installationId) throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/app/installations/" + installationId + "/access_tokens"))
            .header("Authorization", "Bearer " + jwt)
            .header("Accept", "application/vnd.github+json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 201) {
                throw new IOException(
                    "Failed to create installation access token. HTTP " + resp.statusCode() + ": " + resp.body()
                );
            }
            return extractField(resp.body(), "token");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating installation token", e);
        }
    }

    // --- Helpers ---

    private static String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal JSON field extractor — handles both string and numeric values.
     * Avoids adding Jackson as a dependency in this auth-only class.
     */
    private static String extractField(String json, String key) throws IOException {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\"([^\"]*)\"|([0-9]+))").matcher(json);
        if (!m.find()) {
            throw new IOException(
                "Field '" + key + "' not found in response: " +
                json.substring(0, Math.min(200, json.length()))
            );
        }
        return m.group(2) != null ? m.group(2) : m.group(3);
    }
}
