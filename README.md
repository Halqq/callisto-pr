# Callisto

Java Agent that captures runtime exceptions, classifies them as **INTERNAL** (your code) or
**EXTERNAL** (dependency/API/IO/network), and opens GitHub Draft PRs with AI-generated fixes —
all without touching a single line of your project.

```
your-app.jar  ──►  -javaagent:callisto.jar
                         │
                         ▼
                   captures exceptions
                         │
                         ▼
               .callisto/bugs.jsonl
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
          summary       show     draft-pr
          (stats)    (details)  (GitHub PR)
```

---

## Requirements

| Requirement | Purpose |
|---|---|
| Java 11+ | Agent and CLI |
| `ANTHROPIC_API_KEY` | Exception classification (default LLM) |
| `GITHUB_TOKEN` with `repo` scope | `draft-pr` command |
| `claude` CLI (authenticated) | `draft-pr` patch generation — [install](https://claude.ai/download) |

---

## Installation

**Option A — pre-built JAR (recommended)**

Download `callisto.jar` from the [Releases](../../releases) page.

**Option B — build from source**

```bash
git clone https://github.com/yourorg/callisto
cd callisto
mvn clean package -DskipTests
# Output: callisto-agent/target/callisto.jar
```

---

## Usage

**1. Attach the agent to your JVM**

```bash
java -javaagent:/path/to/callisto.jar -jar your-app.jar
```

Callisto captures every exception silently in the background. No code changes needed.

**2. View captured bugs**

```bash
java -jar callisto.jar summary
```

```
Callisto Summary
================
Total bugs:  12

By classification:
  INTERNAL      4  (████     33%)
  EXTERNAL      7  (██████   58%)
  UNCERTAIN     1  (█         8%)

Top exception types:
  NullPointerException          3
  HttpConnectTimeoutException   2
```

**3. Inspect a bug**

```bash
java -jar callisto.jar show BUG-a1b2c3
```

```
Bug BUG-a1b2c3
==============
Exception    : java.lang.NullPointerException
Message      : Cannot invoke "String.toUpperCase()" because "username" is null
Thread       : main
Captured     : 2026-04-21T10:30:00Z
Occurrences  : 3
Classification: INTERNAL
Reasoning    : Stack originates in com.example.myapp — matches project prefix
```

**4. Create a Draft PR with an AI fix**

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxx
java -jar callisto.jar draft-pr BUG-a1b2c3
```

Callisto will:
1. Ask Claude Code CLI to generate a patch
2. Create branch `callisto/fix/BUG-a1b2c3` on GitHub
3. Commit the patch and open a Draft PR
4. Run your test suite to validate the fix (retries up to `maxAttempts`)

Use `--dir PATH` on any command to point at a project other than the current directory.

---

## Configuration

Create `callisto.json` in your project root. All fields are optional.

```json
{
  "projectPackagePrefix": ["com.example.myapp"],
  "outputDir": ".callisto",
  "github": {
    "token": "",
    "repo": "owner/repo"
  },
  "test": {
    "command": "mvn test",
    "maxAttempts": 3
  }
}
```

| Field | Default | Description |
|---|---|---|
| `projectPackagePrefix` | `[]` | Package prefixes that identify your code. Frames matching any prefix → INTERNAL. |
| `outputDir` | `.callisto` | Directory where `bugs.jsonl` is written. |
| `github.token` | — | GitHub PAT. Overridden by `GITHUB_TOKEN` env var and `--token` flag. |
| `github.repo` | auto-detect | `owner/repo` slug. Auto-detected from `git remote get-url origin` if omitted. |
| `test.command` | auto-detect | Test runner command. Auto-detects `mvn test` or `gradle test`. |
| `test.maxAttempts` | `3` | Max fix-validate cycles before marking the fix as PENDING. |

> Unknown fields in `callisto.json` cause a parse error — this prevents silent misconfiguration from typos.

---

## Troubleshooting

### No bugs captured

Check `.callisto/` exists in the working directory where the JVM launched. If missing, look for
agent init errors on stderr:

```
[Callisto] WARN: Could not read callisto.json: ...
```

Also verify the agent flag is actually reaching the JVM — some launchers (Spring Boot Maven plugin,
Gradle application plugin) need `JAVA_OPTS` or `jvmArgs` set explicitly:

```bash
# Maven / Spring Boot
export JAVA_OPTS="-javaagent:/path/to/callisto.jar"

# Gradle (build.gradle)
run { jvmArgs '-javaagent:/path/to/callisto.jar' }
```

### `draft-pr` creates Issues instead of PRs

This fallback triggers when:
- `claude` CLI is not installed or not in `PATH`
- `claude` auth has expired — run `claude auth login` to refresh
- Claude could not determine a patch (no file change generated)

Verify with:

```bash
claude --version
claude auth status
```
