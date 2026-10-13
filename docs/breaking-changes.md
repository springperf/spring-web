# 不兼容变更（Breaking Changes）

> [English Version](en/breaking-changes.md)

> 本文件是 **2.7.x 线的累积台账**：任何 2.7.x 版本引入不兼容变更，都在文末**新增一节**，
> 并在上面的版本索引里登记一行。每个版本的**全量**条目（含新特性与修复）见 `CHANGELOG.md`。
>
> - **收录范围**：升级会让使用者**必须动手**的条目 —— 移除、改名、签名或可见性变更、语义收紧；新特性与常规修复不收。
> - **登记要求**：每条要么给出**迁移动作**，要么说明**为何无需迁移**。
> - **版本号与日期**以 `CHANGELOG.md` 为准；节标题带版本号，引用请用版本号描述，不要依赖中文序号（序号会随版本重排）。

## 版本索引

| 版本 | 日期 | 主题 |
|------|------|------|
| **2.7.7** | 2026-10-13 | 配置键对齐 Spring Boot（旧键移除/改名）、响应分帧与 Servlet `flush` 语义、条件请求与 Range、扩展点签名 |
| **2.7.6** | 2026-09-10 | 模块拆分（`spring-web-support` → `spring-web-servlet` + `spring-web-mvc-support`）、`redirect:` 语义收紧、视图解析失败不再静默、生产级 Netty / 线程池默认值 |
| **2.7.4** | 2026-08-06 | `JsonConverter` 接口新增抽象方法 `toJsonBytes(Object)` |

---

## [2.7.7] - 2026-10-13

### 一、移除项与改名项

| 移除的旧键 | 替代键 | 旧语义 → 新语义 / 迁移说明 |
|---|---|---|
| `server.use-forwarded-headers`（boolean） | `server.forward-headers-strategy`（枚举） | `true`=信任转发头 → 枚举 `NONE`/`FALSE`=不信任，`FRAMEWORK`/`NATIVE`=信任（解析 `Forwarded` / `X-Forwarded-*`）。<br>迁移：`server.use-forwarded-headers=true` → `server.forward-headers-strategy=FRAMEWORK` |
| `server.shutdown.timeout`（ms） | `server.shutdown.grace-period`（Duration） | `server.shutdown.timeout=30000` → `server.shutdown.grace-period=30s` |
| `server.servlet.application-name` | `server.servlet.application-display-name` | 仅改名 |
| `server.servlet.encoding.request` | `server.servlet.encoding.charset` | 统一 charset，同时作用于请求与响应 |
| `server.servlet.encoding.response` | `server.servlet.encoding.charset` | 同上（两键合并） |
| `spring.web.view.thymeleaf.*` | `spring.thymeleaf.*` | 仅改名（`prefix` / `suffix` / `cache` / `mode` / `cache-ttl` / `enabled` / `encoding`） |
| `spring.web.view.freemarker.*` | `spring.freemarker.*` | 仅改名（`prefix` / `suffix` / `cache` / `template-loader-path` / `settings` / `content-type`） |
| `spring.web.view.beetl.*` | `spring.beetl.*` | 仅改名（`prefix` / `suffix` / `cache`） |
| `spring.web.view.encoding`（全局视图编码） | 移除 | Thymeleaf 改用 `spring.thymeleaf.encoding`；Freemarker / Beetl 默认 UTF-8（与 Boot 一致，无独立编码键） |

> 注：`spring.web.view.engine`（视图引擎选择）**不在移除范围**——Boot 无对应键，保持原名。

> **同时被移除的 Java 常量**（无兼容别名，直接引用处需改为新常量或直接写新键名）：
> `PropertiesConstant.SHUTDOWN_TIMEOUT`、`PropertiesConstant.USE_FORWARDED_HEADERS`。

---

### 二、新增限制与默认值

