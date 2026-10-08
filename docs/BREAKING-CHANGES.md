# 不兼容变更（Breaking Changes）

> 目标版本：下一个主版本（major）。本版本为对齐 Spring Boot / Tomcat 配置命名，
> **移除全部旧配置键的向后兼容别名**。升级到此版本必须按下方映射迁移配置，旧键不再被读取。
>
> 配套专项：`docs/feature/config-alignment-springmvc-tomcat.md`（该目录 gitignored，仅本地维护；写法同 `docs/internals/12-support-bridge.md`）
> 发布说明：版本号与日期见 `CHANGELOG.md`

---

## 背景

在 `config-alignment` 专项（P0/P1）中，框架新增了一批对齐 Spring Boot / Tomcat 的标准配置键，
并一度保留了旧键作为别名（双读：新键优先、旧键回退）。

经决策：**不再考虑历史兼容**，旧键在本次主版本中**直接移除**，仅保留对齐 Boot 的命名。
本文件即发布用的「不兼容变更清单」，供升级参考。

---

## 一、P0 移除项

| 移除的旧键 | 替代键 | 旧语义 | 新语义 / 迁移说明 |
|---|---|---|---|
| `server.use-forwarded-headers`（boolean） | `server.forward-headers-strategy`（枚举） | `true`=信任转发头 | 枚举 `NONE`/`FALSE`=不信任；`FRAMEWORK`/`NATIVE`=信任（解析 `Forwarded` / `X-Forwarded-*`）。<br>迁移：`server.use-forwarded-headers=true` → `server.forward-headers-strategy=FRAMEWORK` |

---

## 二、P1 改名项（旧键移除）

| 移除的旧键 | 替代键 | 迁移说明 |
|---|---|---|
| `server.http.max-header-size`（int, bytes） | `server.max-http-request-header-size`（int, bytes） | 仅改名，单位不变（默认 8KB） |
| `server.async.timeout`（ms） | `spring.mvc.async.request-timeout`（Duration） | `server.async.timeout=30000` → `spring.mvc.async.request-timeout=30s`（仍兼容纯数字毫秒） |
| `server.shutdown.timeout`（ms） | `server.shutdown.grace-period`（Duration） | `server.shutdown.timeout=30000` → `server.shutdown.grace-period=30s` |
| `server.servlet.application-name` | `server.servlet.application-display-name` | 仅改名 |
| `server.servlet.encoding.request` | `server.servlet.encoding.charset` | 统一 charset，同时作用于请求与响应 |
| `server.servlet.encoding.response` | `server.servlet.encoding.charset` | 同上（两键合并） |
| `server.virtual-host` | `server.servlet.virtual-server-name` | 仅改名（默认 localhost），对齐 Boot 命名 |
| `server.tomcat.max-part-count` | `server.http.multipart.max-part-count` | 仅改名，语义不变（默认 -1 不限）。本框架非 Tomcat，键归入 HTTP 协议层家族 |
| `server.tomcat.max-part-header-size` | `server.http.multipart.max-part-header-size` | 仅改名，语义不变（默认 8192） |

> **同时被移除的 Java 常量**（japicmp 对**已发布 3.5.6** 基线的实测报告里 10 项移除中的 7 项，其余 3 项见下方扩展点条目）：
> `PropertiesConstant.ASYNC_TIMEOUT` / `ASYNC_TIMEOUT_DEFAULT`、`HTTP_MAX_HEADER_SIZE` / `HTTP_MAX_HEADER_SIZE_DEFAULT`、
> `SERVER_SHUTDOWN_TIMEOUT` / `SERVER_SHUTDOWN_TIMEOUT_DEFAULT`、`USE_FORWARDED_HEADERS`。
> 键名层面的迁移见上表；**常量标识符没有兼容别名**——直接引用 `PropertiesConstant.XXX` 的代码需改为新常量或直接写新键名。

---

## 三、P1 视图引擎内部键（对齐 Boot 后移除 `spring.web.view.*`）

视图解析器原本使用框架内部键 `spring.web.view.{engine}.*`，现已统一为 Spring Boot 风格
的 `spring.{engine}.*`。**所有 `spring.web.view.*` 键移除**，仅保留 `spring.*` 命名。

