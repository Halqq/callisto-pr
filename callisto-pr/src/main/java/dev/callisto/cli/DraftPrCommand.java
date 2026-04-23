package dev.callisto.cli;

import dev.callisto.config.CallistoConfig;
import dev.callisto.config.ConfigLoader;
import dev.callisto.config.GithubConfig;
import dev.callisto.config.TestConfig;
import dev.callisto.github.ClaudeCodePatchGenerator;
import dev.callisto.github.ClaudeOutput;
import dev.callisto.github.GitHubPrService;
import dev.callisto.model.BugRecord;
import dev.callisto.model.FixValidation;
import dev.callisto.store.BugStore;
import dev.callisto.validation.TestResult;
import dev.callisto.validation.TestRunner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import dev.callisto.github.GitHubAppAuth;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * `callisto draft-pr &lt;id&gt;` subcommand.
 *
 * <p>D-28a: Token priority: --token flag &gt; GITHUB_TOKEN env var &gt; callisto.json github.token
 * <p>D-28b: Repo detection: git -C &lt;dir&gt; remote get-url origin → parse → --repo override
 * <p>D-29: Branch = callisto/fix/&lt;id&gt;
 * <p>D-32: Primary: branch+commit+Draft PR; Fallback: GitHub Issue
 * <p>D-33: --dir defaults to user.dir; used for bugs.jsonl, git remote, claude CWD
 *
 * <p>T-5-01: Resolved token is never printed to stdout/stderr.
 * <p>T-5-02: ProcessBuilder uses List&lt;String&gt; — no shell string concatenation.
 */
@Command(
    name = "draft-pr",
    description = "Create a GitHub Draft PR for a captured bug (requires claude CLI + GITHUB_TOKEN)"
)
public class DraftPrCommand implements Runnable {

    @Parameters(index = "0", description = "Bug ID (e.g. BUG-1a2b3c)", paramLabel = "ID")
    private String bugId;

    @Option(
        names = "--dir",
        description = "Project directory containing .callisto/ (default: current working directory)",
        defaultValue = "${sys:user.dir}",
        paramLabel = "PATH"
    )
    private String dir;

    @Option(names = "--token", description = "GitHub personal access token (overrides GITHUB_TOKEN env var)", paramLabel = "TOKEN")
    private String token;

