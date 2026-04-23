package dev.callisto.github;

import dev.callisto.model.BugRecord;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.util.Date;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Encapsulates all kohsuke/github-api calls for Phase 5 Draft PR creation.
 *
 * D-32 Primary: branch → commit → Draft PR via GitHub Git Data API.
 * D-32 Fallback: createFallbackIssue() when PR creation fails or patch is absent.
 * D-29: Branch name = callisto/fix/&lt;id&gt; (no slug suffix).
 *
 * Pitfall 1 (RESEARCH.md): getRef("heads/&lt;default&gt;") — NO "refs/" prefix.
 *                           createRef("refs/heads/...") — WITH "refs/" prefix.
 * Pitfall 5: repo.getDefaultBranch() — NOT hard-coded "main".
 */
public class GitHubPrService {

    private final String token;
    private final String ownerRepo;

    public GitHubPrService(String token, String ownerRepo) {
        this.token = token;
        this.ownerRepo = ownerRepo;
    }

    /**
     * Creates a GitHub branch, commits the patch from ClaudeOutput, and opens a Draft PR.
     *
     * @return HTML URL of the created PR
     * @throws IOException on GitHub API failure (caller should catch and call createFallbackIssue)
     */
    public String createDraftPr(BugRecord bug, ClaudeOutput patch) throws IOException {
        GitHub github = GitHub.connectUsingOAuth(token);
        GHRepository repo = github.getRepository(ownerRepo);
        String defaultBranch = repo.getDefaultBranch();

        // Pitfall 1: getRef does NOT take "refs/" prefix
        GHRef headRef = repo.getRef("heads/" + defaultBranch);
        String headSha = headRef.getObject().getSha();
        String headTreeSha = repo.getCommit(headSha).getTree().getSha();

        String branchName = "callisto/fix/" + bug.getId();

        // Pitfall 1: createRef REQUIRES "refs/" prefix
        GHRef branchRef = repo.createRef("refs/heads/" + branchName, headSha);

        GHTree tree;
        if (patch.getFilePath() != null && patch.getFileContent() != null) {
            // Pitfall 3: add() requires FULL file content, not a diff
            tree = repo.createTree()
                .baseTree(headTreeSha)
                .add(patch.getFilePath(), patch.getFileContent(), false)
                .create();
        } else {
            // No file patch — create an empty commit (PR body only)
            tree = repo.createTree()
                .baseTree(headTreeSha)
                .create();
        }

        String simpleExceptionName = simpleClassName(bug.getExceptionType());
        Date now = new Date();
        GHCommit commit = repo.createCommit()
            .message("fix: address " + simpleExceptionName + "\n\n[Callisto] " + bug.getId())
            .tree(tree.getSha())
            .parent(headSha)
            .author("Claude Code", "claude-code@anthropic.com", now)
            .committer("Claude Code", "claude-code@anthropic.com", now)
            .create();

        // branchRef.updateTo() uses PATCH via HttpURLConnection which fails on Java 17+.
        // Use java.net.http.HttpClient (JDK 11+) which supports PATCH natively.
        patchRef(ownerRepo, branchName, commit.getSHA1());

        String prTitle = "[Callisto] Fix " + simpleExceptionName + " (" + bug.getId() + ")";
        GHPullRequest pr = repo.createPullRequest(
            prTitle,
            branchName,         // head (branch)
            defaultBranch,      // base
            patch.getPrBody(),  // body — Claude Code-generated (D-31)
            true,               // maintainerCanModify
            true                // draft = true ALWAYS (D-31)
        );

        return pr.getHtmlUrl().toString();
    }

    /**
     * D-32 Fallback: Creates a GitHub Issue with bug details when Draft PR creation fails.
     *
     * @return HTML URL of the created Issue
     */
    public String createFallbackIssue(BugRecord bug) throws IOException {
        GitHub github = GitHub.connectUsingOAuth(token);
        GHRepository repo = github.getRepository(ownerRepo);

        String title = "[Callisto] " + bug.getExceptionType() + " \u2014 " + bug.getId();
        String body = buildIssueBody(bug);

        GHIssue issue = repo.createIssue(title)
            .body(body)
            .create();

        return issue.getHtmlUrl().toString();
    }

    /**
     * Parses a GitHub SSH or HTTPS remote URL to extract "owner/repo".
     *
     * Handles:
     *   git@github.com:owner/repo.git  → owner/repo
     *   https://github.com/owner/repo.git → owner/repo
     *   https://github.com/owner/repo  → owner/repo
     *
     * Package-private for unit testing (no network needed).
     */
    public static String parseOwnerRepo(String remoteUrl) {
        if (remoteUrl == null) return null;
        String cleaned = remoteUrl.trim();
        // SSH format: git@github.com:owner/repo.git
        cleaned = cleaned.replaceAll("^git@github\\.com:", "");
        // HTTPS format: https://github.com/owner/repo.git
        cleaned = cleaned.replaceAll("^https://github\\.com/", "");
        // Remove .git suffix
        cleaned = cleaned.replaceAll("\\.git$", "");
        return cleaned;
    }

    private String buildIssueBody(BugRecord bug) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Bug Report (captured by Callisto)\n\n");
        sb.append("**ID:** ").append(bug.getId()).append("\n");
        sb.append("**Exception:** ").append(bug.getExceptionType()).append("\n");
        if (bug.getExceptionMessage() != null && !bug.getExceptionMessage().isEmpty()) {
            sb.append("**Message:** ").append(bug.getExceptionMessage()).append("\n");
        }
        sb.append("**Classification:** ").append(bug.getClassification()).append("\n");
        if (bug.getReasoning() != null && !bug.getReasoning().isEmpty()) {
            sb.append("\n**Reasoning:** ").append(bug.getReasoning()).append("\n");
        }
        if (bug.getStackTrace() != null && !bug.getStackTrace().isEmpty()) {
            sb.append("\n**Stack Trace:**\n```\n");
            for (String frame : bug.getStackTrace()) {
                sb.append(frame).append("\n");
            }
            sb.append("```\n");
        }
        return sb.toString();
    }

    /**
     * Updates a branch ref via PATCH using java.net.http.HttpClient.
     * Replaces kohsuke's GHRef.updateTo() which fails on Java 17+ due to
     * HttpURLConnection restrictions on the PATCH verb.
     */
    private void patchRef(String ownerRepo, String branchName, String sha) throws IOException {
        String url = "https://api.github.com/repos/" + ownerRepo + "/git/refs/heads/" + branchName;
        String body = "{\"sha\":\"" + sha + "\",\"force\":false}";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Failed to update branch ref: " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while updating branch ref", e);
        }
    }

    private String simpleClassName(String fullyQualifiedName) {
        if (fullyQualifiedName == null) return "Exception";
        int dot = fullyQualifiedName.lastIndexOf('.');
        return dot >= 0 ? fullyQualifiedName.substring(dot + 1) : fullyQualifiedName;
    }
}