本批次新增的防护项**多数默认开启**——它们不改变已通过请求的语义，但会**拒绝此前能通过的请求/响应**：

| 配置键 | 默认值 | 超限行为（升级风险） |
|---|---|---|
| `server.max-parameter-count` | `10000` | 参数值总数超限抛 `ParameterLimitExceededException` → **400**（防 hash 碰撞 DoS）。原为不限，含超多参数的请求（如大批量表单）会开始 400 |
| `server.http.multipart.max-part-header-size` | `8192` | **单 part header 区**超限 → **400**。原为不限：文件名/自定义头较长的上传会开始失败，需调大 |
| `server.http.multipart.max-part-count` | `-1`（不限） | part 总数超限 → **400**。默认不限 → 默认无行为变化 |
| `server.max-http-response-header-size` | `8192` | 响应头总字节超限时**丢弃 body 并降级最小 500**。原为不限：`Set-Cookie` / 自定义头很多的接口会开始 500 |
| `server.max-swallow-size` | `2MB` | 错误响应（4xx/5xx）后请求体超此值**不再吞完**，改为关闭连接（影响仅限 keep-alive 复用） |
| `server.keep-alive-timeout` / `server.max-keep-alive-requests` | 均为 `0`（都不限制） | 两者**都为 0 ⇒ `KeepAliveHandler` 不注入管线**：默认不再有连接级隐式限制；需要空闲回收 / 请求数上限请显式设置 |
| `server.max-ranges` | 见 4.5 | 用于把 Range 段数上限**收紧到小于 100**（有效上限 = `min(该值, 100)`）；`0`=禁止多段，负值=本层不额外限制 |
| `server.compression.*` | `false`（总开关） | 默认**不压缩**（保持既有行为）；开启后 gzip 仅作用于白名单 Content-Type、大于 `min-response-size` 且非零拷贝文件响应 |
| `server.servlet.session.persistent*` | `false`（总开关） | 开启后按 JDK 序列化每 session 一文件落盘；`transient` 属性不落盘，反序列化受 `persistent-deserialization-filter` 白名单约束 |

> 未迁移旧键的项目**启动不会报错，但旧配置静默失效**，建议升级后通过日志/测试确认配置已生效；框架默认值与 Spring Boot 默认值的差异见 `docs/configuration.md`，从 Boot 迁移时按需显式配置。

---

### 三、迁移检查清单

- [ ] `server.use-forwarded-headers` → `server.forward-headers-strategy`
- [ ] `server.shutdown.timeout`(ms) → `server.shutdown.grace-period`(Duration)
- [ ] `server.servlet.application-name` → `server.servlet.application-display-name`
- [ ] `server.servlet.encoding.request` / `.response` → `server.servlet.encoding.charset`
- [ ] 所有 `spring.web.view.thymeleaf.*` → `spring.thymeleaf.*`
- [ ] 所有 `spring.web.view.freemarker.*` → `spring.freemarker.*`
- [ ] 所有 `spring.web.view.beetl.*` → `spring.beetl.*`
- [ ] `spring.web.view.encoding` → 改用 `spring.thymeleaf.encoding`（Thymeleaf）或删除（Freemarker/Beetl 默认 UTF-8）
- [ ] 直接引用 `PropertiesConstant.SHUTDOWN_TIMEOUT` / `USE_FORWARDED_HEADERS` 的代码改新常量或新键名
- [ ] 检查 `flush(true)` 用法：需要一次性 `Content-Length` 请改用 `flush(false)`；渐进式请确保收尾写终止块
- [ ] 检查 Servlet 代码是否依赖 `println` 触发提交 → 改为显式 `flush()` / `flushBuffer()`
- [ ] 覆写 `DispatcherHandler.flushResponse(...)` 或调用 `ErrorResponseConfig.includeBindingErrors()` 的代码按新签名调整
- [ ] 客户端 / CDN 侧确认条件请求与 Range 新语义（304 带缓存头、`Vary: Accept-Encoding`、多段 `multipart/byteranges`）