| 移除的旧键（`spring.web.view.*`） | 替代键（`spring.*`） |
|---|---|
| `spring.web.view.thymeleaf.prefix` | `spring.thymeleaf.prefix` |
| `spring.web.view.thymeleaf.suffix` | `spring.thymeleaf.suffix` |
| `spring.web.view.thymeleaf.cache` | `spring.thymeleaf.cache` |
| `spring.web.view.thymeleaf.mode` | `spring.thymeleaf.mode` |
| `spring.web.view.thymeleaf.cache-ttl` | `spring.thymeleaf.cache-ttl` |
| `spring.web.view.thymeleaf.enabled` | `spring.thymeleaf.enabled` |
| `spring.web.view.thymeleaf.encoding` | `spring.thymeleaf.encoding` |
| `spring.web.view.freemarker.prefix` | `spring.freemarker.prefix` |
| `spring.web.view.freemarker.suffix` | `spring.freemarker.suffix` |
| `spring.web.view.freemarker.cache` | `spring.freemarker.cache` |
| `spring.web.view.freemarker.template-loader-path` | `spring.freemarker.template-loader-path` |
| `spring.web.view.freemarker.settings` | `spring.freemarker.settings` |
| `spring.web.view.freemarker.content-type` | `spring.freemarker.content-type` |
| `spring.web.view.beetl.prefix` | `spring.beetl.prefix` |
| `spring.web.view.beetl.suffix` | `spring.beetl.suffix` |
| `spring.web.view.beetl.cache` | `spring.beetl.cache` |

### 全局视图编码键移除

| 移除的旧键 | 处理 |
|---|---|
| `spring.web.view.encoding`（全局视图编码） | 移除。Thymeleaf 改用 `spring.thymeleaf.encoding`；Freemarker / Beetl 默认 UTF-8（与 Boot 行为一致，无独立编码键）。 |

> 注：`spring.web.view.engine`（视图引擎选择）**不在移除范围**——Boot 无对应键，保持 `spring.web.view.engine`。

---

## 四、迁移检查清单

- [ ] `server.use-forwarded-headers` → `server.forward-headers-strategy`
- [ ] `server.http.max-header-size` → `server.max-http-request-header-size`
- [ ] `server.async.timeout`(ms) → `spring.mvc.async.request-timeout`(Duration)
- [ ] `server.shutdown.timeout`(ms) → `server.shutdown.grace-period`(Duration)
- [ ] `server.servlet.application-name` → `server.servlet.application-display-name`
- [ ] `server.servlet.encoding.request` / `.response` → `server.servlet.encoding.charset`
- [ ] 所有 `spring.web.view.thymeleaf.*` → `spring.thymeleaf.*`
- [ ] 所有 `spring.web.view.freemarker.*` → `spring.freemarker.*`
- [ ] 所有 `spring.web.view.beetl.*` → `spring.beetl.*`
- [ ] `spring.web.view.encoding` → 改用 `spring.thymeleaf.encoding`（Thymeleaf）或删除（Freemarker/Beetl 默认 UTF-8）
- [ ] 检查 `flush(true)` 用法：需要一次性 `Content-Length` 请改用 `flush(false)`；渐进式请确保收尾写终止块
- [ ] 检查 Servlet 代码是否依赖 `println` 触发提交 → 改为显式 `flush()` / `flushBuffer()`
- [ ] 覆写 `DispatcherHandler.flushResponse(...)` 或调用 `ErrorResponseConfig.includeBindingErrors()` 的代码按新签名调整
- [ ] 客户端 / CDN 侧确认条件请求与 Range 新语义（304 带缓存头、`Vary: Accept-Encoding`、多段 `multipart/byteranges`）

---

## 五、影响范围

- 第一至四节的改名项**默认值与主要行为语义不变**，两处例外：
  1. `server.servlet.encoding.request` / `.response` 合并为单一 `server.servlet.encoding.charset`（同时作用于请求与响应）——若原配置对请求/响应使用了不同 charset，合并后无法保持差异；
  2. `spring.web.view.encoding`（全局视图编码）移除——Thymeleaf 改用 `spring.thymeleaf.encoding`，Freemarker / Beetl 固定 UTF-8（与 Boot 一致，无独立编码键）——原全局非 UTF-8 编码对这两个引擎不再生效。
- 本批次**新增的防护性限制默认即生效**（见第六节），部署前请按业务实际请求/响应规模核对。

---

## 六、配置对齐带来的新增限制与默认值（升级前必读）

本批次（`config-alignment` 专项）新增的防护项**多数默认开启**——它们不改变已通过请求的语义，但会**拒绝此前能通过的请求/响应**，属可观察行为变化：

