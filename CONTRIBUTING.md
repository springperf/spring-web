# Contributing Guide

Thank you for your interest in Spring WebPerf! We welcome all forms of contribution — reporting bugs, proposing features, improving documentation, or submitting code.

## Code of Conduct

Please read and follow our [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## How to Contribute

### Reporting Bugs

1. Search [Issues](https://github.com/springperf/spring-web/issues) to check if the issue already exists
2. If not, create a new Issue and select the Bug Report template
3. Please include:
   - Environment (JDK version, OS)
   - Steps to reproduce
   - Expected and actual behavior
   - Relevant logs or stack traces

### Proposing New Features

1. Search [Issues](https://github.com/springperf/spring-web/issues) for similar proposals
2. Create a Feature Request Issue describing:
   - Use case
   - Expected API or behavior
   - Whether you are willing to participate in implementation

### Submitting Code

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your code:
   - Follow the existing code style
   - Add JavaDoc (in English) for public API interfaces
   - Add tests for new functionality
   - Ensure `mvn clean test` passes
4. Submit a Pull Request
5. Wait for Code Review

### Code Style

- Java 8 compatibility (2.7.x branch) / Java 17+ (master branch)
- Follow Spring Framework naming conventions
- Public API must have English JavaDoc
- Chinese comments may be used for complex business logic explanations
- Package naming: `io.springperf.web.*` (Maven groupId: `io.github.springperf`)
- Claims of alignment with Spring Boot or Tomcat are **verified against the source of truth**, never written from
  memory and never copied from an existing comment: the defaults live in
  `META-INF/spring-configuration-metadata.json` inside `spring-boot-autoconfigure-<version>.jar` (read the key
  there, not from a comment). When the values differ, write **both** numbers and say plainly that it is this
  framework's choice. "Aligned with Boot" printed next to a value Boot does not use is the one wording mistake
  this repository keeps making, twice so far: `spring.mvc.publish-request-handled-events` (ours `false`, Boot
  `true`) and `server.http.multipart.max-part-header-size` (ours `8192`, Boot/Tomcat `512B`). The second sat in a
  **code comment**, so this rule covers comments and javadoc, not just the manuals.
- "Semantically aligned with `<Boot key>`" is allowed only when it names the *meaning* of the key **and** the
  differing default stays visible in the same sentence (`server.http.multipart.max-part-count`: our `-1` and
  Boot's `50` both mean "non-positive disables the cap"). When the claim is about an idiom rather than a value,
  name the counterpart instead of gesturing at it: `spring.web.locale-bind` says it follows Spring MVC's
  `initContextHolders`/`resetContextHolders` pattern (save, set, reset, honouring `threadContextInheritable`),
  which is exactly what `SupportDispatcherHandler` implements - a reader can check that, and cannot check
  "aligns with Spring MVC".
- The markdown checker verifies formatting only; it cannot tell whether a sentence is true. Behavioural prose is
  audited against the code (the last pass covered 21 keys in both languages: 504 on `server.http.timeout`, 413 on
  `max-file-size`, 400 for part count and part header size through `DecoderException`, 503 from pool rejection,
  the minimal 500 on response-header overflow, dropped connections for `max-connections`, and the `>= 0` / `> 0`
  guards behind "zero forbids multi-range" and "non-positive means unlimited").
- Two narrative claims were checked against their sources instead of being trusted. The `same-site` row says an
  early version upper-cased the value before handing it to Netty's enum, so `strict` threw; history agrees -
  `00231995` added `configuredSameSite.toUpperCase()` and `ad24377e` removed it - and the current code maps the
  value case-insensitively onto Netty's exact constant names (`Lax`/`Strict`/`None`, deliberately not
  upper-case) and logs and ignores unknown values. The `locale-bind` row says that with the key off the framework
  never touches `LocaleContextHolder` and that an application setting it itself must clean up; the code returns
  early from `initContextHolders` and both `removeContextHolders` call sites sit behind `if (initContext)`.
  That holds on the native path, but `spring-web-servlet` overrides `initContextHolders` and returns
  `init || requestAttributes != null`, which is always true there because it must also install
  `RequestContextHolder` - so on a servlet deployment the holder is still reset once per request even with the
  flag off. An earlier wording of the constant and of both manuals generalised the native behaviour to both
  paths; they now spell out the two separately.
  Spring's own source (`spring-context-6.2.19-sources.jar`) confirms the last part of that row: `getLocale()`
  falls back to the system default when no context is bound, "a replacement for `Locale.getDefault()`".
- The contract-doc checker (`python scripts/check-docs.py` + `python scripts/check-doc-symbols.py`) is
  ported to this branch and is run against this tree (72 markdown files, 0 defects; the symbol checker
  carries a small branch-specific allow-list for the `master` section of `docs/(en/)compatibility.md`).
  It is how the docs that called `ModelContext` by the name `ModelSupport` were found.
  Its rules are worth remembering: it checks that the keys, classes and members the **contract docs** name
  actually exist (including keys the code builds by concatenation), and that paired documents stay paired.
  It never checks whether a sentence is *true* — only the code does that.
- Four documents are user-facing and must stay **paired**: `CHANGELOG.md`, `docs/configuration.md`,
  `docs/compatibility.md` and `docs/breaking-changes.md` each have a mirror under `docs/en/` —
  change one side and you change the other.
### Formatting

- This branch does **not** carry master's `format` profile (rewrite `RemoveUnusedImports`, Eclipse-style
  formatter, sortpom), nor the SpotBugs / modernizer gates: they belong to master's JDK 17+ toolchain and are
  deliberately not ported (JDK 8 build cost, and the exemption files are calibrated against master's source set).
  Do not port one of them in isolation.
- Keep the conventions by hand: 4-space indent, no trailing whitespace, LF in the index.
  `.gitattributes` declares `* text=auto` (LF in the index; `*.sh` stays LF, `*.bat`/`*.cmd` checkout as CRLF),
  so a CRLF working tree compares clean against the LF-based index and line endings never show up as phantom diffs.

### Static Analysis & Binary Compatibility

- Gates that **do** run on this branch:
  - `./mvnw clean test` — the primary gate (library + E2E modules; the JaCoCo agent is declared in the root pom).
    The Maven wrapper is ported from master (`distributionType=only-script`, Maven 3.9.9 pinned by
    `distributionSha256Sum`), so CI and local runs resolve the same Maven; plain `mvn` still works but is unpinned.
  - `requirePluginVersions` (maven-enforcer-plugin, bound to `validate`) — rejects `LATEST` / `RELEASE` /
    `SNAPSHOT` plugin versions. Only that rule is ported: master's `requireJavaVersion [17,)` and
    `enforceBytecodeVersion` max 17 contradict this branch's JDK 8 target.
  - `./mvnw javadoc:javadoc -Ddoclint=all,-missing` — master's doclint caliber, run on JDK 8. Javadoc counts as a
    documentation compilation here: broken links and tags fail the build. JDK 8 files "no description for
    @param/@return" under the *syntax* group, which `-missing` cannot exclude — those stay warnings, not errors.
  - `python scripts/check-docs.py` / `python scripts/check-doc-symbols.py` — the contract-doc checkers
    (ported from master; they carry no JDK-8-hostile toolchain dependency).
  - `./scripts/check-netty-leaks.sh <log-or-dir>` / `--run [--clean] [rounds]` (or the Windows twin
    `.\scripts\check-netty-leaks.ps1 -Paths <log-or-dir>` / `-Run [-Rounds N]`) — scans run logs for Netty
    `ByteBuf` leak reports, or runs the paranoid test pass itself.
  - `./scripts/coverage-report.sh` (or `.\scripts\coverage-report.ps1`) — the bilingual `coverage-report.md` summary.
  - `spring-web-benchmark/check-jfr-truncation.sh <jfr file or dir>` — benchmark JFR stack-depth sanity check.
- **Not** ported from master: SpotBugs (`spotbugs:check`), modernizer, japicmp (`-Pcompat` / `-Pcompat-file`),
  the `format` profile and `scripts/native-smoke-test.sh` (native-image toolchain).
- Intentional incompatibilities are recorded **by hand** in [docs/breaking-changes.md](docs/breaking-changes.md):
  this branch has no machine gate that produces that list (master uses japicmp for it).
### Verification Discipline

Handbook of habits that were paid for. Each one cost this repository a wrong statement or a wasted run, so each
one carries the instance that produced it.

- **Trace a claim to the code that decides it, not to where it is cheapest to read.** A statement about
  behaviour has to be checked at the point that controls it, including its call sites. Reading
  `removeContextHolders` on its own says the locale holder is reset even with `spring.web.locale-bind` off;
  its only call site sits behind `if (initContext)`, and `initContextHolders` returns false when the flag is
  off, so on the native path the holder is untouched and the original wording was right. Reading the method was
  not enough - the guard lives in `handleAfterFilter`. The servlet path is a genuine exception on top of that,
  because `SupportDispatcherHandler` returns `init || requestAttributes != null` and therefore always reports
  true there.
- **Never edit files and start a build in the same batch.** An edit issued alongside a build can land after
  the compiler has read the file, and the result describes a state that never existed. This happened twice in
  one session; both runs had to be thrown away and repeated serially. Land the edit, then build.
- **Prove a new gate can fail before trusting it.** A check that has never been seen red is a claim, not a
  check. The format assertion added to `static-analysis` was validated by appending one blank line to
  `docs/configuration.md` and watching it report failure, then reverting. It then earned its place on the
  first real run, catching a javadoc rewrap that `formatter:validate` had passed.
- **A red on this machine is a hypothesis until it is isolated.** Re-run the failing class alone, then the
  module alone, and read the error text before theorising. Measured: two reactor-only failures, twenty-eight
  cascading timeouts in `AsyncSseRobustnessE2eTest` and one reset in `ChunkedRequestE2eTest`, were both green
  in isolation - and the second carried a Windows-only error code, so it is not a Linux CI risk at all.
- **Re-read the file or the tree; do not work from a summary or from memory.** Three times in one session a
  remembered "known issue" turned out to be already fixed (`check-doc-symbols.py` reports unknown owners of
  `Class.member` now, and the `CONTRIBUTING` entry about that rule had already been corrected). Memory of a
  compacted history is a hypothesis about the tree, and the tree is one command away.
- **Know which checker covers what.** They are not interchangeable. `formatter:validate` does not see the
  unused-import removals the format profile performs; doclint rejects a `ul` inside a `p` that both the
  formatter and the format assertion accept; the markdown checker verifies formatting and links but never
  whether a sentence is true; and `check-doc-symbols.py` only checks that a named symbol exists.
- **After a deliberate mutation, revert it in the same breath, and never through a command that can be
  blocked.** Breaking a guard on purpose is the only way to prove a test is not vacuous, so it is worth
  doing - but the broken state looks like ordinary code and can outlive the run that created it. That
  happened here: the revert was chained behind a command whose permission prompt timed out, so a mutated
  `batchBuf.release()` stayed in the working tree and was caught only by re-reading the file. Revert with the
  edit tool rather than a git command, and read the reverted lines back afterwards; that check is cheap.

### Binary Compatibility

- There is **no** binary-compatibility gate on this branch: the `compat` / `compat-file` profiles and the
  japicmp plugin live in master's `pom.xml` and are not ported. Upgrades are therefore validated by
  [docs/breaking-changes.md](docs/breaking-changes.md) (hand-maintained) plus `mvn clean test`.
- When master reports a break through japicmp, the corresponding entry is written into this branch's
  `docs/breaking-changes.md` as part of the backport, with the migration action spelled out.
### Workflow Actions

- `.github/workflows/ci.yml` is master's workflow set adapted to this branch:
  - `build` — `./mvnw clean test` over the JDK matrix (8/11/17 here, 17/21/25 on master), with
    `fail-fast: false` so one JDK combination cannot hide the others.
  - `docs` — `bash scripts/check-docs.sh`. Master pins its own copy to `refs/heads/master` with a job-level
    `if`, so a verbatim copy would never run here; the `if` admits both `master` and `2.7.x`.
  - `javadoc` — installs the reactor, then runs `javadoc:javadoc -Ddoclint=all,-missing` on JDK 8.
    `javadoc:javadoc` is a direct goal invocation, so it resolves module artifacts from the local repository.
- `.github/workflows/codeql.yml` and `.github/dependabot.yml` are ported as-is. Dependabot deliberately
  covers `github-actions` only: the Spring Boot version matrix is maintained on purpose, so automated
  `maven` bumps would fight it.
- Actions are pinned by commit SHA (`actions/checkout`, `actions/setup-java`, `actions/upload-artifact`) —
  that is what Dependabot maintains.
### Test Coverage

- `./mvnw clean test` runs JaCoCo in **every** module (the agent is declared in the root `pom.xml`
  `<build><plugins>`), so E2E modules such as `spring-web-support-test` contribute server-side coverage too.
  Per-module HTML/CSV land in `<module>/target/site/jacoco/`; the `coverage-aggregate` module (ported from
  master, listed last in the root `<modules>`) runs `jacoco:report-aggregate` to merge them.
- `io/springperf/webtest/**` (test-support controllers and scaffolding) is excluded from instrumentation —
  it is not production code and would only dilute the numbers.
- For the bilingual summary committed at the repo root, run `./scripts/coverage-report.sh`
  (or `.\scripts\coverage-report.ps1`): it runs the library and E2E modules, lets the `coverage-aggregate`
  module merge the `jacoco.exec` files, then reads the merged
  `coverage-aggregate/target/site/jacoco-aggregate/jacoco.csv` into `coverage-report.md`, so coverage driven
  only by E2E tests is counted too. It stamps the generation time in its header.
  Pass `--skip` / `-SkipTests` to re-aggregate existing data without running Maven (takes seconds).
- Benchmark JFR recordings can be checked for truncated stack traces with
  `spring-web-benchmark/check-jfr-truncation.sh <jfr file or dir>` (non-zero exit when truncation is found);
  see [Benchmarks](docs/benchmark.md).

- The invariant-bearing tests were audited for whether they assert what they claim. Mostly they do; the 128
  `assertDoesNotThrow` sites turned up seven where the name promised a result and the body only ruled out an
  exception - three `SslContextFactoryTest` cases now assert the context is non-null (as their sibling tests
  already did) and four were renamed to `doesNotThrow` because the promised effect is not observable through
  the public API (`BeetlViewResolver` exposes no initialised state; the empty-route warning is
  implementation-side; the bridged-callback case has no registry getter). The swallow-size
  E2E drives a raw socket and asserts the connection is really gone - a read that times out becomes an explicit
  `AssertionError` rather than a pass - and covers the boundary where the body equals the limit, so `>` versus
  `>=` cannot pass by accident. The async/SSE lifecycle E2E reads a per-context counter
  (`CountingWebMetrics#activeAsyncLifecycles()`, resolved through the `WebMetrics` component the test registers;
  the counter used to be a JVM-wide static, which coupled unrelated suites) and requires it to fall back to zero
  inside a bounded poll, so a leaked holder fails instead of being collected
  later, and one case aborts an idle stream where only `channelInactive` can release. The fast-path parameter
  lookup test asserts the limit cannot be bypassed, and the `maxInactiveInterval` regression is pinned by name.
  The usual false-assurance smells are absent: no `@Disabled`/`@Ignore` in any of the 562 test files, no empty
  catch, no `assertTrue(true)`. The 48 `Thread.sleep` calls are bounded polls or semantically required waits
  (a 1 s session expiry waits 1.6 s, ETag granularity waits 1.1 s, the slow producers live in test fixtures).
- Two gaps are known and deliberate. `BaseWebServerHttpRequest.parameterMap` - the one real concurrency fix -
  has no test, because a `volatile` visibility bug is not reproducible in a unit test; the fix carries the
  argument instead (`LocaleConfig`'s race does have a regression test, `defaultLocaleContextStaysConsistentWithLocaleSetDefault`).
  And the audit read the invariant-bearing tests rather than all 562: the sweep covered the smells, the deep
  read covered the ones guarding resource and lifecycle behaviour.

### PR Checklist

- [ ] Code compiles: `mvn clean compile`
- [ ] Tests pass: `mvn clean test`
- [ ] Related tests have been added or updated
- [ ] Public API has JavaDoc
- [ ] No empty catch blocks (except for resource cleanup scenarios)
- [ ] No `System.out.println` debug code