---

### 四、行为与 API 变更

#### 4.1 响应分帧：`flush(true)` 由「一次性」改为 chunked 渐进式

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `WebServerHttpResponse.flush(true)` | 一次性提交（`Content-Length` 帧，提交即结束） | 首次调用提交 `Transfer-Encoding: chunked` 头帧并送出已缓冲内容，**提交后仍可继续写入**；由 `endStream()` 写终止块（框架收尾自动补） |

- 迁移：要一次性提交改用 `flush(false)`；要渐进式且长度未知用 `flushChunked()`，收尾由框架 `endStream()` 处理。

#### 4.2 Servlet `PrintWriter`：`print` / `println` 不再自动提交

- 旧行为：`getWriter().println(...)` 立即提交响应（`autoFlush=true`）。
- 新行为：`println` **不提交**，仅显式 `flush()` / `flushBuffer()` / `getOutputStream().flush()` 提交；首次写入即标记「业务已接管响应」，框架收尾仍会提交，内容不会丢。
- 迁移：依赖 `println` 推动提交的代码（自建流式循环等）改为显式 `flush()`。

#### 4.3 扩展点签名变更（源码级不兼容）

| 旧签名 | 新签名 | 说明 |
|---|---|---|
| `protected void DispatcherHandler.flushResponse(WebServerHttpResponse)` | `protected void flushResponse(WebServerHttpRequest, WebServerHttpResponse)` | 流式收尾需判断「异步是否仍挂起」，故增加请求参数；同仓子类 `ManagementDispatcherHandler` 已同步 |
| `public boolean ErrorResponseConfig.includeBindingErrors()` | `public boolean includeBindingErrors(boolean onParam)` | `on-param` 必须真正受参数门控（旧实现在 `ON_PARAM` 下恒为 true，字段级校验信息被无条件下发） |
| 三个 `@Bean` 方法：`SpringWebAutoConfiguration.applicationProperties()`、`accessLogWebFilter(Environment)`、`JspViewAutoConfiguration.jspViewResolver()` | 依次变为 `applicationProperties(Environment)`、`accessLogWebFilter(Environment, ApplicationProperties)`、`jspViewResolver(ApplicationProperties)` | bean 依旧注册、类型不变；只有**覆写或调用**这些方法的代码需按新形参适配 |

> 注：`WebServerHttpResponse` 新增的方法（`flushChunked` / `endStream` / `isStreaming` / `markStreamCompleted` / `setBeforeCommit`）均为 `default`，**不要求**既有实现类改动。**例外**：`markStreamCompleted` 由 `void` 改为 `boolean`（抢占式「终止块写入权」，见 4.10）——覆写过该方法的实现需同步改签名（返回 `true` 表示本次调用赢得写入权）。

#### 4.4 条件请求与错误体暴露语义收紧

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `If-None-Match` 比较 | 强比较（`W/"x"` 不匹配 → 持续 200） | **弱比较**（忽略 `W/` 前缀，RFC 9110 §13.1.2）→ 更易命中 304 |
| `If-None-Match` + `If-Modified-Since` 并存 | 两者都评估（可能误判 304） | 存在 `If-None-Match` 时**忽略** `If-Modified-Since`（§13.1.3） |
| `304` 响应的缓存头 | 无 `Cache-Control` | 与对应 `200` 一致地输出 `Cache-Control`（§15.4.5） |
| 预压缩（`.gz`）变体 | 无 `Vary` | 输出 `Vary: Accept-Encoding`（防共享缓存污染） |
| `server.error.include-*` 的 `on-param` | `include-binding-errors` 在 `ON_PARAM` 下等同 `always`；Servlet `sendError` 路径参数恒视为「未命中」；`?x=false` 也算命中 | 参数名 `trace`/`message`/`errors`，值 `false` 视为显式关闭；一次性提交/错误/适配器三条路径统一门控；`include-message=always` 时错误体带根因消息 |
| `If-Range` 不匹配 | — | 忽略 `Range` 返回整实体（不返回与新版实体不一致的片段） |

