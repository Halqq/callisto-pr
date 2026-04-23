<!-- GSD:project-start source:PROJECT.md -->
## Project

**Callisto**

Callisto é um Java Agent (JAR) que roda em paralelo a projetos Java existentes, interceptando exceptions em runtime via JVM instrumentation. Captura stack traces, classifica bugs como internos (código do projeto) ou externos (APIs, IO, rede) usando um LLM configurável, e oferece CLI para visualizar histórico e gerar draft PRs reais no GitHub.

**Core Value:** Classificar automaticamente se um bug é culpa do código do projeto ou de uma dependência/API externa, poupando tempo de triagem.

### Constraints

- **Tech Stack**: Java — tudo no JAR, sem sidecar externo
- **Integração**: Zero-config nos projetos alvo — só adicionar `-javaagent:callisto.jar` na JVM
- **LLM**: Pluggable — Anthropic (padrão), OpenAI, DeepSeek via config
- **Persistência**: JSON local — sem dependência de banco
- **Identidade do PR**: Suporta dois modos de autenticação GitHub:
  - **Personal token** (padrão): `GITHUB_TOKEN` env var ou `github.token` em `callisto.json` — PRs abrem com identidade do usuário. Mesmo padrão de gh CLI, Act, Renovate self-hosted.
  - **GitHub App** (opcional): `github.appId` + `github.privateKeyPath` em `callisto.json` — PRs abrem como `{app-name}[bot]`. Implementado em `GitHubAppAuth.java`. Útil para times que querem PRs abertos por um bot dedicado.
  - A classe `GitHubAppAuth` é parte intencional do projeto. Não remover.
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## Core Agent
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Byte Buddy | 1.14.x | JVM bytecode instrumentation | See rationale below |
| java.lang.instrument | JDK built-in | `premain` / `agentmain` entry points | Required by JVM agent spec |
- ASM operates at raw bytecode level — you write visitor classes for every opcode. Correct for library authors, overkill for intercepting `throw` / exception handler sites.
- Byte Buddy provides a fluent DSL: `new AgentBuilder().type(isSubTypeOf(Throwable.class)).transform(...)`. Readable, maintainable, safe against JDK version changes.
- Byte Buddy is the instrumentation backbone of major APM agents (Elastic, Datadog Java agent) in 2024-2025 — ecosystem validation is strong.
- **Do not use raw Instrumentation + ClassFileTransformer directly** unless Byte Buddy is insufficient. The retransformation / class-loading edge cases are where bugs live.
- Javassist — effectively unmaintained for new projects, lacks Java 17+ module compatibility.
- AspectJ compile-time weaving — defeats the zero-config goal; requires target project changes.
## HTTP / LLM Client
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Java `java.net.http.HttpClient` | JDK 11+ built-in | HTTP calls to LLM APIs | Zero extra dependency |
- `java.net.http.HttpClient` (JEP 321, JDK 11) is production-stable, supports async via `CompletableFuture`, HTTP/2, and has no transitive dependency weight — critical for a fat JAR that must not bloat or conflict with the target application's classpath.
- OkHttp 4.x is excellent but adds ~500KB + Kotlin stdlib to the JAR. For a tool distributed as a single agent JAR, that dependency cost is unjustified.
- **Do not use Apache HttpClient 4.x** — deprecated in favor of 5.x and overly verbose.
- **Do not use Retrofit** — annotation processor adds complexity with no benefit for 3 fixed API endpoints.
## CLI
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| picocli | 4.7.x | CLI framework (`summary`, `list`, `show`, `draft-pr`) | See rationale |
- picocli generates ANSI-colored help, supports subcommands natively, and has a GraalVM native-image annotation processor (future-proofing).
- JCommander is older and less actively maintained; no subcommand tree support without boilerplate.
- **The JAR dual-mode pattern:** `MANIFEST.MF` declares both `Premain-Class: dev.callisto.Agent` and `Main-Class: dev.callisto.Cli`. When invoked as `-javaagent:callisto.jar`, the JVM calls `premain`. When invoked as `java -jar callisto.jar summary`, it calls `main`. Same JAR, two entry points — this is the standard approach.
## JSON
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Jackson Databind | 2.17.x | JSON serialization/deserialization | See rationale |
- Jackson is the de facto standard in the Java ecosystem (2025). Mature, fast, supports records/sealed classes, extensive annotation support.
- Gson is simpler but lacks `ObjectMapper` configurability needed for clean null handling, date formats, and pretty-printing bug history files.
- **Do not use JSON-B / Yasson** — adds a CDI-like context that's excessive for file I/O.
- Dependency note: Jackson has transitive deps (`jackson-core`, `jackson-annotations`) — all must be shaded into the fat JAR.
## GitHub API
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| kohsuke/github-api | 1.321+ | GitHub REST API (branch, commit, PR) | See rationale |
- `org.kohsuke:github-api` is the most widely-used Java GitHub client, actively maintained, covers the full REST API surface needed: create branch, push commit (via Git Data API), open PR.
- Alternative: raw HTTP calls via `java.net.http.HttpClient` + Jackson. Viable but rebuilds pagination, auth header management, and rate-limit handling by hand.
- **Do not use JGit** for the GitHub API operations — JGit is for local Git operations; it cannot create PRs or interact with GitHub's REST API.
- Use JGit only if local repo operations (staging, committing) are needed before pushing; for `draft-pr` the GitHub API's Git Data API is sufficient without cloning.
## Build
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Maven | 3.9.x | Build + dependency management | Standard for Java Agent fat JAR |
| maven-shade-plugin | 3.5.x | Fat JAR creation with relocation | Shade + relocate deps to avoid classpath conflicts |
- Relocate Jackson to `dev.callisto.shaded.jackson` and kohsuke to `dev.callisto.shaded.github`. This prevents version conflicts when Callisto is injected into a target app that already uses Jackson 2.x or a different version.
- Set `MANIFEST.MF` entries: `Premain-Class`, `Main-Class`, `Can-Retransform-Classes: true`, `Can-Redefine-Classes: true`.
- **Do not use Gradle** for v1 — Maven Shade's JAR manifest handling for agents is better documented and less error-prone than Gradle Shadow plugin for the dual-entry-point pattern.
## Alternatives Considered
| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Instrumentation | Byte Buddy 1.14.x | ASM 9.x | Too low-level; maintainability cost |
| Instrumentation | Byte Buddy 1.14.x | Javassist | Unmaintained, poor Java 17+ support |
| HTTP client | java.net.http | OkHttp 4.x | Avoidable JAR weight + Kotlin dep |
| CLI | picocli 4.7.x | JCommander | Less active, weaker subcommand support |
| JSON | Jackson 2.17.x | Gson | Less configurable for structured output |
| GitHub | kohsuke/github-api | Raw HTTP | Reimplements auth/pagination/rate-limit |
| Build | Maven + Shade | Gradle + Shadow | Agent MANIFEST less documented in Gradle |
## Sources
- Byte Buddy documentation: https://bytebuddy.net (training knowledge, MEDIUM confidence)
- java.net.http HttpClient: JEP 321, JDK 11+ standard library (HIGH confidence)
- picocli: https://picocli.info (training knowledge, MEDIUM confidence)
- Jackson: https://github.com/FasterXML/jackson (HIGH confidence — ecosystem standard)
- kohsuke/github-api: https://github.com/hub4j/github-api (MEDIUM confidence — verify latest version)
- maven-shade-plugin: https://maven.apache.org/plugins/maven-shade-plugin/ (HIGH confidence)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
