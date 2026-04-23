package dev.callisto.github;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GitHubPrService — only tests parseOwnerRepo (no network required).
 * GitHub API calls (createDraftPr, createFallbackIssue) are tested via manual smoke test.
 */
class GitHubPrServiceTest {

    @Test
    void testRepoUrlParsing_sshFormat_returnsOwnerRepo() {
        String result = GitHubPrService.parseOwnerRepo("git@github.com:myorg/myrepo.git");
        assertEquals("myorg/myrepo", result);
    }

    @Test
    void testRepoUrlParsing_httpsFormat_returnsOwnerRepo() {
        String result = GitHubPrService.parseOwnerRepo("https://github.com/myorg/myrepo.git");
        assertEquals("myorg/myrepo", result);
    }

    @Test
    void testRepoUrlParsing_httpsWithoutDotGit_returnsOwnerRepo() {
        String result = GitHubPrService.parseOwnerRepo("https://github.com/myorg/myrepo");
        assertEquals("myorg/myrepo", result);
    }
}