#### 4.5 新增：`Accept-Ranges` 与多段 Range（`multipart/byteranges`）

- 静态资源开始宣告 `Accept-Ranges: bytes`；单段 → `206` + `Content-Range`（不可满足 → `416` + `Content-Range: bytes */len`）；多段 → `206` + `Content-Type: multipart/byteranges; boundary=...`（此前多段回退整实体）；`If-Range` 门控（实体标签强比较 / HTTP 日期）。
- 长度已知的流式响应改用 `Content-Length` 帧（此前一律 chunked，导致预设 `Content-Length` 被 Netty 静默移除）。
- 新增防护键 `server.max-ranges`：**有效上限 = min(该值, 100)** —— `Range` 头由 Spring `HttpRange` 解析，超过 100 段会抛 `Too many ranges`（框架按「Range 不可用」忽略该头），故本键用于把上限**收紧到小于 100**；设成大于 100 不会放宽；`0`=禁止多段，负值=本层不额外限制。超限按 RFC 9110 §14.2 忽略 `Range` 返回整实体，**不截断段数**、也不回 `416`。

#### 4.6 超时、错误体协商与同连接串行化

| 项 | 旧行为 | 新行为 |
|---|---|---|
| `server.http.timeout=0` | 每个请求立即 504（delay=0 即触发，等于全站不可用） | `<=0` 表示**不限制**（对齐 Tomcat `connectionTimeout=0`） |
| 异步请求超时状态码 | 500（无 resolver 映射 `AsyncRequestTimeoutException`） | **503**（对齐 Spring `DefaultHandlerExceptionResolver`）；响应已提交时不改写 |
| 错误体内容协商 | 开启 whitelabel 时一律返回 HTML 错误页 | 仅当客户端接受 HTML（无 `Accept`、`*/*` 或 `text/html`）才用 whitelabel；显式 JSON 客户端得到 JSON 错误体 |
| `server.http.read-timeout` | 用 Netty `ReadTimeoutHandler`：处理期间无读事件即关连接 → 慢处理器的响应被丢弃、客户端无错可查 | 改为读**空闲**超时：有请求在途时改期再审，只回收真正空闲的连接与半截请求 |
| 同连接 pipelined 请求 | 各自独立分发 → 响应可能乱序；带 `Connection: close` 的后到响应甚至会在先前响应写出前关闭连接、把它丢掉 | 同连接**串行化**：响应写完后按序处理排队请求；排队缓冲在连接关闭时释放（引用计数配平） |
| 压缩响应 | 无 `Vary` | 实际压缩时追加 `Vary: Accept-Encoding`（幂等） |
| `ParameterLimitExceededException` | 继承 `RuntimeException` | 继承 `ResponseStatusException(BAD_REQUEST)`：MVC 参数解析期懒触发也映射 **400** 而非 500 |
| 表单体解码 | Netty 解码器按固定 charset（容器编码不生效） | 按请求 `characterEncoding`（`server.servlet.encoding.*`）解码 |
| 请求头超限等解码失败 | Netty 产出的 `decoderResult=failure` 请求被当正常请求交付业务 | **400** 优雅关闭（对齐 `HttpObjectAggregator`） |
| `spring.servlet.multipart.enabled=false` | 仍安装 multipart 聚合器 | 不安装（`getParts` 按规范报「非 multipart 请求」） |
| `*.ms`/`*.us`/`*.ns` 时长写法 | `s` 分支先匹配，`500ms` 被解析为 `500m` 而抛错 | 多字符后缀优先匹配，`ms`/`us`/`ns` 可正常使用 |