| 配置键 | 默认值 | 超限行为（升级风险） |
|---|---|---|
| `server.max-parameter-count` | `10000` | 参数值总数超限抛 `ParameterLimitExceededException` → **400**（防 hash 碰撞 DoS）。原为不限，含超多参数的请求（如大批量表单）会开始 400 |
| `server.max-connections` | `0`（不限） | 连接数超限时直接断连（不 `fireChannelActive`，无法返回 503）。默认不限 → 默认无行为变化 |
| `server.http.multipart.max-part-header-size` | `8192` | **单 part header 区**超限 → **400**。原为不限：文件名/自定义头较长的上传会开始失败，需调大 |
| `server.http.multipart.max-part-count` | `-1`（不限） | part 总数超限 → **400**。默认不限 → 默认无行为变化 |
| `server.max-http-response-header-size` | `8192` | 响应头总字节超限时**丢弃 body 并降级最小 500**。原为不限：响应头很大（大量 `Set-Cookie` / 自定义头）的接口会开始 500 |
| `server.max-swallow-size` | `2MB` | 错误响应（4xx/5xx）后请求体超此值**不再吞完**，改为关闭连接（负面影响仅限 keep-alive 复用） |
| `server.keep-alive-timeout` / `server.max-keep-alive-requests` | 均为 `0`（都不限制） | 两者**都为 0 ⇒ `KeepAliveHandler` 不注入管线**：默认不再有连接级隐式限制；需要空闲回收 / 请求数上限请显式设置 |
| `server.compression.*` | `false`（总开关） | 默认**不压缩**（保持既有行为）；开启后 gzip 仅作用于白名单 Content-Type、大于 `min-response-size` 且非零拷贝文件响应 |

**与 Spring Boot 默认值不同（从 Boot 迁移时需显式配置）**：

| 键 | 本框架默认 | Spring Boot 默认 | 迁移影响 |
|---|---|---|---|
| `spring.web.resources.add-mappings` | `false` | `true` | Boot 项目默认自动映射静态资源；本框架需显式设为 `true` 才会注册默认静态资源映射 |
| `spring.mvc.throw-exception-if-no-handler-found` | `true` | `false` | 本框架默认抛异常（可被 `@ControllerAdvice` 拦截）而非直接 404/405；需要 Boot 行为时设为 `false` |
| `spring.mvc.publish-request-handled-events` | `false` | `true` | 需要 `ServletRequestHandledEvent`（监控/审计）时显式开启 |
| `server.max-keep-alive-requests` | `0`（不限） | `100` | 默认不再限制单连接请求数；要 Tomcat 行为请显式设为 `100` |
| `server.http.read-timeout` | `0`（关闭） | Tomcat `connectionTimeout` = `20s` | 默认**不施加**读空闲防护：不设则慢速客户端可长期占用连接，**生产建议显式设置**（如 `30s`）。语义见手册：只回收真正空闲的连接与半截请求，在途请求不会被掐断 |
| `server.error.include-message` / `.include-binding-errors` | `never` | `never` | 一致 |
- **第六节是行为与 API 变更**（响应分帧、Servlet flush 语义、条件请求/Range、扩展点签名），
  与配置迁移同属一次主版本升级，需一并评估。
- 未迁移旧键的项目启动后将以默认值运行（不会报错，但旧配置静默失效），
  建议在升级后通过日志/测试确认配置已生效。
- 代码示例（`spring-web-examples`）中若仍使用旧键，需同步更新（见对应示例 PR）。

---

## 六、行为与 API 变更（非配置键，同一次主版本发布）

> 与配置键迁移无直接关系，但需一并评估。条目均落地于「E2E 驱动协议对齐」批次（括号内为提交号）。

### 6.1 响应分帧：`flush(true)` 由「一次性」改为 chunked 渐进式

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `WebServerHttpResponse.flush(true)` | 一次性提交（`Content-Length` 帧，提交即结束） | 首次调用提交 `Transfer-Encoding: chunked` 头帧并送出已缓冲内容，**提交后仍可继续写入**；由 `endStream()` 写终止块（框架收尾自动补） |

- 影响：依赖旧语义（提交后能拿到 `Content-Length`、或提交后不可再写）的代码与压测脚本需调整。
- 迁移：要一次性提交改用 `flush(false)`；要渐进式且长度未知用 `flushChunked()`，收尾由框架 `endStream()` 处理。
- 相关提交：`41f4e785`

### 6.2 Servlet `PrintWriter`：`print/println` 不再自动提交（对齐 Tomcat `autoFlush=false`）

- 旧行为：`getWriter().println(...)` 立即提交响应（`autoFlush=true`）。
- 新行为：`println` **不提交**，仅显式 `flush()` / `flushBuffer()` / `getOutputStream().flush()` 提交；首次写入即标记「业务已接管响应」，框架收尾仍会提交，内容不会丢。
- 迁移：依赖 `println` 推动提交的代码（自建流式循环等）改为显式 `flush()`。
- 相关提交：`10a0f9d0`

### 6.3 扩展点签名变更（源码级不兼容）

