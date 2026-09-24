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
  Spring's own source (`spring-context-6.2.19-sources.jar`) confirms the last part of that row: `getLocale()`
  falls back to the system default when no context is bound, "a replacement for `Locale.getDefault()`".
- `scripts/check-docs.sh` also runs `scripts/check-doc-symbols.py`, which checks that the keys, classes and
  members the **contract docs** name actually exist - including keys the code builds by concatenation
  (`"server.ssl." + "enabled"`, `"management.endpoints.web.exposure" + ".include"`), which a plain literal scan
  misses: the first run reported 34 such keys, all of them false alarms for exactly that reason.
  One limit is deliberate and written in the file: it does not check whether a sentence is **true**. Its
  unknown-class rule **is** a gate, carried by an 81-entry allowlist whose every entry names where the symbol
  comes from - a class defined in a doc snippet, a package this repository imports, or a class found in a local
  jar, with the measured name collisions called out (the `ServiceLoader` hit was surefire, the
  `ExceptionHandler` import was disruptor, and `HandlerExecutionChain` was in no local jar at all because
  `spring-webmvc` is not on this machine). The triage that built the list is what justified the gate: it is how
  the docs calling `ModelContext` by the name `ModelSupport` was found. When the rule fires on something
  legitimate, add an entry with its origin - do not widen the rule. `member` references whose owner is unknown
  go through the same rule, which closes the gap that let `ModelSupport.getOrCreate()` pass.
  Files whose job is to list **removed** keys and members (`docs/BREAKING-CHANGES.md`, `docs/(en/)changelog.md`)
  are exempt from those rules in code, and `CONTRIBUTING.md`'s japicmp section from the member rule.

### Formatting

- Source formatting is **explicitly invoked**, never run implicitly:
  `./mvnw -Dformat=true process-sources` (profile `format`) rewrites sources with
  rewrite `RemoveUnusedImports`, formatter (Eclipse style), and sortpom (pom layout,
  4-space indent). impsort is deliberately absent: it configures JavaParser below
  language level 14 and cannot parse this codebase's `instanceof` patterns (Java 16+);
  import order is kept by convention until that is fixed upstream.
- Run it in a **standalone commit** — the first run touches nearly every file — and never
  mix formatting changes into feature commits.
- CI enforces it without rewriting: the `static-analysis` job runs `formatter:validate`, which
  fails if any file is unformatted (fix by running the profile above and committing the result).
  **Run that validate locally before committing Java changes.** Measured: a series of javadoc and
  comment edits went in over five commits on a verification loop of `compile` + `javadoc` + the docs
  checker, which never ran the formatter, so the gate would have failed on `PropertiesConstant.java`
  and nine other files; the failure reads
  `File '…' has not been previously formatted`. Compile and tests passing say nothing about it.
- SpotBugs is a hard gate at **Default** (high + medium) through `spotbugs:check`, bound to `verify`.
  The first full scan reported 2745 findings repo-wide; 2375 of them come from JMH `jmh_generated`
  stubs, the Spring-API mirrors and Spring's CGLIB-generated subclasses, which `spotbugs-exclude.xml`
  excludes with a written rationale.
  The 10 high-priority findings were reviewed one by one and are exempted individually, and the whole
  medium tier was then burned down from 416 to 0 - fixed where fixable, otherwise reviewed and
  recorded per class, each with its reason in the same file.
- modernizer is **report-only** and not yet a gate: a real run reports 331 violations repo-wide (an
  earlier "0 violations" reading of mine was wrong - the `-q` flag had suppressed the plugin output,
  so the grep found nothing and I mistook that for a clean result). Flipping `failOnViolations` needs
  the same treatment the SpotBugs backlog got: cluster by rule, fix what is real, exempt the rest.
- Relax it temporarily with `./mvnw verify -Dspotbugs.threshold=High`.
  The pom references the property (`${spotbugs.threshold}`), so the command line really overrides it;
  a literal value there would win instead and silently ignore `-D`.
- Medium inventory: **0**. How each family ended:
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
  **Correction (later review)**: the count above was published as zero but seven findings survived in
  `NettyHttpServletRequest`, and the interface annotation does not reach them, because the analyser reads
  the annotation on the declaration it resolves - the **overrides** in `NettyServerHttpRequest` and
  `WebServerHttpRequestWrapper` - not the interface method. Annotating those four overrides is what actually
  cleared the family; the gate now passes at `Default` with zero findings. Why the earlier verification
  missed it: the report XML was read while a background build was still writing it, so a partially
  written file looked clean. Only the gate itself (`spotbugs:check`) answers "is the gate green".
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
- The 1093-file format sweep (`019eafe3`) was **verified structurally inert** rather than assumed: parsed
  with a Java parser (comments off, imports sorted) it shows 785 files identical, 359 differing only in
  import order, **0** type-body changes and **0** import additions or removals. The 22 `pom.xml` files did
  gain the canonical XML declaration - a real but harmless non-format change worth knowing about.
- Tip: `spotbugs:spotbugs` analyses `target/classes`, so run `compile` (or `test-compile`) in the same
  invocation — otherwise the report still describes the previous build.
- Gotcha: a single module built without `-am` (`./mvnw verify -pl <module>`) is analysed against the
  **installed** upstream jars rather than the reactor. Measured: `verify -pl spring-web-servlet` reported
  the seven `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE` findings in `NettyHttpServletRequest` that the full
  reactor clears, because the `@Nullable` on `NettyServerHttpRequest`'s overrides existed only in the
  working tree, not in the `spring-web` jar Maven resolved from the repository. Run the gate from the root,
  or `install` the upstream module first.
- Gotcha: `threshold` only takes effect at plugin level - inside an `<execution>` configuration it is
  ignored (measured: the check still failed with 180 findings instead of 0).