#### 4.7 会话、重定向与条件请求错误映射

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 路径匹配但条件不满足 | 一律 **404** | 按原因映射（对齐 Spring MVC）：**405**（并写 `Allow` 头）/ **415**（`consumes` 不匹配）/ **406**（`produces` 不匹配） |
| `sendRedirect` 的相对路径 | location 原样写出（如 `next`） | 按 Servlet 规范转为**绝对 URL**：以当前请求 URI 所在目录解析，`./`/`..` 归一化，保留 query/fragment；带 scheme 与 `//host/...` 原样透传；`/` 开头仍补 context-path |
| `sendRedirect` 前已写入的缓冲 | 302 可能夹带先前写入的页面字节 | 丢弃已缓冲内容（对齐 Tomcat `clearBuffer=true`）；已提交后调用仍抛 `IllegalStateException` |
| `tracking-modes=URL` | 仍下发会话 Cookie（双轨）；`encodeURL` 写出的 `;jsessionid=` 无人回读 → 跟随重写后的 URL 会 **404 且丢会话** | 有效跟踪模式不含 `COOKIE` 时**不下发**会话 Cookie；服务端从请求 URI 回读 `;jsessionid=`（Cookie 优先）；路由前剥离矩阵参数，原始 URI 保留 |
| `server.servlet.session.cookie.same-site` | 配置值被整体大写（`STRICT`）后交给 Netty 枚举 → **抛 `IllegalArgumentException`** | 规范化为 `Lax`/`Strict`/`None`（大小写不敏感；未知值告警并忽略） |
| `server.servlet.encoding.charset` | 仅用于「防止业务覆盖」，未作为容器默认值 | 作为请求/响应适配器的**默认编码**（Filter 包装 rebind 后重新应用） |
| `isRequestedSessionIdFromCookie()` / `isRequestedSessionIdFromURL()` | 前者按「id 非 null」判定；后者恒 `false` | 均反映 id 的**真实来源**（Cookie / URI） |

#### 4.8 管理端口、转发语义与其它行为调整

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 管理端口的 HTTP/2 与请求体上限 | 误用主服务器键 | 独立键 `management.server.http2.enabled`、`management.server.max-content-length` |
| 管理端口与主端口相同 | 任一为随机端口（`0`）也判冲突、**阻断启动** | 仅当两者都显式配置为**相同且非 0** 时才报冲突 |
| `RequestDispatcher.forward` | 清空已写响应头并**强制状态 200** | 仅重置缓冲：**保留**已写响应头与状态码；并修正 `FORWARD_SERVLET_PATH`/`FORWARD_PATH_INFO` 颠倒、`FORWARD_QUERY_STRING` 去掉前导 `?` |
| 非 `void` 方法返回 `null` | 不标记 handled → 响应永不 flush（客户端挂到超时） | 视为「无响应体的已完成响应」并标记 handled（`void` 方法行为不变） |
| 普通 `HandlerInterceptor` Bean（`spring-web-mvc-support`） | 自动全局注册 | **不再**自动注册（与 Spring MVC 一致）：经 `addInterceptors` 注册或声明 `MappedInterceptor` |
| `postHandle` / `afterCompletion` 遍历顺序 | 逆向 | 与 `preHandle` **同向（正向）**；`preHandle` 提前返回时未通过者仍按逆向补 `afterCompletion` |
| 畸形 multipart 报文 | 裸异常关闭连接 | **400** 优雅关闭（`TooLongFrameException` 仍为 **413**） |
| 流式响应（`writeStream`） | 在 EventLoop 线程读 `InputStream` → 慢速源造成 I/O **队头阻塞** | 读卸载到业务池（默认池；缺失时用专用兜底池；**绝不回退 EventLoop**），按 8KB 分块写出并以可写性背压 |
| `writeFile` / `writeStream` 的 `Content-Type` | 一律 `application/octet-stream` | 尊重调用方已设置的值；否则按扩展名推导；仍无法定夺时回退 `octet-stream` |
| `getOutputStream()` | 每次返回新实例 | 同一响应返回**同一实例**（Servlet 规范）；`rebind` 后失效重建 |
| JSR-356 `Session.setMaxIdleTimeout` | 仅赋值字段（空闲超时实际不生效） | 传播到 Netty pipeline 的 `IdleStateHandler`（`<=0` 移除空闲检测） |
| WebSocket 单帧上限 | 固定 text 8KB / binary 64KB | 新增 `WebSocketHandlerRegistration#setMessageSizeLimit` 按路径覆盖（默认值不变） |
| `@WebFilter` 的 `urlPatterns` | 读注解不含代理/继承来源 → 静默升格为全局 Filter | 用 `findMergedAnnotation` 沿超类/接口查找；`urlPatterns` 真正限制路径 |

