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
- The markdown checker verifies formatting only; it cannot tell whether a sentence is true. Behavioural prose
  must be audited against the code in both languages, including the status codes and boundary semantics behind
  the configuration keys (504 on `server.http.timeout`, 413 on `max-file-size`, 400 for part count and part
  header size through `DecoderException`, 503 from pool rejection, the minimal 500 on response-header overflow,
  dropped connections for `max-connections`, and the `>= 0` / `> 0` guards behind "zero forbids multi-range"
  and "non-positive means unlimited").
- Document the native path and the servlet-bridge path separately. With `spring.web.locale-bind` off, the
  native path returns early from `initContextHolders` and never touches `LocaleContextHolder`; both
  `removeContextHolders` call sites sit behind `if (initContext)`. `spring-web-servlet` overrides
  `initContextHolders` and returns `init || requestAttributes != null`, which is always true there because it
  must also install `RequestContextHolder` - so on a servlet deployment the holder is still reset once per
  request even with the flag off. Enum-valued keys are mapped case-insensitively onto the framework's exact
  constant names, and unknown values are logged and ignored.
- `scripts/check-docs.sh` runs three checkers: `check-docs.py` (links, anchors, encoding and headings),
  `check-doc-symbols.py` (keys, classes and members named in the contract docs really exist) and
  `check-doc-structure.py` (markdown structure). Run it after editing any document.
- Four user-facing documents must stay **paired**: `CHANGELOG.md`, `docs/configuration.md`,
  `docs/compatibility.md` and `docs/breaking-changes.md` each have a mirror under `docs/en/`;
  `check-docs.py` treats a missing side as a defect, so change one side and you change the other.

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

- Source formatting is **explicitly invoked**, never run implicitly:
  `./mvnw -Dformat=true process-sources` (profile `format`) rewrites sources with
  rewrite `RemoveUnusedImports`, formatter (Eclipse style), and sortpom (pom layout,
  4-space indent). impsort is deliberately absent: it configures JavaParser below
  language level 14 and cannot parse this codebase's `instanceof` patterns (Java 16+);
  import order is kept by convention until that is fixed upstream.
- Run it in a **standalone commit** — the first run touches nearly every file — and never
  mix formatting changes into feature commits.
- CI enforces it by rewriting and asserting: the `static-analysis` job runs the profile above and then
  `git diff --exit-code`, failing with the resulting diff if anything changed (fix by running the profile
  locally and committing the result). `formatter:validate` checks formatting only and cannot see the
  unused-import removals the profile also performs. **Run the format profile locally before committing Java
  changes** - the gate's failure reads `File '…' has not been previously formatted`, and compile plus tests
  passing say nothing about it.
- SpotBugs is a hard gate at **Default** (high + medium) through `spotbugs:check`, bound to `verify`.
  Generated sources (JMH `jmh_generated` stubs, the Spring-API mirrors and Spring's CGLIB-generated
  subclasses) are excluded in `spotbugs-exclude.xml` with a written rationale. High-priority findings are
  exempted individually; medium findings are fixed where possible, otherwise reviewed and recorded per class
  (or per class + field) with the reason in the same file. Never delete a guard that a test or a contract needs.
- modernizer is **report-only** and not yet a gate. Flipping `failOnViolations` requires clustering the
  violations by rule, fixing what is real and exempting the rest. A `-q` Maven run suppresses plugin output,
  so a grep over that output is not evidence of a clean result.
- Relax it temporarily with `./mvnw verify -Dspotbugs.threshold=High`.
  The pom references the property (`${spotbugs.threshold}`), so the command line really overrides it;
  a literal value there would win instead and silently ignore `-D`.
- SpotBugs findings are fixed at the cause where possible; the remaining ones are exempted per class (or per
  class + field) with a written rationale in `spotbugs-exclude.xml`. Never delete a guard that a test or a
  contract needs, and never widen an exclusion to silence a family of findings. The medium tier is gated at
  `threshold=Default`, so a regression fails the build.
