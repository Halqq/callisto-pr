package dev.callisto.github;

import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real integration smoke tests — create actual PRs and Issues on Halqq/callisto-pr.
 *
 * Guards:
 *   Personal token path: CALLISTO_SMOKE=true (uses gh CLI token)
 *   GitHub App path:     CALLISTO_SMOKE_APP=true + GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY_PATH
 *
 * Run:
 *   CALLISTO_SMOKE=true mvn test -pl callisto-agent -Dtest=GitHubSmokeTest
 *   CALLISTO_SMOKE_APP=true GITHUB_APP_ID=123 GITHUB_APP_PRIVATE_KEY_PATH=/path/key.pem \
 *     mvn test -pl callisto-agent -Dtest=GitHubSmokeTest
 *
 * Cleanup: tests close and delete branches automatically after assertions.
 */
class GitHubSmokeTest {

    private static final String OWNER_REPO = "Halqq/callisto-pr";

    /** PRs and branches to clean up in @AfterEach */
    private final List<Runnable> cleanupTasks = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Runnable task : cleanupTasks) {
            try { task.run(); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String ghToken() throws Exception {
        // Use the token from `gh auth token` — same as gh CLI uses
        ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String token = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        assertFalse(token.isBlank(), "gh auth token returned empty — run: gh auth login");
        return token;
    }

    private BugRecord makeSmokeRecord(String id, String exType, String classification) {
        BugRecord r = new BugRecord();
        r.setId(id);
        r.setExceptionType(exType);
        r.setExceptionMessage("[Callisto smoke test] " + id + " — safe to close");
        r.setClassification(classification);
        r.setReasoning("Smoke test — auto-generated, auto-closed");
        r.setThreadName("smoke-test-thread");
        r.setStackTrace(List.of(
            "dev.callisto.github.GitHubSmokeTest.smokeFn(GitHubSmokeTest.java:1)",
            "java.lang.Thread.run(Thread.java:833)"
        ));
        return r;
    }

    private void scheduleClosePr(String token, String prUrl) {
        cleanupTasks.add(() -> {
            try {
                GitHub gh = GitHub.connectUsingOAuth(token);
                GHRepository repo = gh.getRepository(OWNER_REPO);
                // Parse PR number from URL
                String[] parts = prUrl.split("/");
                int prNumber = Integer.parseInt(parts[parts.length - 1]);
                GHPullRequest pr = repo.getPullRequest(prNumber);
                pr.close();
                // Delete the branch
                try {
                    repo.getRef("heads/" + pr.getHead().getRef()).delete();
                } catch (IOException ignored) {}
            } catch (Exception e) {
                System.err.println("[smoke cleanup] Failed to close PR: " + e.getMessage());
            }
        });
    }

    private void scheduleCloseIssue(String token, String issueUrl) {
        cleanupTasks.add(() -> {
            try {
                GitHub gh = GitHub.connectUsingOAuth(token);
                GHRepository repo = gh.getRepository(OWNER_REPO);
                String[] parts = issueUrl.split("/");
                int issueNum = Integer.parseInt(parts[parts.length - 1]);
                repo.getIssue(issueNum).close();
            } catch (Exception e) {
                System.err.println("[smoke cleanup] Failed to close issue: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Test 1 (Personal Token): Draft PR — branch + commit + PR created
    // ─────────────────────────────────────────────────────────────
    @Test
    @EnabledIfEnvironmentVariable(named = "CALLISTO_SMOKE", matches = "true")
    void smoke_personalToken_createsDraftPr() throws Exception {
        String token = ghToken();
        GitHubPrService service = new GitHubPrService(token, OWNER_REPO);

        // Unique ID per run — branch name = callisto/fix/<id>, must not collide across runs
        String runId = "BUG-s1-" + Long.toHexString(System.currentTimeMillis()).substring(4);
        BugRecord bug = makeSmokeRecord(runId, "java.lang.NullPointerException", "INTERNAL");

        // Provide a real file — GitHub rejects empty trees (422)
        ClaudeOutput patch = new ClaudeOutput(
            ".callisto-smoke-test.md",
            "# Callisto Smoke Test\n\nAuto-generated by `GitHubSmokeTest`. **Safe to delete.**\nRun: " + runId + "\n",
            "## Callisto Smoke Test PR\n\nAuto-generated by `GitHubSmokeTest`. **Safe to close.**\n\n" +
            "- Bug ID: `" + runId + "`\n- Exception: `NullPointerException`\n- Classification: INTERNAL"
        );

        String prUrl = service.createDraftPr(bug, patch);

        System.out.println("[smoke] Created Draft PR: " + prUrl);
        scheduleClosePr(token, prUrl);

        assertNotNull(prUrl, "PR URL must not be null");
        assertTrue(prUrl.startsWith("https://github.com/Halqq/callisto-pr/pull/"),
            "PR URL must point to callisto-pr. Got: " + prUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2 (Personal Token): Fallback Issue — when PR fails
    // ─────────────────────────────────────────────────────────────
    @Test
    @EnabledIfEnvironmentVariable(named = "CALLISTO_SMOKE", matches = "true")
    void smoke_personalToken_createsFallbackIssue() throws Exception {
        String token = ghToken();
        GitHubPrService service = new GitHubPrService(token, OWNER_REPO);

        BugRecord bug = makeSmokeRecord("BUG-smoke2", "java.net.SocketTimeoutException", "EXTERNAL");

        String issueUrl = service.createFallbackIssue(bug);

        System.out.println("[smoke] Created Fallback Issue: " + issueUrl);
        scheduleCloseIssue(token, issueUrl);

        assertNotNull(issueUrl, "Issue URL must not be null");
        assertTrue(issueUrl.startsWith("https://github.com/Halqq/callisto-pr/issues/"),
            "Issue URL must point to callisto-pr issues. Got: " + issueUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3 (Personal Token): Parse remote URL + create Issue (full CLI flow)
    // ─────────────────────────────────────────────────────────────
    @Test
    @EnabledIfEnvironmentVariable(named = "CALLISTO_SMOKE", matches = "true")
    void smoke_parseRemoteUrl_thenCreateIssue() throws Exception {
        String token = ghToken();

        // Simulates what DraftPrCommand does: parses remote → ownerRepo
        String remoteUrl = "https://github.com/Halqq/callisto-pr.git";
        String ownerRepo = GitHubPrService.parseOwnerRepo(remoteUrl);
        assertEquals("Halqq/callisto-pr", ownerRepo);

        GitHubPrService service = new GitHubPrService(token, ownerRepo);
        BugRecord bug = makeSmokeRecord("BUG-smoke3", "java.lang.IllegalStateException", "INTERNAL");

        String issueUrl = service.createFallbackIssue(bug);

        System.out.println("[smoke] Created Issue via parsed remote: " + issueUrl);
        scheduleCloseIssue(token, issueUrl);

        assertTrue(issueUrl.contains("github.com/Halqq/callisto-pr/issues/"),
            "Issue URL must be on the parsed repo. Got: " + issueUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 4 (GitHub App): App JWT → installation token → Draft PR
    // Requires: CALLISTO_SMOKE_APP=true, GITHUB_APP_ID, GITHUB_APP_PRIVATE_KEY_PATH
    // ─────────────────────────────────────────────────────────────
    @Test
    @EnabledIfEnvironmentVariable(named = "CALLISTO_SMOKE_APP", matches = "true")
    void smoke_githubApp_createsDraftPr() throws Exception {
        String appId = System.getenv("GITHUB_APP_ID");
        String keyPath = System.getenv("GITHUB_APP_PRIVATE_KEY_PATH");

        assertNotNull(appId, "GITHUB_APP_ID env var must be set");
        assertNotNull(keyPath, "GITHUB_APP_PRIVATE_KEY_PATH env var must be set");

        // Exchange App credentials for installation token
        GitHubAppAuth appAuth = new GitHubAppAuth(appId, Path.of(keyPath));
        String installationToken = appAuth.resolveInstallationToken(OWNER_REPO);

        assertNotNull(installationToken, "Installation token must not be null");
        assertFalse(installationToken.isBlank(), "Installation token must not be blank");
        System.out.println("[smoke] GitHub App token resolved (ghs_***)");

        // Use the installation token to create a real Draft PR
        GitHubPrService service = new GitHubPrService(installationToken, OWNER_REPO);
        BugRecord bug = makeSmokeRecord("BUG-smokeApp", "java.io.IOException", "EXTERNAL");

        ClaudeOutput patch = new ClaudeOutput(
            ".callisto-smoke-app.md",
            "# Callisto Smoke Test — GitHub App\n\nCreated by GitHub App bot. **Safe to delete.**\n",
            "## Callisto Smoke Test — GitHub App\n\nCreated by the GitHub App bot. **Safe to close.**\n\n" +
            "- Bug ID: `BUG-smokeApp`\n- Auth: GitHub App installation token"
        );

        String prUrl = service.createDraftPr(bug, patch);

        System.out.println("[smoke] GitHub App created Draft PR: " + prUrl);
        scheduleClosePr(installationToken, prUrl);

        assertNotNull(prUrl);
        assertTrue(prUrl.startsWith("https://github.com/Halqq/callisto-pr/pull/"),
            "App-created PR must point to callisto-pr. Got: " + prUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 5 (GitHub App): App token → Fallback Issue
    // ─────────────────────────────────────────────────────────────
    @Test
    @EnabledIfEnvironmentVariable(named = "CALLISTO_SMOKE_APP", matches = "true")
    void smoke_githubApp_createsFallbackIssue() throws Exception {
        String appId = System.getenv("GITHUB_APP_ID");
        String keyPath = System.getenv("GITHUB_APP_PRIVATE_KEY_PATH");

        assertNotNull(appId, "GITHUB_APP_ID env var must be set");
        assertNotNull(keyPath, "GITHUB_APP_PRIVATE_KEY_PATH env var must be set");

        GitHubAppAuth appAuth = new GitHubAppAuth(appId, Path.of(keyPath));
        String installationToken = appAuth.resolveInstallationToken(OWNER_REPO);

        GitHubPrService service = new GitHubPrService(installationToken, OWNER_REPO);
        BugRecord bug = makeSmokeRecord("BUG-smokeAppIssue", "java.lang.OutOfMemoryError", "EXTERNAL");

        String issueUrl = service.createFallbackIssue(bug);

        System.out.println("[smoke] GitHub App created Fallback Issue: " + issueUrl);
        scheduleCloseIssue(installationToken, issueUrl);

        assertNotNull(issueUrl);
        assertTrue(issueUrl.startsWith("https://github.com/Halqq/callisto-pr/issues/"),
            "App-created issue must point to callisto-pr. Got: " + issueUrl);
    }
}