#### 4.9 分帧与 Servlet 重置语义

| 项 | 旧行为 | 新行为 |
|---|---|---|
| **HTTP/1.0** 请求的渐进式输出 | 发送 chunked 帧（1.0 无此分帧，客户端会把分块标记当 body 收下 —— 协议违规） | 退化为 **close-delimited**：响应头不带 `Content-Length` / `Transfer-Encoding`，显式 `Connection: close` |
| 已提交响应上的 `resetBuffer()` / `reset()` | 静默清空缓冲，调用方误以为能收回已发出的内容 | 抛 `IllegalStateException`（Servlet 规范 §5.6） |
| `resetBuffer()` 与 Writer 的编码缓冲 | 只清响应体缓冲 → 「写入后未 flush」的内容仍留在编码缓冲，提交前回调会把它们重新刷出，**丢弃语义失效** | 先把编码缓冲刷入响应体再清空 |

#### 4.10 异步 / SSE 生命周期与引用计数

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 异步期间的入站缓冲存活 | 不在 `startAsync()` 保活；异步阶段读 `content()` 可能读到 0/陈旧值，甚至抛 `IllegalReferenceCountException` | 异步持有者**显式 acquire/release**：`startAsync()` 恰好一次 `+1`，响应**写终结**时恰好一次 `-1`；body 长度改为**构造期缓存** |
| 请求释放时级联释放响应缓冲 | **每次** `release()` 都级联 → 可能释放**业务线程正在写**的响应 buf（跨线程早释放竞态） | 仅在 **refCnt 归零**时级联；响应侧另设兜底点：写终结回调 + 连接关闭时对在途响应 `release()` |
| 客户端断连（空闲 SSE / 未完成异步） | 异步持有者扣留的入站引用**永不归还** | 连接关闭退场路径 `releaseOnConnectionClose()`（由 `channelInactive` 触发）：只做生命周期清理，**不**触发业务错误处理器 |
| reactive 上游订阅取消 | 依赖「下一次投递失败」→ 源不再投递（长轮询挂住）则**永不取消** | 新增「客户端断连」钩子，断连即取消上游订阅 |
| 终止块（`LastHttpContent`）写入权 | `markStreamCompleted()` 为 `void`，谁写谁标记 → 可能出现**双终止块**（`EncoderException`；HEAD + SSE 实测触发） | `markStreamCompleted()` 改 **`boolean` 抢占式**；HEAD 请求**不再**补终止块 |
| `StreamEmitter.send()` 在完成后调用 | 静默进入待发列表 → **已终止的流仍被写出数据** | 抛 `IllegalStateException`（对齐 Spring `ResponseBodyEmitter`） |
| 「交换已结束」后仍写响应体 | 继续分配池化缓冲（内容永远写不出去）→ ByteBuf 泄漏 | `getBuf()` 记 **ERROR** 并返回**非池化**丢弃缓冲；已提交时拒绝写出并**就地释放**（warn） |
| 返回值是 reactive 类型但无解析器认领 | 静默「200 + 空 body」 | 打 **WARN** 提示缺 `ReactiveAdapter` |