| 旧签名 | 新签名 | 说明 |
|---|---|---|
| `protected void DispatcherHandler.flushResponse(WebServerHttpResponse)` | `protected void flushResponse(WebServerHttpRequest, WebServerHttpResponse)` | 流式收尾需判断「异步是否仍挂起」，故增加请求参数；同仓子类 `ManagementDispatcherHandler` 已同步 |
| `public boolean ErrorResponseConfig.includeBindingErrors()` | `public boolean includeBindingErrors(boolean onParam)` | `on-param` 必须真正受参数门控（旧实现在 `ON_PARAM` 下恒为 true，字段级校验信息被无条件下发） |
| 三个 `@Bean` 方法：`SpringWebAutoConfiguration.applicationProperties()`、`accessLogWebFilter(Environment)`、`JspViewAutoConfiguration.jspViewResolver()` | 依次变为 `applicationProperties(Environment)`、`accessLogWebFilter(Environment, ApplicationProperties)`、`jspViewResolver(ApplicationProperties)`（`jspViewResolver` 同时从主自动配置移入 `JspViewAutoConfiguration`） | bean 依旧注册、类型不变；只有**覆写或调用这些方法**的代码（如 `@Configuration` 子类里的 `super.xxx()`）需按新形参适配。由 japicmp 对**真发布 3.5.6** 的比对报出 |
| `public static final AttributeKey<ConnectionContext> NettyServerHttpResponse.CONN_CTX` | 已移除：连接上下文改由「每连接状态持有者」`ChannelAttrs.connCtx` 承载（经 `ChannelAttrs.of(ch)` / `ofIfPresent(ch)` 取） | 整个连接收敛为**单个** channel attr，省掉每请求 8~10 次 `attr(key)` 线性扫描（JFR 实测 `searchAttributeByKey` ≈1.6% 叶帧）。用法见 `docs/internals/05-server-and-http.md` §7 |
| `Http2ChannelInitializer` 的 11 参构造器 `(boolean, SslContext, int, long, boolean, NettyHttpHandler, List<ChannelHandler>, List<ChannelHandler>, int, int, int)` | 已移除，替换为 15 参形态（在原三个 `int` 之后增加 `maxPartCount`、`maxPartHeaderSize`、`CompressionConfig`、`KeepAliveConfig`）；同时新增 `multipartConfig(MultipartConfig)` | 直接 `new` 该类的代码需按新形参调整。上面两侧签名取自 japicmp **实测输出**（基线 3.2.4）：`mvn -Pcompat -Dcompat.oldVersion=<已发布版本> verify`，报告在 `<module>/target/japicmp/` |

- 注：`WebServerHttpResponse` 新增的方法（`flushChunked`/`endStream`/`isStreaming`/`markStreamCompleted`/`setBeforeCommit`）均为 `default`，**不要求**既有实现类改动。**例外**：`markStreamCompleted` 后来由 `void` 改为 `boolean`（抢占式「终止块写入权」，见 6.10）——覆写过该方法的实现需同步改签名（返回 `true` 表示本次调用赢得写入权）。
- 迁移：覆写或调用上表方法的代码按新签名调整。
- 相关提交：`41f4e785`、`e6cdab8d`

### 6.4 条件请求与错误体暴露语义收紧

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `If-None-Match` 比较 | 强比较（`W/"x"` 不匹配 → 持续 200） | **弱比较**（忽略 `W/` 前缀，RFC 9110 §13.1.2）→ 更易命中 304 |
| `If-None-Match` + `If-Modified-Since` 并存 | 两者都评估（可能误判 304） | 存在 `If-None-Match` 时**忽略** `If-Modified-Since`（§13.1.3） |
| `304` 响应的缓存头 | 无 `Cache-Control` | 与对应 `200` 一致地输出 `Cache-Control`（§15.4.5） |
| 预压缩（`.gz`）变体 | 无 `Vary` | 输出 `Vary: Accept-Encoding`（防共享缓存污染） |
| `server.error.include-*` 的 `on-param` | `include-binding-errors` 在 `ON_PARAM` 下等同 `always`；Servlet `sendError` 路径参数恒视为「未命中」；`?x=false` 也算命中 | 参数名 `trace`/`message`/`errors`，值 `false` 视为显式关闭；一次性提交/错误/适配器三条路径统一门控；`include-message=always` 时错误体带根因消息 |
| `If-Range` 不匹配 | — | 忽略 `Range` 返回整实体（不返回与新版实体不一致的片段） |

- 影响：客户端 / CDN 的缓存命中行为与错误体内容会变化（更规范）。
- 相关提交：`f1bba5f9`、`e6cdab8d`

### 6.5 新增：`Accept-Ranges` 与多段 Range（`multipart/byteranges`）

