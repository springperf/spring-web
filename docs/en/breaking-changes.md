# Breaking Changes

> [中文版](../breaking-changes.md)

> This file is the **cumulative ledger for the 2.7.x line**: whenever a 2.7.x release introduces a breaking change,
> a new section is **appended** and one row is registered in the version index above.
> The **full** entry list per release (features and fixes included) lives in `CHANGELOG.md`.
>
> - **Scope**: only entries that force **user action** on upgrade — removals, renames, signature or visibility
>   changes, semantic tightening. New features and regular fixes are not listed.
> - **Requirement**: every entry states either a **migration action** or **why no migration is needed**.
> - **Versions and dates** follow `CHANGELOG.md`; section titles carry the version, so reference entries by version.

## Version Index

| Version | Date | Topic |
|---------|------|-------|
| **2.7.6** | 2026-09-10 | Module split (`spring-web-support` → `spring-web-servlet` + `spring-web-mvc-support`), tightened `redirect:` semantics, view resolution no longer silent, production-grade Netty / pool defaults |
| **2.7.4** | 2026-08-06 | `JsonConverter` gained the abstract method `toJsonBytes(Object)` |

---

## [2.7.6] - 2026-09-10

### 1. Module split: `spring-web-support` split in two

**Change**: `spring-web-support` no longer exists; it is split into

- `spring-web-servlet`: pure Servlet bridge (Filter / Servlet / `HttpSession` / JSP / `@SessionAttribute`,
  i.e. official `spring-web` semantics), **zero SpringMVC dependency**;
- `spring-web-mvc-support`: SpringMVC compatibility bridge (`org.springframework.web.servlet.*` shims,
  `WebMvcConfigurer` / `HandlerInterceptor` / `RequestBodyAdvice` bridges), which **depends on `spring-web-servlet`**.

The starter's auto-configuration is split accordingly into `SpringWebServletAutoConfiguration` +
`SpringWebMvcSupportAutoConfiguration` (each activated by its own module's class condition).

**Impact**: projects depending on `spring-web-support` directly cannot resolve that coordinate.

**Migration**: replace the coordinate as needed — use `spring-web-servlet` when only Servlet semantics
(Filter / Session / JSP) are required, or `spring-web-mvc-support` when you keep using the
`org.springframework.web.servlet.*` shims or `WebMvcConfigurer`. Projects that only depend on
`spring-boot-starter-web` (this framework's starter) **need no change**.

### 2. Tightened `redirect:` semantics

**Change**: the `redirect:` prefix only applies to **view methods**. A `@ResponseBody` / `@RestController`
method returning the string `"redirect:/x"` is no longer hijacked into a 302 — it is serialized as a plain
string. When the target URL already carries a query string, `&` is used instead of a second `?`.

**Impact**: endpoints that relied on "return a string to get a 302" behave differently — the JSON body now
contains the string `"redirect:/x"`.

**Migration**: return the status and `Location` explicitly when you need a redirect:

```java
return ResponseEntity.status(302).location(URI.create("/target?a=1")).build();
```

### 3. View resolution failure is no longer silent

**Change**: an unresolvable view name used to produce an empty 200 response; it now throws
`IllegalArgumentException` (500 + error context), and `ViewResolver` failures are logged.

**Impact**: typos in view names, a missing template engine, or a wrong prefix used to look like an empty 200
and now surface as 500.

**Migration**: check the `spring.web.view.*` prefix/suffix and the template engine on the classpath; if you
really want "resolve failure ⇒ empty response", register a fallback `ViewResolver` explicitly.

### 4. Static resources accept GET only

**Change**: `ResourceRequestHandler` only accepts GET. HEAD is mapped to GET by the routing layer but returns
metadata only, **skipping file IO**.

**Impact**: other methods against a static-resource path are no longer served by the static handler
(they fall through to normal routing).

**Migration**: do not target static-resource paths with POST/PUT.

### 5. Production-grade Netty and pool defaults

**Change**: the following defaults moved from "developer friendly" to "production friendly":

| Key | Old default | New default | Notes |
|-----|-------------|-------------|-------|
| `server.netty.so-backlog` | 128 | **1024** | accept backlog, so bursts are not dropped |
| `server.netty.so-keepalive` | false | **true** | TCP keepalive, reclaims dead peers |
| `server.http.max-content-length` | 1MB | **4MB** | per-request body limit |
| `pool.queue-capacity` | unbounded | **100** (bounded) | the bounded queue is what makes `pool.max-pool-size` growth and "queue full → fast 503" effective |

**Impact**: the body limit is 4× larger; pool queueing changed from unbounded (unlimited backlog, rising latency)
to bounded (immediate 503 once full).

**Migration**:

- applications relying on unbounded queueing to absorb spikes should set `pool.queue-capacity` explicitly
  (or raise `pool.max-pool-size`) and accept the 503 semantics;
- set `server.http.max-content-length` explicitly if you want a tighter body limit.

---

## [2.7.4] - 2026-08-06

### `JsonConverter` gained the abstract method `toJsonBytes(Object)`

**Change**: `io.springperf.web.json.JsonConverter` gained `byte[] toJsonBytes(Object obj)` to skip the
intermediate `String` copy when writing the response. The bundled Jackson / Fastjson converters implement it.

**Impact**: external implementations of `JsonConverter` (including anonymous ones) fail to compile or hit
`AbstractMethodError` at runtime.

**Migration**: implement it by delegating to the existing serialization:

```java
@Override
public byte[] toJsonBytes(Object obj) {
    return toJson(obj).getBytes(StandardCharsets.UTF_8);
}
```