#### 4.11 性能优化带来的可观测变化（JFR 驱动，等价性为主）

| 项 | 旧行为 | 新行为 |
|---|---|---|
| **非法配置值** | 热路径键**懒解析**：首次使用时才失败 | `ApplicationProperties` 在 **Environment 就绪时急切解析**（对齐 Boot `@ConfigurationProperties`）：解析失败即 **启动失败**（fail-fast）。运行期配置刷新则逐字段容错：解析失败的字段**保留旧值**并告警 |
| servlet 桥 `AsyncContext.start` 交棒 | 受 `server.http.timeout` 约束 | **显式**配置 `pool.default-execute-mode=eventloop` 时**不再受**其约束，改由 Servlet 自身 async 超时语义负责 |
| 响应超时装配时机 | 每个请求开始即装配定时器 | **按需装配**：eventloop 同步且已提交的请求不再产生 schedule/cancel；长于 `server.http.timeout` 的流式响应不再出现「已提交拒绝写出」WARN |
| `spring.mvc.publish-request-handled-events` 默认值 | `true` | **`false`**（本项目有意关闭；Boot 默认相反）。依赖该事件做监控/审计的需**显式开启** |

#### 4.12 视图层：模板可见变化与新增扩展点

| 项 | 旧行为 | 新行为 |
|---|---|---|
| 模板读取会话数据 | 读不到（`IWebSession` 返回 `null`） | Servlet 场景由 `ServletWebExchangeProvider` 提供**真实** session / principal / cookie；session 属性被**注入上下文变量**（同名时 **model 优先**）→ 模板写 `${user}` 而非 `${session.user}` |
| 模板 `IWebExchange` 的来源 | 内部硬编码构造 | 新增 SPI **`WebExchangeProvider`**：`supports()` + `createExchange()` + `getOrder()`，按 order 升序取第一个 `supports()==true`，内置默认实现兜底。经 Spring 组件容器装配（**不用** JDK `ServiceLoader`，兼容 native-image/AOT） |
| `WebMvcConfigurer#addViewControllers` | **静默忽略**（无告警） | 由 `WebMvcConfigurerBridge` 桥接（`ViewControllerRegistry` / `ViewControllerRegistration` + `ViewControllerInvoker`）；未能桥接的回调打 **WARN** |
| `spring.mvc.view.prefix` / `.suffix` | — | **新增**，语义对齐 Boot：**仅 JSP 视图**的前后缀；模板引擎仍用各自的 `spring.{engine}.prefix/suffix` |
| 会话持久化与编码强制 | — | `server.servlet.session.persistent` / `.store-dir` / `.persistent-exclude`（JDK 序列化，每 session 一文件，不落盘 `transient` 属性）；`server.servlet.encoding.force` / `.force-request` / `.force-response` |
| 配置中心动态刷新 | 改了配置需重启才生效 | 监听 `EnvironmentChangeEvent`（**按类名监听**，不引入 Spring Cloud 编译期依赖）→ 清空框架属性缓存；亦可调用 `WebContext.refreshProperties()` 手动触发 |

---

## [2.7.6] - 2026-09-10

### 一、模块拆分：`spring-web-support` 一分为二

**变更**：`spring-web-support` 不再存在，拆分为

- `spring-web-servlet`：纯 Servlet 桥接（Filter / Servlet / `HttpSession` / JSP / `@SessionAttribute` 等官方 `spring-web` 语义），**零 SpringMVC 依赖**；
- `spring-web-mvc-support`：SpringMVC 兼容桥接（`org.springframework.web.servlet.*` 重写类、`WebMvcConfigurer` / `HandlerInterceptor` / `RequestBodyAdvice` 桥接），**依赖 `spring-web-servlet`**。

starter 的自动配置同步拆为 `SpringWebServletAutoConfiguration` + `SpringWebMvcSupportAutoConfiguration`（按各自模块的 class 条件生效）。

