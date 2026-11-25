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
  framework's choice — e.g. `spring.mvc.publish-request-handled-events` (ours `false`, Boot `true`) and
  `server.http.multipart.max-part-header-size` (ours `8192`, Boot/Tomcat `512B`). This rule covers comments
  and javadoc as well as the manuals.
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
- `scripts/check-docs.sh` also runs `scripts/check-doc-symbols.py`, which checks that the keys, classes and
  members the **contract docs** name actually exist - including keys the code builds by concatenation
  (`"server.ssl." + "enabled"`, `"management.endpoints.web.exposure" + ".include"`), which a plain literal scan
  misses.
  One limit is deliberate and written in the file: it does not check whether a sentence is **true**. Its
  unknown-class rule **is** a gate, carried by an explicit allow-list (`EXTERNAL_TYPES`) whose every entry names
  where the symbol
  comes from - a class defined in a doc snippet, a package this repository imports, or a class found in a local
  jar. When the rule fires on something legitimate, add an entry with its origin - do not widen the rule.
  `member` references whose owner is unknown go through the same rule.
  Files whose job is to list **removed** keys and members (`docs/breaking-changes.md`,
  `docs/en/breaking-changes.md`, `docs/en/changelog.md`) are exempt from those rules in code
  (the root `CHANGELOG.md` is outside the scanner's scope), and `CONTRIBUTING.md`'s japicmp
  section from the member rule.
  Four documents are user-facing and must stay **paired**: `CHANGELOG.md`, `docs/configuration.md`,
  `docs/compatibility.md` and `docs/breaking-changes.md` each have a mirror under `docs/en/`, and
  `check-docs.py` enforces it (a missing side is a defect) - change one side and you change the other.
- The same entry point runs `scripts/check-doc-structure.py`, which checks markdown **structure** rather
  than content: leftover CJK-parenthesis debris (`（ ）`, `（，`), empty inline code spans, table row/column
  mismatches and fully empty columns, unbalanced code fences, and `.java` file references that name no
  tracked file. `.agent/` files other than
  `module.md` are out of scope for the file-existence rule, matching `check-doc-symbols.py`: backport
  checklists exist to name files that only the other branch has.

### Coverage and Leak Gates

- `scripts/check-coverage.py` is a gate, not a report. It reads the merged CSV that `coverage-aggregate`
  produces in the `test` phase (`report-aggregate`, so E2E-driven coverage is included) and fails when
  the totals fall below **92% line / 83% branch**, or when any library module drops below its floor
  (`spring-web` 90%, the rest 80%). The floors sit 1.6-1.9 points under the measured 93.6% / 84.9%, so
  ordinary noise does not redden a PR while a real regression does. A module **missing** from the CSV is
  a defect too - an empty or partial aggregation must not pass silently. CI runs it in the `build` job
  on JDK 17 only (one judgement over one dataset; the other matrix legs are for compatibility) and
  uploads the CSV as an artifact.
- E2E modules (`spring-web-test`, `spring-web-support-test`) set `rerunFailingTestsCount=1`: they bind
  real ports and depend on network timing, so a single flake should not fail a PR. Retries stay visible
  rather than silent - surefire records `Flaky:` in the report and the `build` job prints every such
  report as a warning. Unit-test modules deliberately do **not** retry.
- The `netty-leaks` job runs `scripts/check-netty-leaks.sh --run --online --clean 1` with
  `-Dio.netty.leakDetection.level=paranoid` (plus `targetRecords=16`) and fails on any `LEAK: ByteBuf`
  report. The script treats a missing paranoid property as a warning so it stays usable offline; the job
  re-checks it with `grep -q` to confirm the detector actually ran. Use `--online` in CI: the default
  `-o` requires a populated local Maven repository.

### Version Matrix, Architecture and Mutation

- The `spring-boot-versions` job runs the core module's **tests** on Spring Boot 3.2 / 3.3 / 3.4.
  Tests rather than `compile` is deliberate: a version switch changes behaviour (the
  `ServerSentEvent` newline check only appeared in a 6.2 patch, and `SseEmitter` now guards it itself)
  and surfaces missing runtime symbols that `javac` cannot see. 3.0 / 3.1 are out of scope - see
  `docs/compatibility.md` for the measured reason.
- `ArchitectureRulesTest` (`spring-web-test`, ArchUnit) turns four documented promises into assertions:
  core does not depend on `jakarta.servlet` or `org.springframework.web.servlet`; the request hot path
  (`core.mapping`, `core.arg.resolver`, `core.retval`) contains no `Method.invoke`; and `util` does not
  depend on `core`/`server`/`context`. These are exactly the constraints Maven's dependency graph
  cannot express.
- `.github/workflows/mutation.yml` runs PIT weekly over the same three hot packages (`-Pmutation`),
  report-only. The mapping package alone produces ~435 mutants in about a minute; survived mutants need
  human triage before a threshold means anything.
- The `build` job also emits an aggregate SBOM (`target/bom.json`) as an artifact. It is for delivery
  traceability only; released artifacts are published to Maven Central (see the `compat` baseline below).

### Metric and Sync Contracts

- `MetricContractTest` freezes the metric **names and tag keys** of `MicrometerWebMetrics` and
  `MicrometerBatchMetrics` as two whitelists (web: 6 meters, batch: 9). The per-metric tests stay - they
  assert values; this one asserts the set, so *adding* a metric without a test is now a failure too.
  Changing a name or tag key means editing the whitelist on purpose.
- The javadoc job also compiles `io.springperf.web.annotation` with `-Xdoclint:all -Werror`. Excluding
  `missing` for the whole reactor is deliberate (100+ warnings live in internal classes), but the
  annotation package is what users type in their own code, so missing `@param`/`@return` there is an
  error. The single-package check is a plain `javadoc` invocation because the Maven plugin has no
  "one subpackage" switch.
- `scripts/sync-audit.py --target <branch> --whitelist <file>` produces a branch-sync report with
  three sections: files only on the base branch, files only on the target branch, and same-name files
  with line deltas. It reports differences; it never decides "must sync" — same-name files legitimately
  differ between branches because of adaptation rewrites. The whitelist holds version-boundary paths
  only (native-image, Spring AI, virtual threads, Thymeleaf WebExchange); feature gaps must not be
  whitelisted. See the script's `--output` / `--whitelist` options for the report location.

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

Practices for verifying claims and gates before trusting them.

- **Trace a claim to the code that decides it, not to where it is cheapest to read.** A statement about
  behaviour has to be checked at the point that controls it, including its call sites. Reading
  `removeContextHolders` on its own says the locale holder is reset even with `spring.web.locale-bind` off;
  its only call site sits behind `if (initContext)`, and `initContextHolders` returns false when the flag is
  off, so on the native path the holder is untouched and the original wording was right. Reading the method was
  not enough - the guard lives in `handleAfterFilter`. The servlet path is a genuine exception on top of that,
  because `SupportDispatcherHandler` returns `init || requestAttributes != null` and therefore always reports
  true there.
- **Finish edits before starting a build.** A build started while files are still being written can read a
  mixed state; land the changes first, then build.
- **Prove a new gate can fail before trusting it.** A check that has never been seen red is a claim, not a
  check: introduce a deliberate change, confirm the gate reports failure, then revert it.
- **A reactor-wide red is a hypothesis until it is isolated.** Re-run the failing class alone, then the
  module alone, and read the error text before theorising; isolated failures and environment-specific errors
  need different explanations.
- **Re-read the file or the tree; do not work from a summary or from memory.** A remembered "known issue"
  may already be fixed. A summary is a hypothesis about the tree, and the tree is one command away.
- **Know which checker covers what.** They are not interchangeable. `formatter:validate` does not see the
  unused-import removals the format profile performs; doclint rejects a `ul` inside a `p` that both the
  formatter and the format assertion accept; the markdown checker verifies formatting and links but never
  whether a sentence is true; and `check-doc-symbols.py` only checks that a named symbol exists.
- **After a deliberate mutation, revert it immediately.** Breaking a guard on purpose proves a test is not
  vacuous, but the broken state must not outlive the run; read the reverted lines back afterwards.

### Binary Compatibility (japicmp)

- The latest published version is `2.7.7` (the next release is planned as `2.7.8`), so the canonical
  invocation is `mvn -Pcompat -Dcompat.oldVersion=2.7.7 verify` (profile `compat`, property-activated):
  it compares each module's freshly built jar against that released artifact. pom-packaging modules are
  skipped automatically, and three modules have **no baseline at all** on purpose: `spring-web-test`,
  `spring-web-support-test` and `spring-web-benchmark` set `maven.deploy.skip=true`, so nothing was ever
  published for them. The remaining seven published modules - spring-web, spring-web-servlet,
  spring-web-websocket, spring-web-mvc-support, spring-boot-starter-web, spring-web-view, spring-web-batch
  - are the full sweep.
- **Local jars can shadow the released artifact**: when a version is already installed in the local
  repository, Maven may resolve that copy instead of the published jar and compare the build with itself.
  Use `compat-file` with a jar fetched into an isolated repository to bypass repository resolution:

  ```bash
  # fetch the released jar through an isolated local repo, so the shadowing copy stays untouched
  ./mvnw -q dependency:copy -Dartifact=io.github.springperf:spring-web:3.5.6:jar \
      -Dtransitive=false -DoutputDirectory=_baseline -Dmaven.repo.local=_m2tmp
  ./mvnw -Pcompat-file -Dcompat.oldJar="$PWD/_baseline/spring-web-3.5.6.jar" verify -DskipTests
  ```

- **The `compat` profile reports; it does not fail**: with japicmp 0.26.2, the `Treat changes as errors`
  block reads `No` for every category (any / binary / source / semantic), so a MAJOR-incompatible delta
  still ends in BUILD SUCCESS. To turn it into a real gate, set one of
  `breakBuildBasedOnSemanticVersioning`, `breakBuildOnBinaryIncompatibleModifications`,
  `breakBuildOnSourceIncompatibleModifications` or `breakBuildOnModifications` inside its
  `<parameter>` block (parameter names are taken from the plugin descriptor).
- breaking-changes.md records the intentional breaks; the japicmp report is its machine-checkable
  counterpart — every reported failure must be reviewed (fix it, or record it there), never silenced.

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
  see [Benchmarks](docs/en/benchmark.md).

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
  The invariant-bearing tests (those guarding resource and lifecycle behaviour) were reviewed in depth;
  the remaining files were covered by the smell sweep.

### PR Checklist

- [ ] Code compiles: `mvn clean compile`
- [ ] Tests pass: `mvn clean test`
- [ ] Related tests have been added or updated
- [ ] Public API has JavaDoc
- [ ] No empty catch blocks (except for resource cleanup scenarios)
- [ ] No `System.out.println` debug code