- `.gitattributes` declares `* text=auto` (LF in the index; `*.sh` stays LF,
  `*.bat`/`*.cmd` checkout as CRLF), so a CRLF working tree compares clean against the
  LF-based index and line endings never show up as phantom diffs.

### Binary Compatibility (japicmp)

- The latest published version is `3.5.6` (the next release is planned as `3.5.7`), so the canonical
  invocation is `mvn -Pcompat -Dcompat.oldVersion=3.5.6 verify` (profile `compat`, property-activated):
  it compares each module's freshly built jar against that released artifact. pom-packaging modules are
  skipped automatically, and three modules have **no baseline at all** on purpose: `spring-web-test`,
  `spring-web-support-test` and `spring-web-benchmark` set `maven.deploy.skip=true`, so nothing was ever
  published for them (`dependency:copy` fails with "Unable to find/resolve artifact", and so does the
  comparison). Covering the seven published modules - spring-web, spring-web-servlet, spring-web-websocket,
  spring-web-mvc-support, spring-boot-starter-web, spring-web-view, spring-web-batch - is the full sweep. **Do not use that form on a machine that has this version installed locally** —
  this one has: `D:\maven\repository\...\spring-web\3.5.6` holds a 501 KB locally built jar while the
  published one is 419 KB (different sha256), and repository resolution picks the local copy, silently
  comparing the current build with itself. On such a machine use `compat-file` with a jar fetched into an
  isolated repository (the recipe below). Ran that way against the published 3.5.6, the report says
  `semver MAJOR` and every item it flags is accounted for in BREAKING-CHANGES.md (the six renamed config
  constants, `DispatcherHandler.flushResponse(WebServerHttpResponse)`, `NettyServerHttpResponse.CONN_CTX`,
  and the 11-argument `Http2ChannelInitializer` constructor). **It reports; it does not fail.** Measured with 0.26.2: the report's
  `Treat changes as errors` block reads `No` for every category (any / binary / source / semantic), so a
  MAJOR-incompatible delta still ends in BUILD SUCCESS. To make the profile a real gate, set one of these
  inside its `<parameter>` block (names read from the plugin descriptor, not from memory):
  `breakBuildBasedOnSemanticVersioning`, `breakBuildOnBinaryIncompatibleModifications`,
  `breakBuildOnSourceIncompatibleModifications` or `breakBuildOnModifications`.
- **Gotcha — silent self-comparison**: while master still carries the version number of the last
  release, a locally `install`ed jar shadows the released artifact and the comparison degenerates into
  "3.5.6 vs 3.5.6", which cheerfully reports *No incompatible changes*. Measured: the locally installed
  `spring-web-3.5.6.jar` and Central's differ (501 KB vs 419 KB, different sha256). When in doubt,
  compare against a file — that bypasses repository resolution entirely:

  ```bash
  # fetch the released jar through an isolated local repo, so the shadowing copy stays untouched
  ./mvnw -q dependency:copy -Dartifact=io.github.springperf:spring-web:3.5.6:jar \
      -Dtransitive=false -DoutputDirectory=_baseline -Dmaven.repo.local=_m2tmp
  ./mvnw -Pcompat-file -Dcompat.oldJar="$PWD/_baseline/spring-web-3.5.6.jar" verify -DskipTests
  ```

- First real comparison (master vs released 3.5.6, `spring-web`) reported the expected intentional
  breaks: removed constants `ASYNC_TIMEOUT*`, `HTTP_MAX_HEADER_SIZE*`, `SERVER_SHUTDOWN_TIMEOUT*`,
  `USE_FORWARDED_HEADERS`, a removed `DispatcherHandler` method, a removed `NettyServerHttpResponse`
  field and an `Http2ChannelInitializer` constructor. The constants line up with the config-key renames
  recorded below; the last two are now named in BREAKING-CHANGES.md, with the signatures taken from the
  report itself (a later run against the 3.2.4 baseline printed the removed 11-argument
  `Http2ChannelInitializer` constructor next to its 15-argument replacement, and the removed
  `NettyServerHttpResponse.CONN_CTX` field).
- BREAKING-CHANGES.md records the intentional breaks; the japicmp report is its machine-checkable
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
  `>=` cannot pass by accident. The async/SSE lifecycle E2E compares `PerfAsyncWebRequest#activeRequestRefs()`
  against a per-scenario baseline with a bounded poll, so a leaked holder fails instead of being collected
  later, and one case aborts an idle stream where only `channelInactive` can release. The fast-path parameter
  lookup test asserts the limit cannot be bypassed, and the `maxInactiveInterval` regression is pinned by name.
  The usual false-assurance smells are absent: no `@Disabled`/`@Ignore` in any of the 559 test files, no empty
  catch, no `assertTrue(true)`. The 48 `Thread.sleep` calls are bounded polls or semantically required waits
  (a 1 s session expiry waits 1.6 s, ETag granularity waits 1.1 s, the slow producers live in test fixtures).
- Two gaps are known and deliberate. `BaseWebServerHttpRequest.parameterMap` - the one real concurrency fix -
  has no test, because a `volatile` visibility bug is not reproducible in a unit test; the fix carries the
  argument instead (`LocaleConfig`'s race does have a regression test, `defaultLocaleContextStaysConsistentWithLocaleSetDefault`).
  And the audit read the invariant-bearing tests rather than all 559: the sweep covered the smells, the deep
  read covered the ones guarding resource and lifecycle behaviour.

### PR Checklist

- [ ] Code compiles: `mvn clean compile`
- [ ] Tests pass: `mvn clean test`
- [ ] Related tests have been added or updated
- [ ] Public API has JavaDoc
- [ ] No empty catch blocks (except for resource cleanup scenarios)
- [ ] No `System.out.println` debug code