- A format sweep must be **verified structurally inert**: compare the parsed sources (comments off, imports
  sorted) and confirm no type-body changes and no import additions or removals before committing it.
- Tip: `spotbugs:spotbugs` analyses `target/classes`, so run `compile` (or `test-compile`) in the same
  invocation — otherwise the report still describes the previous build.
- Gotcha: a single module built without `-am` (`./mvnw verify -pl <module>`) is analysed against the
  **installed** upstream jars rather than the reactor, which can report findings the full reactor clears.
  Run the gate from the root, or `install` the upstream module first.
- Gotcha: `threshold` only takes effect at plugin level - inside an `<execution>` configuration it is
  ignored.
- `.gitattributes` declares `* text=auto` (LF in the index; `*.sh` stays LF,
  `*.bat`/`*.cmd` checkout as CRLF), so a CRLF working tree compares clean against the
  LF-based index and line endings never show up as phantom diffs.

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

- The latest published version is `3.5.7` (the next release is planned as `3.5.8`), so the canonical
  invocation is `mvn -Pcompat -Dcompat.oldVersion=3.5.7 verify` (profile `compat`, property-activated):
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

### Workflow Actions

- Every `uses:` is pinned to a commit SHA with a `# vN` comment. Dependabot
  (`.github/dependabot.yml`, weekly, actions-only) updates both. The Maven ecosystem is deliberately
  not enabled: this repository keeps a Spring Boot version matrix on purpose.

### Test Coverage

- `mvn clean test` runs JaCoCo in **every** module (the agent is declared in the root `pom.xml`
  `<build><plugins>`), so E2E modules such as `spring-web-support-test` contribute server-side coverage too.
  At the end of the reactor, the `coverage-aggregate` module (`packaging=pom`, deliberately kept **last** in
  `<modules>`) merges all `jacoco.exec` files into an HTML report at
  `coverage-aggregate/target/site/jacoco-aggregate/index.html`.
- `io/springperf/webtest/**` (test-support controllers and scaffolding) is excluded from instrumentation —
  it is not production code and would only dilute the numbers.
- For the bilingual summary committed at the repo root, run `./scripts/coverage-report.sh`
  (or `.\scripts\coverage-report.ps1`): it runs the **library and E2E modules plus the aggregate module**
  and turns the merged CSV (`coverage-aggregate/target/site/jacoco-aggregate/jacoco.csv`, whose `GROUP`
  column identifies the module) into `coverage-report.md`, stamping the generation time in its header.
  Pass `--skip` / `-SkipTests` to re-aggregate existing data without running Maven (takes seconds).
- Benchmark JFR recordings can be checked for truncated stack traces with
  `spring-web-benchmark/check-jfr-truncation.sh <jfr file or dir>` (non-zero exit when truncation is found);
  see [Benchmarks](docs/en/benchmark.md).

- Tests that guard resource and lifecycle behaviour are reviewed for whether they assert what they claim;
  assertions that only rule out an exception are renamed or strengthened, and bounded polls use explicit
  timeouts rather than passing on a timeout. No `@Disabled` / `@Ignore`, empty catch or `assertTrue(true)`.
- Two gaps are known and deliberate. `BaseWebServerHttpRequest.parameterMap` — the concurrency fix — has no
  test, because a `volatile` visibility bug is not reproducible in a unit test; the fix carries the
  argument instead (`LocaleConfig`'s race does have a regression test,
  `defaultLocaleContextStaysConsistentWithLocaleSetDefault`).

### PR Checklist

- [ ] Code compiles: `mvn clean compile`
- [ ] Tests pass: `mvn clean test`
- [ ] Related tests have been added or updated
- [ ] Public API has JavaDoc
- [ ] No empty catch blocks (except for resource cleanup scenarios)
- [ ] No `System.out.println` debug code