**影响**：直接依赖 `spring-web-support` 的项目无法解析该坐标。

**迁移**：按需要替换坐标 —— 只需要 Servlet 语义（Filter / Session / JSP）时用 `spring-web-servlet`；
继续使用 `org.springframework.web.servlet.*` 重写类或 `WebMvcConfigurer` 时用 `spring-web-mvc-support`。
只依赖 `spring-boot-starter-web`（本框架的 starter）的项目**无需改动**。

### 二、`redirect:` 语义收紧

**变更**：`redirect:` 前缀只在**视图方法**上生效。`@ResponseBody` / `@RestController` 返回字符串
`"redirect:/x"` 不再被劫持为 302，而是按普通字符串序列化；目标 URL 已含 query 时用 `&` 续接而非重复 `?`。

**影响**：此前依赖「返回字符串即 302」写法的接口行为改变 —— JSON 响应体变成 `"redirect:/x"` 字符串。

**迁移**：需要跳转时显式返回状态与 `Location`：

```java
return ResponseEntity.status(302).location(URI.create("/target?a=1")).build();
```

### 三、视图解析失败不再静默

**变更**：无法解析的视图名由「空响应 200」改为抛 `IllegalArgumentException`（500 + 错误上下文），
`ViewResolver` 的解析异常同时记录日志。

**影响**：视图名拼错、模板引擎未引入、前缀配置错误等此前表现为 200 空体，现在表现为 500。

**迁移**：核对 `spring.web.view.*` 前缀 / 后缀与已引入的模板引擎；如确实需要「找不到就空响应」，
显式注册兜底 `ViewResolver`。

### 四、静态资源只接受 GET

**变更**：`ResourceRequestHandler` 仅允许 GET。HEAD 由路由层自动映射到 GET，但只回元数据、**跳过文件 IO**。

**影响**：对静态资源路径发起的其他方法不再由静态资源处理器响应（按常规路由未命中处理）。

**迁移**：不要把静态资源路径当作 POST/PUT 的目标。

### 五、生产级 Netty 与线程池默认值

**变更**：以下默认值由「开发友好」调整为「生产友好」：

| 配置键 | 旧默认 | 新默认 | 说明 |
|--------|--------|--------|------|
| `server.netty.so-backlog` | 128 | **1024** | 连接建立队列，避免突发握手被丢弃 |
| `server.netty.so-keepalive` | false | **true** | 开启 TCP keepalive，回收对端猝死的连接 |
| `server.http.max-content-length` | 1MB | **4MB** | 单请求体上限 |
| `pool.queue-capacity` | 无界 | **100**（有界） | 有界队列使 `pool.max-pool-size` 扩容与「队列满 → 503 快速失败」真正生效 |

**影响**：请求体上限放宽 4 倍；线程池由无界排队（请求无限堆积、延迟上升）改为有界排队（超限直接 503）。

**迁移**：

- 依赖「无界队列削峰」的应用请显式设置 `pool.queue-capacity`（或调大 `pool.max-pool-size`），并确认能接受 503 语义；
- 需要更严格的请求体上限时显式设置 `server.http.max-content-length`。

---

## [2.7.4] - 2026-08-06

### `JsonConverter` 接口新增抽象方法 `toJsonBytes(Object)`

**变更**：`io.springperf.web.json.JsonConverter` 新增 `byte[] toJsonBytes(Object obj)`，
用于跳过中间 `String` 拷贝直接输出字节；框架自带的 Jackson / Fastjson 转换器已实现。

**影响**：自行实现 `JsonConverter` 的外部实现类（含匿名类）会因未实现该抽象方法而**编译失败 / 运行期 `AbstractMethodError`**。

**迁移**：补上实现，委托既有序列化即可：

```java
@Override
public byte[] toJsonBytes(Object obj) {
    return toJson(obj).getBytes(StandardCharsets.UTF_8);
}
```
