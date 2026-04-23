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
 * {@code callisto draft-pr <id>} subcommand.
 *
 * <p>Generates a fix for a captured bug via Claude Code CLI, validates it by running
 * the project's test suite, then opens a GitHub Draft PR on success or falls back to
 * a GitHub Issue if the PR API is unavailable.
 *
 * <p>Token resolution order: {@code --token} flag → {@code GITHUB_TOKEN} env var →
 * {@code callisto.json github.token} → GitHub App credentials.
 * The resolved token is never printed to stdout or stderr.
 *
 * <p>Repo detection order: {@code --repo} flag → {@code git remote get-url origin} →
 * {@code callisto.json github.repo}.
 *
 * <p>{@code --dir} defaults to the current working directory and controls where
 * {@code bugs.jsonl} is read from, where git is queried, and where Claude runs.
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

        // 3. Load config once — shared by token resolution, repo resolution, and test settings
        CallistoConfig config = ConfigLoader.load(projectDir.resolve("callisto.json"));

        // 4. Resolve repo first — needed before app auth token exchange
        String resolvedRepo = resolveRepoLogic(repo, projectDir, config);
        if (resolvedRepo == null || resolvedRepo.isEmpty()) {
            err.println("[Callisto] Could not detect GitHub repository. Use --repo owner/repo flag.");
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "Missing GitHub repo");
        }

        // 5. Resolve GitHub token: --token > GITHUB_TOKEN env > callisto.json github.token
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

        // 5b. Test settings (maxAttempts default 3)
        TestConfig testConfig = config.getTest();
        int maxAttempts = testConfig.getMaxAttempts();

        // 5b. Detect test runner
        TestRunner testRunner = new TestRunner();
        String testCmd = testRunner.detectTestCommand(projectDir, testConfig);
        if (testCmd == null) {
            err.println("[Callisto] Cannot detect test runner. Set test.command in callisto.json.");
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "No test runner detected");
        }

        // 6. Invoke Claude Code + validation loop
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
                    // Tests passed → VALID → proceed with PR
                    String prUrl = prService.createDraftPr(bug, patch);
                    // lastTestOutput omitted when VALID
                    FixValidation fv = new FixValidation(attempt, null, prUrl);
                    BugStore bugStore = new BugStore(bugsFile);
                    bugStore.updateFixStatus(bug.getId(), "VALID", fv);
                    out.println("Created Draft PR: " + prUrl);
                    out.flush();
                    return; // success
                }

                // Tests failed
                if (attempt == maxAttempts) {
                    // Exhausted attempts → INVALID, do not open PR
                    FixValidation fv = new FixValidation(attempt, testResult.getOutput(), null);
                    BugStore bugStore = new BugStore(bugsFile);
                    bugStore.updateFixStatus(bug.getId(), "INVALID", fv);
                    out.printf("[Callisto] Invalid fix after %d attempts — PR not opened. See: callisto show %s%n",
                        maxAttempts, bug.getId());
                    out.flush();
                    return; // abort without PR
                }

                // Retry via --resume with the test output for context
                out.println("[Callisto] Tests failed. Retrying with test output...");
                out.flush();
                try {
                    patch = generator.resume(sessionId, testResult.getOutput());
                    sessionId = patch.getSessionId() != null ? patch.getSessionId() : sessionId;
                } catch (IllegalArgumentException e) {
                    // session_id absent — fallback to a full new generate() call
                    err.println("[Callisto] WARN: " + e.getMessage() + " — retrying with full context.");
                    err.flush();
                    patch = generator.generate(bug, projectDir);
                    sessionId = patch.getSessionId();
                }
            }
        } catch (IOException | InterruptedException e) {
            // Fallback: Claude Code failed or GitHub PR API failed — try creating an Issue instead
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
     * Token resolution logic: {@code --token} flag → env var → {@code callisto.json github.token}.
     * Package-private so tests can call directly without environment manipulation.
     * Accepts pre-loaded config to avoid redundant {@link ConfigLoader#load} calls.
     *
     * @return resolved token, or {@code null} if none found
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
     * Repo resolution logic: {@code --repo} flag → git remote auto-detect → {@code callisto.json github.repo}.
     * Package-private for testability.
     * Accepts pre-loaded config to avoid redundant {@link ConfigLoader#load} calls.
     *
     * @return resolved {@code "owner/repo"}, or {@code null} if none found
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
     * GitHub App auth fallback: if {@code callisto.json} has {@code github.appId} and
     * {@code github.privateKeyPath}, exchanges the RSA private key for an installation
     * access token. Returns {@code null} if app credentials are absent or the app
     * is not installed on the target repo.
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
     * Runs {@code git -C <dir> remote get-url origin} and parses the SSH or HTTPS remote URL
     * into {@code "owner/repo"} format. Uses {@code List<String>} args — no shell expansion.
     * Returns {@code null} on any failure (git not found, non-GitHub remote, etc.).
     */
    private String detectRepoFromGit(Path projectDir) {
        try {
            // List<String> args — projectDir.toString() is a path, no shell expansion
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