- 静态资源开始宣告 `Accept-Ranges: bytes`；单段 → `206` + `Content-Range`（不可满足 → `416` + `Content-Range: bytes */len`）；多段 → `206` + `Content-Type: multipart/byteranges; boundary=...`（此前多段回退整实体）；`If-Range` 门控（实体标签强比较 / HTTP 日期）。
- 长度已知的流式响应改用 `Content-Length` 帧（此前一律 chunked，导致预设 `Content-Length` 被 Netty 静默移除）。
- 新增防护键 `server.http.max-ranges`：**有效上限 = min(该值, 100)** —— `Range` 头由 Spring `HttpRange` 解析，超过 100 段会抛 `Too many ranges`（框架按「Range 不可用」忽略该头），故本键用于把上限**收紧到小于 100**（例如只允许 `2` 段），设成大于 100 不会放宽；`0`=禁止多段，负值=本层不额外限制。超限按 RFC 9110 §14.2 忽略 `Range` 返回整实体，**不截断段数**（截断会让客户端拿到与请求不符的表示）、也不回 `416`（请求本身合法）。
- 相关提交：`e6cdab8d`、`f1bba5f9`、`ead49073`（上限为本次补充）

### 6.6 超时、错误体协商与同连接串行化

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `server.http.timeout=0` | 每个请求立即 504（定时器 delay=0 即触发，等于全站不可用） | `<=0` 表示**不限制**（对齐 Tomcat `connectionTimeout=0`） |
| 异步请求超时状态码 | 500（无 resolver 映射 `AsyncRequestTimeoutException`） | **503**（对齐 Spring `DefaultHandlerExceptionResolver`）；响应已提交时不改写 |
| 错误体内容协商 | 配置开启 whitelabel 时一律返回 HTML 错误页 | 仅当客户端接受 HTML（无 `Accept`、`*&#47;*` 或 `text/html`）才用 whitelabel；显式 JSON 客户端（RestTemplate / 服务间调用）得到 JSON 错误体 |
| `server.http.read-timeout` | 用 Netty `ReadTimeoutHandler`：处理期间无读事件即关连接 → 慢处理器（超过 read-timeout）的响应被丢弃、客户端无错可查 | 改为读**空闲**超时：有请求在途时改期再审，只回收真正空闲的连接与半截请求（对齐 Tomcat `connectionTimeout`） |
| 同连接 pipelined 请求 | 各自独立分发 → 响应可能乱序；带 `Connection: close` 的后到响应甚至会在先前响应写出前关闭连接、把它丢掉 | 同连接**串行化**：响应写完后按序处理排队请求；排队缓冲在连接关闭时释放（引用计数配平） |
| 压缩响应 | 无 `Vary` | 实际压缩时追加 `Vary: Accept-Encoding`（幂等，防共享缓存把 gzip 表示发给不支持它的客户端） |
| `ParameterLimitExceededException` | 继承 `RuntimeException` | 继承 `ResponseStatusException(BAD_REQUEST)`：MVC 参数解析期懒触发也映射 **400** 而非 500（捕获 `RuntimeException` 的既有代码仍有效） |
| 表单体解码 | Netty 解码器按固定 charset（容器编码不生效） | 按请求 `characterEncoding`（`server.servlet.encoding.*`）解码 |
| 请求头超限等解码失败 | Netty 产出的 `decoderResult=failure` 请求被当正常请求交付业务 | **400** 优雅关闭（对齐 `HttpObjectAggregator`），不再绕过请求头/请求行防护 |
| `spring.servlet.multipart.enabled=false` | 仍安装 multipart 聚合器 | 不安装（`getParts` 按规范报「非 multipart 请求」） |
| `*.ms`/`*.us`/`*.ns` 时长写法 | `parseDurationMillis` 的 `s` 分支先匹配，`500ms` 被解析为 `500m` 而抛错 | 多字符后缀优先匹配，`ms`/`us`/`ns` 可正常使用 |

- 相关提交：`efba12d7`、`b246675f`、`d1c30851`、`267359b7`、`2f505e2f`

