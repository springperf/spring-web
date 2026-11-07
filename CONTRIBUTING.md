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
  re-checks it with `grep -q`, because "zero leaks" must not come from a detector that never ran. Use
  `--online` in CI: the default `-o` fails on a cold local repository, and that failure is not a leak
  verdict.

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
  traceability only - this project does not publish to Maven Central.

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
- `scripts/sync-audit.py --target 2.7.x --whitelist <local list>` produces a branch-sync report with
  three sections: files only on the base branch, files only on the target branch, and same-name files
  with line deltas. It never decides "must sync" - 505 same-name files differ between `master` and
  `2.7.x` simply because adaptation rewrites are normal. The whitelist holds version-boundary paths
  only (native-image, Spring AI, virtual threads, Thymeleaf WebExchange); feature gaps must not be
  whitelisted. Reports and whitelists are local, untracked artifacts — see the script's
  `--output` / `--whitelist` options for where to put them.

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
- Medium inventory: **0**. The main families and their resolution:
  `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` **230 -> 0**: 33 findings genuinely fixed (defensive copies and
  read-only wrappers for caller-owned configuration and framework-built collections), the rest
  recorded per class with the reason - the framework hands its own infrastructure objects to user
  code and adapters, registers extension points by sharing them, returns what Servlet/JSR contract
  demands, or exposes reflection handles that cannot be copied.
  `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` **51 -> 0**: three rounds, every finding fixed at the cause
  rather than suppressed. Two recurring shapes: (a) "check then call the getter again"
  (`if (x.getY() != null) { x.getY() ... }`, or `hasParameterAnnotation(a)` before
  `getParameterAnnotation(a)`) - now a single call whose value is null-checked, which the analyser can
  follow and which also removes duplicate reflection lookups on hot paths; (b) a genuinely nullable
  result dereferenced directly (`Path.getFileName()`, `MultiValueMap.get()`,
  `ResolvableType.resolve()`, `ResponseEntity.getBody()`, OkHttp `Response.body()`,
  `URI.getScheme()/getPath()`, `ControllerAdviceBean.getBeanType()`,
  `BeanDefinition.getBeanClassName()`, `getParameterAnnotation()`) - now guarded, with the no-value case
  mapped to the semantics the call site needs (empty array, empty map, early return, or a clear
  exception).
- `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE` **28 -> 0**. Root cause of most of them: `WebServerHttpRequest`
  extends Spring's `ServerHttpRequest` from the jar, so the contract of
  `getURI` / `getRemoteAddress` / `getLocalAddress` was invisible to the analyser, and its two null
  detectors then assumed opposite things about the same call. Redeclaring those three methods in our own
  interface with the real contract (`@NonNull` URI, `@Nullable` addresses) made the guards in that
  interface necessary instead of redundant. Six further guards must stay - one is demanded by its own test
  (`JsrEndpointScannerTest#scan_beanNamesForAnnotationNull_returnsEmpty`), the others protect
  `protected` non-final methods replaced by spies in tests or come from a third-party producer - so they
  are excluded per class with the reason in `spotbugs-exclude.xml`, never deleted.
  Annotations must be placed on the **overrides the analyser resolves** (`NettyServerHttpRequest` and
  `WebServerHttpRequestWrapper`), not only on the interface method. Verify with the gate itself
  (`spotbugs:check`); a report XML that is still being written is not evidence of a green gate.
- `VA_FORMAT_STRING_USES_NEWLINE` **28 -> 0**: `\n` became `%n` in the 28 `printf` format strings of
  `ReportGenerator` (verified as a pure substitution; tests were checked first because the report text is
  asserted on).
- `CT_CONSTRUCTOR_THROW` **20 -> 0** across 13 classes, each inspected individually: ten constructors only
  validate arguments and assign fields; three call an overridable method from the constructor (nothing
  overrides them in this repository, so the risk stays theoretical); `FileHttpSessionStorage` starts its
  cleanup thread after every field is assigned, which its exclusion says explicitly so that a future
  subclass triggers the refactor.
- `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` **230 -> 0**: 44 findings genuinely fixed (defensive copies and read-only
  views), the rest recorded per class **and per field** with the reason. The first pass exempted whole
  classes, which a review showed was too broad - it also swallowed getters returning collections the class
  builds itself - so the exemptions were narrowed to class+field and 11 more getters fixed. Three stay
  exempt because their tests `assertSame` the same live instance, and those exclusions name the tests.
- The concurrency family (`AT_NONATOMIC_64BIT_PRIMITIVE` 7, `AT_STALE_THREAD_WRITE_OF_PRIMITIVE` 5,
  `IS2_INCONSISTENT_SYNC` 3): one real fix (`BaseWebServerHttpRequest.parameterMap` was double-checked
  locking without `volatile`), the rest exempted per class with the JMM argument - configuration snapshots
  written by the starting thread before any request is served, so the write happens-before the reader
  threads by the `Thread.start` rule.
- The gate runs at `threshold=Default`: the medium tier reached zero, so gating on it is signal, not noise.
- A format sweep must be **verified structurally inert**: parsed
  with a Java parser (comments off, imports sorted) it shows 785 files identical, 359 differing only in
  import order, **0** type-body changes and **0** import additions or removals. The 22 `pom.xml` files did
  gain the canonical XML declaration - a real but harmless non-format change worth knowing about.
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
- **Never edit files and start a build in the same batch.** An edit issued alongside a build can land after
  the compiler has read the file, and the result describes a state that never existed. Land the edit, then build.
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
- **After a deliberate mutation, revert it in the same breath, and never through a command that can be
  blocked.** Breaking a guard on purpose is the only way to prove a test is not vacuous, so it is worth
  doing - but the broken state looks like ordinary code and can outlive the run that created it. Revert with
  the edit tool rather than a git command, and read the reverted lines back afterwards; that check is cheap.

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
  The usual false-assurance smells are absent: no `@Disabled`/`@Ignore` in any of the 559 test files, no empty
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