    @Option(names = "--repo", description = "GitHub repository in owner/repo format (overrides auto-detect)", paramLabel = "OWNER/REPO")
    private String repo;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        Path projectDir = Paths.get(dir).toAbsolutePath();
        Path bugsFile = projectDir.resolve(".callisto/bugs.jsonl");
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        // 1. Find bugs.jsonl
        if (!Files.exists(bugsFile)) {
            err.println("[Callisto] No bugs.jsonl found at " + bugsFile);
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "bugs.jsonl not found");
        }

        // 2. Find bug by ID
        List<BugRecord> records = BugStore.readAll(bugsFile);
        BugRecord bug = records.stream()
            .filter(r -> bugId.equals(r.getId()))
            .findFirst()
            .orElse(null);
        if (bug == null) {
            err.println("[Callisto] Bug not found: " + bugId);
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "Bug not found: " + bugId);
        }

        // 3. Load config once — shared by token resolution, repo resolution, and test settings (WR-02)
        CallistoConfig config = ConfigLoader.load(projectDir.resolve("callisto.json"));

        // 4. Resolve repo first (D-28b) — needed before app auth token exchange
        String resolvedRepo = resolveRepoLogic(repo, projectDir, config);
        if (resolvedRepo == null || resolvedRepo.isEmpty()) {
            err.println("[Callisto] Could not detect GitHub repository. Use --repo owner/repo flag.");
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "Missing GitHub repo");
        }

        // 5. Resolve GitHub token (D-28a): --token > GITHUB_TOKEN env > callisto.json github.token
        //    App fallback: if no PAT found and github.appId + github.privateKeyPath are set,
        //    exchange RSA private key for an installation access token (PRs open as "{app}[bot]").
        String resolvedToken = resolveTokenLogic(token, System.getenv("GITHUB_TOKEN"), config);
        if (resolvedToken == null) {
            resolvedToken = resolveAppToken(config, resolvedRepo, err);
        }
        if (resolvedToken == null) {
            err.println("[Callisto] No GitHub credentials found. Options:");
            err.println("[Callisto]   PAT:     set GITHUB_TOKEN env var, --token flag, or github.token in callisto.json");
            err.println("[Callisto]   App:     set github.appId + github.privateKeyPath in callisto.json (see docs/GITHUB_APP_SETUP.md)");
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "Missing GitHub credentials");
        }

        // 5b. Test settings (D-05: maxAttempts default 3)
        TestConfig testConfig = config.getTest();
        int maxAttempts = testConfig.getMaxAttempts();

        // 5b. Detect test runner (D-01)
        TestRunner testRunner = new TestRunner();
        String testCmd = testRunner.detectTestCommand(projectDir, testConfig);
        if (testCmd == null) {
            err.println("[Callisto] Cannot detect test runner. Set test.command in callisto.json.");
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "No test runner detected");
        }

        // 6. Invoke Claude Code + validation loop (D-03, D-04, D-05, D-06)
        GitHubPrService prService = new GitHubPrService(resolvedToken, resolvedRepo);
        try {
            ClaudeCodePatchGenerator generator = new ClaudeCodePatchGenerator();
            ClaudeOutput patch = generator.generate(bug, projectDir);
            String sessionId = patch.getSessionId();

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                out.printf("[Callisto] Running tests (attempt %d/%d)...%n", attempt, maxAttempts);
                out.flush();

                TestResult testResult = testRunner.run(projectDir, testCmd);

                if (testResult.getExitCode() == 0) {
                    // Tests passed → VALID → proceed with PR (REQ-3-04)
                    String prUrl = prService.createDraftPr(bug, patch);
                    // D-07: lastTestOutput omitted when VALID
                    FixValidation fv = new FixValidation(attempt, null, prUrl);
                    BugStore bugStore = new BugStore(bugsFile);
                    bugStore.updateFixStatus(bug.getId(), "VALID", fv);
                    out.println("Created Draft PR: " + prUrl);
                    out.flush();
                    return; // success
                }

                // Tests failed (REQ-3-03)
                if (attempt == maxAttempts) {
                    // Exhausted attempts → INVALID, do not open PR (D-06)
                    FixValidation fv = new FixValidation(attempt, testResult.getOutput(), null);
                    BugStore bugStore = new BugStore(bugsFile);
                    bugStore.updateFixStatus(bug.getId(), "INVALID", fv);
                    out.printf("[Callisto] Invalid fix after %d attempts — PR not opened. See: callisto show %s%n",
                        maxAttempts, bug.getId());
                    out.flush();
                    return; // abort without PR
                }

                // Retry via --resume (D-04)
                out.println("[Callisto] Tests failed. Retrying with test output...");
                out.flush();
                try {
                    patch = generator.resume(sessionId, testResult.getOutput());
                    sessionId = patch.getSessionId() != null ? patch.getSessionId() : sessionId;
                } catch (IllegalArgumentException e) {
                    // Pitfall 4: session_id absent — fallback to a full new generate() call
                    err.println("[Callisto] WARN: " + e.getMessage() + " — retrying with full context.");
                    err.flush();
                    patch = generator.generate(bug, projectDir);
                    sessionId = patch.getSessionId();
                }
            }
        } catch (IOException | InterruptedException e) {
            // D-32 Fallback: Claude Code failed OR GitHub PR API failed
            err.println("[Callisto] Primary flow failed (" + e.getMessage() + "), trying Issue fallback...");
            err.flush();
            try {
                String issueUrl = prService.createFallbackIssue(bug);
                out.println("Fallback: created Issue: " + issueUrl);
                out.flush();
            } catch (IOException fallbackEx) {
                err.println("[Callisto] Fallback also failed: " + fallbackEx.getMessage());
                err.flush();
                throw new CommandLine.ExecutionException(spec.commandLine(), "Both PR and Issue creation failed");
            }
        }
    }

    /**
     * Token resolution logic (D-28a): flag &gt; env var &gt; callisto.json github.token.
     * Package-private so tests can call directly without environment manipulation.
     * WR-02: accepts pre-loaded config to avoid redundant ConfigLoader.load() calls.
     *
     * @return resolved token, or null if none found
     */
    String resolveTokenLogic(String flagToken, String envToken, CallistoConfig cfg) {
        // 1. CLI flag
        if (flagToken != null && !flagToken.isEmpty()) return flagToken;
        // 2. Env var
        if (envToken != null && !envToken.isEmpty()) return envToken;
        // 3. callisto.json
        GithubConfig gh = cfg.getGithub();
        if (gh != null && gh.getToken() != null && !gh.getToken().isEmpty()) return gh.getToken();
        // None found
        return null;
    }

    /**
     * Repo resolution logic (D-28b): flag &gt; git remote auto-detect &gt; callisto.json github.repo.
     * Package-private for testability.
     * WR-02: accepts pre-loaded config to avoid redundant ConfigLoader.load() calls.
     *
     * @return resolved "owner/repo", or null if none found
     */
    String resolveRepoLogic(String flagRepo, Path projectDir, CallistoConfig cfg) {
        // 1. CLI flag
        if (flagRepo != null && !flagRepo.isEmpty()) return flagRepo;
        // 2. git remote auto-detect
        String detected = detectRepoFromGit(projectDir);
        if (detected != null && !detected.isEmpty()) return detected;
        // 3. callisto.json
        GithubConfig gh = cfg.getGithub();
        if (gh != null && gh.getRepo() != null && !gh.getRepo().isEmpty()) return gh.getRepo();
        // None found
        return null;
    }

    /**
     * GitHub App auth fallback: if callisto.json has github.appId + github.privateKeyPath,
     * exchange the RSA private key for an installation access token.
     * WR-02: accepts pre-loaded config to avoid redundant ConfigLoader.load() calls.
     * Returns null if app credentials are absent or app is not installed on the repo.
     */
    private String resolveAppToken(CallistoConfig cfg, String resolvedRepo, PrintWriter err) {
        GithubConfig gh = cfg.getGithub();
        if (gh == null || !gh.hasAppCredentials()) return null;
        try {
            GitHubAppAuth appAuth = new GitHubAppAuth(gh.getAppId(), Paths.get(gh.getPrivateKeyPath()));
            return appAuth.resolveInstallationToken(resolvedRepo);
        } catch (IOException e) {
            err.println("[Callisto] GitHub App auth failed: " + e.getMessage());
            err.flush();
            return null;
        }
    }

    /**
     * D-28b: Runs `git -C &lt;dir&gt; remote get-url origin` and parses SSH/HTTPS format.
     * T-5-02: Uses List&lt;String&gt; ProcessBuilder — no shell string expansion.
     * Returns null on any failure (missing git, non-GitHub remote, etc.).
     */
    private String detectRepoFromGit(Path projectDir) {
        try {
            // T-5-02: List<String> args — projectDir.toString() is a path, no shell expansion
            ProcessBuilder pb = new ProcessBuilder(
                "git", "-C", projectDir.toString(), "remote", "get-url", "origin"
            );
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String remoteUrl = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = p.waitFor();
            if (exitCode != 0 || remoteUrl.isEmpty()) return null;
            return GitHubPrService.parseOwnerRepo(remoteUrl);
        } catch (IOException | InterruptedException e) {
            return null; // git not found or no remote — caller falls through to config
        }
    }
}