### 6.7 会话、重定向与条件请求错误映射

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 路径匹配但条件不满足 | 一律 **404** | 按原因映射（对齐 Spring MVC）：**405**（并写 `Allow` 头列出该路径支持的方法）/ **415**（`consumes` 不匹配）/ **406**（`produces` 不匹配）；`throwExceptionIfNoHandlerFound=true` 时以 `ResponseStatusException` 承载状态码，`Allow` 直接写入响应 |
| `sendRedirect` 的相对路径 | location 原样写出（如 `next`） | 按 Servlet 规范转为**绝对 URL**：以当前请求 URI 所在目录解析，`./`/`..` 归一化，保留 query/fragment；带 scheme（`http:`/`mailto:`）与网络路径引用 `//host/...` 原样透传；`/` 开头仍补 context-path |
| `sendRedirect` 前已写入的缓冲 | 302 可能夹带先前写入的页面字节 | 丢弃已缓冲内容（对齐 Tomcat `clearBuffer=true`）；已提交后调用仍抛 `IllegalStateException`（此时响应已在线路上，客户端保留已提交内容） |
| `tracking-modes=URL` | 仍下发会话 Cookie（双轨）；`encodeURL` 写出的 `;jsessionid=` 无人回读 → 跟随重写后的 URL 会 **404 且丢会话** | 有效跟踪模式不含 `COOKIE` 时**不下发**会话 Cookie；服务端从请求 URI 回读 `;jsessionid=`（Cookie 优先，Servlet §7.1）；路由前剥离各路径段的矩阵参数（对齐 Spring `removeSemicolonContent=true`，原始 URI 保留） |
| `server.servlet.session.cookie.same-site` | 配置值被整体大写（`STRICT`）后直接交给 Netty 枚举 → **抛 `IllegalArgumentException`** | 规范化为 `Lax`/`Strict`/`None`（大小写不敏感；未知值告警并忽略） |
| `server.servlet.encoding.charset` | 仅用于"防止业务覆盖"，未作为容器默认值 | 作为请求/响应适配器的**默认编码**（Filter 包装 rebind 后重新应用；`force-*` 语义不变） |
| `isRequestedSessionIdFromCookie()` / `isRequestedSessionIdFromURL()` | 前者按"id 非 null"判定（把回退的新建会话 id 也算作 Cookie 来源）；后者恒 `false` | 均反映 id 的**真实来源**（Cookie / URI） |

- 相关提交：`56b16d40`、`85b98205`、`64de3eef`、`ff33fe73`（`9b1d361a` 为其 E2E 锁定，47 例）

### 6.8 管理端口、转发语义与其它行为调整

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 管理端口的 HTTP/2 与请求体上限 | 误用主服务器键（`server.http2.enabled`、`server.http.max-content-length`） | 独立键 `management.server.http2.enabled`、`management.server.max-content-length`（前缀不再借主服务器） |
| 管理端口与主端口相同 | 任一为随机端口（`0`）时也判为冲突、**阻断启动** | 仅当两者都显式配置为**相同且非 0** 时才报冲突 |
| `RequestDispatcher.forward` | 清空已写入的响应头并**强制状态 200** | 仅重置缓冲（丢弃未提交 body）：**保留**已写响应头与状态码（符合 Servlet 规范）；并修正 `FORWARD_SERVLET_PATH`/`FORWARD_PATH_INFO` 颠倒、`FORWARD_QUERY_STRING` 去掉前导 `?` |
| 非 `void` 方法返回 `null` | 不标记 handled → 响应永不 flush（客户端挂到超时） | 视为「无响应体的已完成响应」并标记 handled（`void` 方法行为不变） |
| 普通 `HandlerInterceptor` Bean（`spring-web-mvc-support`） | 自动全局注册 | **不再**自动注册（与 Spring MVC 一致）：请经 `addInterceptors` 注册或声明 `MappedInterceptor`（也避免同一实例两路注册导致重复执行） |
| `postHandle` / `afterCompletion` 遍历顺序 | 逆向 | 与 `preHandle` **同向（正向）**；`preHandle` 提前返回时未通过者仍按逆向补 `afterCompletion` |
| 畸形 multipart 报文 | 裸异常关闭连接 | **400** 优雅关闭（`TooLongFrameException` 仍为 **413**） |
| 流式响应（`writeStream`） | 在 EventLoop 线程读 `InputStream` → 慢速源（DB 游标/网络流）造成 I/O **队头阻塞** | 读卸载到业务池（默认池；缺失时 ForkJoin；**绝不回退 EventLoop**），按 8KB 分块写出并以可写性背压 |
| `writeFile` / `writeStream` 的 `Content-Type` | 一律 `application/octet-stream` | 尊重调用方已设置的值；否则按文件扩展名推导（`writeFile`）；仍无法定夺时回退 `octet-stream` |
| `getOutputStream()` | 每次返回新实例 | 同一响应返回**同一实例**（Servlet 规范）；`rebind` 后失效重建 |
| JSR-356 `Session.setMaxIdleTimeout` | 仅赋值字段（空闲超时实际不生效） | 传播到 Netty pipeline 的 `IdleStateHandler`（`<=0` 移除空闲检测） |
| WebSocket 单帧上限 | 固定 text 8KB / binary 64KB | 新增 `WebSocketHandlerRegistration#setMessageSizeLimit` 按路径覆盖（默认值不变） |
| `@WebFilter` 的 `urlPatterns` | 读注解不含代理/继承来源 → 静默升格为全局 Filter | 用 `findMergedAnnotation` 沿超类/接口查找；`urlPatterns` 真正限制路径 |

- 相关提交：`df3c238f`、`89385c3d`、`a1b0a509`、`756f31f8`、`1b1f33b3`（`54069908`、`e33d4a9b` 为其 E2E 锁定）

### 6.9 分帧与 Servlet 重置语义

| 项 | 旧行为 | 新行为 |
|---|---|---|
| **HTTP/1.0** 请求的渐进式输出 | 发送 chunked 帧（1.0 无此分帧，客户端会把分块标记当 body 收下 —— 协议违规） | 退化为 **close-delimited**：响应头不带 `Content-Length` / `Transfer-Encoding`，显式 `Connection: close`，连接关闭即 body 结束（对齐 Tomcat） |
| 已提交响应上的 `resetBuffer()` / `reset()` | 静默清空缓冲，调用方误以为能收回已发出的内容 | 抛 `IllegalStateException`（Servlet 规范 §5.6）；`forward` / `sendRedirect` 均先检查 `isCommitted`，不受影响 |
| `resetBuffer()` 与 Writer 的编码缓冲 | 只清响应体缓冲 → 「写入后未 flush」的内容仍留在编码缓冲，提交前回调会把它们重新刷出，**丢弃语义失效** | 先把编码缓冲刷入响应体再清空，使 discard 语义真正生效 |

- 相关提交：`e8e16f87`、`890f4345`（`09ddf4bc`、`335c8a17`、`a567726c`、`f68dd351` 为其 E2E 锁定）

### 6.10 异步 / SSE 生命周期与引用计数

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 异步期间的入站缓冲存活 | 不在 `startAsync()` 保活（依赖「body 已物化」的取舍）；`getContentLength()` 现读 `content()`，异步阶段可能读到 0/陈旧值，甚至抛 `IllegalReferenceCountException` | 异步持有者**显式 acquire/release**：`startAsync()` 恰好一次 `+1`，响应**写终结**时恰好一次 `-1`；body 长度改为**构造期缓存**，异步阶段不再触碰 `content()` |
| 请求释放时级联释放响应缓冲 | **每次** `release()` 都级联（EventLoop 提交业务池后、`channelRead.finally` 各一次）→ 可能释放**业务线程正在写**的响应 buf 并置空（跨线程早释放竞态） | 仅在 **refCnt 归零（最后一次）** 时级联；响应侧另设兜底点：写终结回调 + 连接关闭时对在途响应 `release()` |
| 客户端断连（空闲 SSE / 未完成异步） | 既不写 `LastHttpContent`，也无 chunk 写失败 → 异步持有者扣留的入站引用**永不归还**（等 GC） | 连接关闭退场路径 `releaseOnConnectionClose()`（由 `channelInactive` 触发）：只做生命周期清理，**不**触发业务错误处理器（客户端中断不是业务错误） |
| reactive 上游订阅取消 | 依赖「下一次投递失败」→ 源在断连后不再投递（长轮询挂住）则**永不取消**，每个断连客户端留下仍在运行的源 | 新增「客户端断连」钩子，断连即取消上游订阅（不必等下一次投递） |
| 终止块（`LastHttpContent`）写入权 | `markStreamCompleted()` 为 `void`，谁写谁标记 → 可能出现**双终止块**（编码器复位为 INIT 后再收 `LastHttpContent` 抛 `EncoderException`；HEAD + SSE 实测触发） | `markStreamCompleted()` 改 **`boolean` 抢占式**（`true` = 赢得写入权，只有一方能写）；HEAD 请求（已提交但从未进入渐进式输出）**不再**补终止块 |
| `StreamEmitter.send()` 在完成后调用 | 静默进入待发列表，`initialize()` 时批量交付 → **已终止的流仍被写出数据** | 抛 `IllegalStateException`（对齐 Spring `ResponseBodyEmitter`） |
| 「交换已结束」后仍写响应体 | 继续分配池化缓冲（内容永远写不出去）→ ByteBuf 泄漏 | `getBuf()` 记 **ERROR** 并返回**非池化**丢弃缓冲；已提交时 `flushChunked`/`writeAndFlush` 拒绝写出并**就地释放**该缓冲（warn） |
| 返回值是 reactive 类型但无解析器认领 | 静默「200 + 空 body」（最难排查的降级形态） | 打 **WARN** 提示缺 `ReactiveAdapter`（最常见原因：classpath 无 reactor） |

- 相关提交：`fe722450`、`685eaa0c`、`61003c38`、`66204e80`、`11c3ae53`（`219f28a0`、`ebba0f80`、`03f820aa`、`4d838675`、`91d495ad`、`dad4294c`、`249efc50`、`0a346fa2` 为其 E2E / 契约锁定）

### 6.11 性能优化带来的可观测变化（JFR 驱动，等价性为主）

> 本批以「等价变换 + JFR 采样佐证」为主，绝大多数改动对使用方不可见；下面 4 条是其中**可观测**的部分。

| 项 | 旧行为 | 新行为 |
|---|---|---|
| **非法配置值** | 热路径键**懒解析**：首次使用时才失败（或落到默认值），启动不受影响 | `ApplicationProperties` 在 **Environment 就绪时急切解析**（对齐 Boot `@ConfigurationProperties`）：解析失败即 **启动失败**（fail-fast）。运行期配置刷新（Spring Cloud）则逐字段容错：解析失败的字段**保留旧值**并告警，不让脏推送中断刷新 |
| servlet 桥 `AsyncContext.start` 交棒 | 受 `server.http.timeout` 约束 | 在 `pool.default-execute-mode=eventloop`（**默认即此模式**）下**不再受** `server.http.timeout` 约束，改由 Servlet 自身 async 超时语义负责（代码内标注为已知残留）。其他执行模式不变 |
| 响应超时装配时机 | 每个请求开始即装配定时器 | **按需装配**（同步段结束仍未提交时兜底，异步/流式等待时装配）：eventloop 同步且已提交的请求不再产生一次 schedule/cancel；**长于 `server.http.timeout` 的流式响应不再出现「已提交拒绝写出」WARN**（纯噪声消除，无内容改写） |
| `spring.mvc.publish-request-handled-events` 默认值 | `true`（请求完成即发布 `ServletRequestHandledEvent`） | **`false`**（**本项目有意关闭**，Boot 默认相反为 `true`：该事件对多数应用无实际用途，每请求发布纯属开销）。依赖该事件做监控/审计的需**显式开启**（见配置手册） |

- 等价性佐证（各提交内附 JFR 数值）：`@RequestParam` 快路径 **CPU 采样/请求 −21%**、GET 分配 **−8.8%**；快路径资格在**构造期**判定（曾用「查找中途超阈再回退」，会被命中早退绕过 hash DoS 防护，回归用例捕获后修正）；`canDeserialize` 探测按 `mappingContext` 缓存（**约束**：默认 `ObjectMapper` 的能力集视为静态，运行期修改需同时 `clearCache` 该键与 `READ_JAVA_TYPE`/`WRITE_TYPE_SERIALIZABLE` 两个键——自定义 mapper 不缓存，保留请求级切换语义）。
- 相关提交：`f515ae43`、`b6091749`、`bf1b88c8`、`59dc0ecb`、`21c3f8b1`、`2ec4e7c6`、`f3f9fa43`、`1708ed48`、`392747f8`、`d8c382cd`、`26281555`、`580ac83b`、`0254200d`、`5c3a23d2`

### 6.12 视图层：模板可见变化与新增扩展点

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 模板读取会话数据 | 读不到（`IWebSession` 返回 `null`）；且 Thymeleaf 3.1 已移除 `#session` / `#request` 表达式对象 | Servlet 场景（引入 `spring-web-servlet`）由 `ServletWebExchangeProvider` 提供**真实** session / principal / cookie；session 属性被**注入上下文变量**（同名时 **model 优先**）→ 模板写 `${user}` 而非 `${session.user}` |
| 模板 `IWebExchange` 的来源 | `ThymeleafWebContext` 内部硬编码构造 | 新增 SPI **`WebExchangeProvider`**：`supports()` 能力探测 + `createExchange()` + `getOrder()`，按 order 升序取第一个 `supports()==true`，内置默认实现（order 最低、恒支持）兜底。经 Spring 组件容器装配（**不用** JDK `ServiceLoader`，兼容 native-image/AOT） |
| `WebMvcConfigurer#addViewControllers` | **静默忽略**（无告警） | 由 `WebMvcConfigurerBridge` 桥接（`ViewControllerRegistry` / `ViewControllerRegistration` + `ViewControllerInvoker`）；未能桥接的回调打 **WARN**，不再静默 |
| `spring.mvc.view.prefix` / `.suffix` | — | **新增**，语义对齐 Boot：**仅 JSP 视图**的前后缀；模板引擎仍用各自的 `spring.{engine}.prefix/suffix` |
| 会话持久化与编码强制 | — | `server.servlet.session.persistent` / `.store-dir` / `.persistent-exclude`（JDK 序列化，每 session 一文件，不落盘 `transient` 属性）；`server.servlet.encoding.force` / `.force-request` / `.force-response` |
| 配置中心动态刷新 | 改了配置需重启才生效 | 监听 `EnvironmentChangeEvent`（**按类名监听**，不引入 Spring Cloud 编译期依赖 ✓）→ 清空框架属性缓存；亦可调用 `WebContext.refreshProperties()` 手动触发。刷新失败逐字段保留旧值（见 6.11） |

- 相关提交：`e6088584`、`30d1bf12`、`6aa5bd5f`、`9b6779ac`、`4f7ebbbf`、`24d4f768`
