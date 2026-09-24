> [English](en/configuration.md) | 中文

# 配置参考

所有配置项在 `application.properties` 中设置。

## 服务器配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | `8080` | Netty HTTP 监听端口 |
| `server.servlet.context-path` | `/` | 应用上下文路径 |
| `server.address` | 无 | 绑定指定网卡 IP（如 `192.168.1.10`）；不配置则绑定全部接口（0.0.0.0）。主端口与管理端口同步生效 |

## HTTP 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.http.max-content-length` | `4194304` (4MB) | 最大请求体大小（字节） |
| `server.http.timeout` | `60000` (60s) | 响应超时（毫秒；`<=0` 表示不限制，对齐 Tomcat `connectionTimeout=0`）：从请求进入管线到响应提交的时限，超时写出 **504**；响应一旦提交（flush / 流式首帧）即取消计时。异步请求自身的超时由 `spring.mvc.async.request-timeout` 控制，写出 **503** |

## 异步配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.mvc.async.request-timeout` | `30s` | 异步请求（DeferredResult/Callable）超时时间（支持 `30s`/`1m`/`1h` 或纯数字毫秒） |

## 文件上传（multipart）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.servlet.multipart.enabled` | `true` | 是否启用 multipart（文件上传）解析 |
| `spring.servlet.multipart.max-file-size` | `-1`（不限） | 单个上传文件大小上限（DataSize，如 `10MB`）；超限返回 **413** |
| `spring.servlet.multipart.max-request-size` | `-1` | 整个 multipart 请求体上限（DataSize）；未配置时回退 `server.http.max-content-length` |
| `spring.servlet.multipart.file-size-threshold` | `-1`（框架默认 16KB） | part 落盘阈值（DataSize）：低于该值留内存，超过写临时文件；显式 `0` 对齐 Boot 的「全部落盘」语义 |
| `spring.servlet.multipart.location` | 无（系统临时目录） | 上传临时文件目录。**容器化部署建议显式指向数据盘**（tmpfs 受限时 `/tmp` 易写满且挤占内存配额） |

> 另有防护型键：`server.http.multipart.max-part-count`（part 总数上限）、`server.http.multipart.max-part-header-size`（单 part header 上限，超限返回 400）、`server.http.max-ranges`（多段 Range 段数上限，默认 `100`=不额外收紧；**有效上限 = min(该值, 100)**，因底层 `HttpRange` 解析器拒绝超过 100 段，故本键用于收紧、不能放宽；`0`=禁止多段、负值=本层不额外限制；超限按 RFC 9110 §14.2 忽略 `Range` 返回整实体）、`server.http.max-pipelined-requests`（单连接上排队等待的同连接请求数上限，默认 `16`；`<=0` 不限制。达上限时**暂停读取**而非关连接/拒绝请求——pipelining 要求响应保序，未读字节留在 socket 缓冲由 TCP 窗口形成背压，队列排空后自动恢复读取）。
>
> **为何 multipart 键分属两个命名空间**：`spring.servlet.multipart.*`（对齐 Boot 的上传配置：大小限制、落盘）与 `server.http.multipart.*`（框架自有的管线防护：part 计数、header 大小）。本框架在 Netty 管线层提前执行 Servlet 容器语义（聚合/校验在进入 Servlet 桥之前完成），故防护型键归入 HTTP 协议层家族。实现分层依据见 [设计决策 ADR 11](internals/19-design-decisions.md)。

## 静态资源

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.mvc.static-path-pattern` | `/**` | 全局静态资源映射前缀 |
| `spring.web.resources.add-mappings` | `false` | 是否自动注册默认静态资源映射（`/**` → `static-locations`）。**默认 false**（保持「静态资源由用户代码注册」的历史行为）；设为 true 时与用户注册的自定义 handler 共存（特异性匹配下用户 pattern 优先命中，对齐 Boot） |
| `spring.web.resources.static-locations` | Boot 默认 4 位置 | 默认静态资源位置（逗号分隔），对齐 Boot：`classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/` |
| `spring.web.resources.cache.period` | 无 | 默认静态资源缓存时长（如 `1h`）；仅作用于自动注册的默认映射 |
| `spring.web.resources.cache.cachecontrol.max-age` | 无 | Cache-Control max-age（优先于 `cache.period`） |

## 编码（server.servlet.encoding.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.servlet.encoding.charset` | `UTF-8` | 请求/响应统一字符集 |
| `server.servlet.encoding.force` | `false` | 同时强制请求与响应的 charset（忽略业务显式 `setCharacterEncoding`） |
| `server.servlet.encoding.force-request` | 继承 `force` | 仅强制请求 charset |
| `server.servlet.encoding.force-response` | 继承 `force` | 仅强制响应 charset |

## 会话（server.servlet.session.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.servlet.session.tracking-modes` | `COOKIE` | 跟踪模式（`COOKIE` / `URL`；`SSL` 本框架 N/A）。`URL` 模式下**不下发**会话 Cookie，改由 `encodeURL()` 写出 `;jsessionid=`，服务端在 Cookie 缺失时回读该值（Servlet 规范 §7.1） |
| `server.servlet.session.persistent` | `false` | 会话持久化到磁盘（重启不丢；JDK 序列化，每 session 一文件） |
| `server.servlet.session.store-dir` | `.perf-sessions` | 持久化目录（`persistent=true` 时生效） |
| `server.servlet.session.persistent-exclude` | 无 | 不落盘的 session 属性名（逗号分隔） |
| `server.servlet.virtual-server-name` | `localhost` | ServletContext 虚拟服务器名 |
| `server.servlet.application-display-name` | 无 | ServletContext 应用显示名（`getServletContextName()`） |
| `server.servlet.context-parameters.*` | 无 | ServletContext 初始化参数显式块：`server.servlet.context-parameters.<name>=bar` 暴露为 `getInitParameter("foo")` |
| `server.servlet.session.cookie.name` | `JSESSIONID` | 会话 Cookie 名 |
| `server.servlet.session.cookie.http-only` | `true` | 禁止脚本读取（`HttpOnly`） |
| `server.servlet.session.cookie.secure` | `false` | 仅通过 HTTPS 下发（`Secure`） |
| `server.servlet.session.cookie.max-age` | `-1`（会话 Cookie） | Cookie 有效期（秒；`-1` = 浏览器会话结束即失效） |
| `server.servlet.session.cookie.domain` | 无 | Cookie 作用域（`Domain`） |
| `server.servlet.session.cookie.same-site` | 无 | `SameSite`：`lax` / `strict` / `none`（大小写不敏感；非法值告警并忽略）。**历史缺陷**：早期版本把值整体大写后交给 Netty 枚举，配置 `strict` 会抛 `IllegalArgumentException`（已修复） |

> **URL 重写与会话 id**：`tracking-modes` 含 `URL` 且请求未携带会话 Cookie 时，容器以 `;jsessionid=<id>` 形式随 URL 传递会话（`encodeURL`/`encodeRedirectURL` 写出，服务端回读）。
>
> **矩阵参数（path parameter）**：路由匹配前会剥离每个路径段内 `;` 之后的内容（对齐 Spring `UrlPathHelper.removeSemicolonContent=true`），故 `/foo;jsessionid=X` 匹配 `/foo`；`getRequestURI()` 仍返回未剥离的原始 URI，`getServletPath()` 返回剥离后的路径（与 Tomcat 一致）。

## 响应压缩（server.compression.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.compression.enabled` | `false` | 是否启用 gzip 响应压缩（关闭时管线不注入压缩器，零运行时开销） |
| `server.compression.mime-types` | 8 项白名单 | 参与压缩的 Content-Type **主类型**白名单（逗号分隔）：`text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json,application/xml` |
| `server.compression.excluded-user-agents` | 无 | User-Agent 正则列表（逗号分隔，大小写不敏感），命中则跳过压缩 |
| `server.compression.min-response-size` | `2KB` | 响应体小于该值不压缩（支持 DataSize 写法）；`FullHttpResponse` 与分块首块均按此判断 |

> 已处理的边界：HEAD 请求不压缩；零拷贝文件响应（`writeFile`）整响应透传不压缩（避免给明文文件体加 `Content-Encoding` 而损坏响应）；静态预压缩 `.gz`（已带 `Content-Encoding`）不二次压缩。gzip 级别固定 6（Spring 规范未暴露该开关，对齐 Netty 默认）。

## 错误响应（server.error.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.error.include-stacktrace` | `never` | 是否暴露异常栈：`never` / `on-param` / `always` |
| `server.error.include-message` | `never` | 是否暴露异常 message：`never` / `on-param` / `always` |
| `server.error.include-binding-errors` | `never` | 是否暴露校验错误：`never` / `on-param` / `always` |
| `server.error.whitelabel.enabled` | `true` | 是否渲染内置 whitelabel HTML 错误页 |
| `server.error.path` | `/error` | 错误页路径/实例标识 |
| `spring.mvc.problemdetails.enabled` | `false` | 错误响应改为 RFC 7807 `application/problem+json` |

## MVC 行为（spring.mvc.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.mvc.throw-exception-if-no-handler-found` | `true` | 无匹配处理器时抛异常（可被 `@ControllerAdvice` 拦截）而非直接 404/405 |
| `spring.mvc.dispatch.error` / `.options` / `.trace` | `true` | 是否将 ERROR / OPTIONS / TRACE 请求分发给处理器（关闭 OPTIONS 时 CORS 预检仍放行） |
| `spring.mvc.publish-request-handled-events` | `false` | 请求完成时是否发布 `ServletRequestHandledEvent`。**本项目有意关闭**（Boot 默认相反为 `true`）：该事件对多数应用无实际用途，默认关闭可省掉每请求一次的事件发布；需监控/审计时显式开启 |
| `spring.mvc.message-codes-resolver-format` | `prefix_error_code` | 校验消息码格式：`prefix_error_code` / `postfix_error_code` |
| `spring.mvc.format.date` / `.time` / `.datetime` | `yyyy-MM-dd` / `HH:mm:ss` / `yyyy-MM-dd'T'HH:mm:ss` | 无 `@DateTimeFormat` 时的默认日期/时间格式（均为 ISO 形态，但各自的模式串不同） |

## 国际化（spring.web.locale.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.web.locale` | 无 | 默认 Locale（如 `zh_CN`） |
| `spring.web.locale-resolver` | `accept-header` | Locale 解析策略：`fixed`（恒用 `spring.web.locale`）/ `accept-header`（按请求头） |
| `spring.web.locale-bind` | `true` | 是否每请求绑定 `LocaleContextHolder`（与 Spring MVC 的 `initContextHolders`/`resetContextHolders` 同范式：保存旧上下文 → 设置 → 复位，含 `threadContextInheritable`；实现见 `SupportDispatcherHandler`）。设为 `false` 时框架完全不触碰 Locale 上下文：省掉每请求的上下文对象分配与 ThreadLocal `set/remove`，此时 `LocaleContextHolder.getLocaleContext()` 返回 `null`、`getLocale()` 按 Spring 语义回退 JVM 默认；不关注 Locale 的 API 服务可关闭（关闭后应用若自行设置该 holder，需自行清理） |

## 访问日志（server.accesslog.*）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.accesslog.enabled` | `false` | 是否启用访问日志 Filter |
| `server.accesslog.format` | 见源码 | 日志格式（`%h`/`%m`/`%U`/`%T`/`%s`/`%u`） |
| `server.accesslog.directory` | 无 | 落盘目录；不配置则仅走 SLF4J |
| `server.accesslog.prefix` / `.suffix` | `access` / `.log` | 文件名前后缀 |
| `server.accesslog.rotate` | `true` | 是否按天轮转 |
| `server.accesslog.max-days` | `7` | 轮转文件保留天数（`<=0` 不限） |

## 连接与限制（server.* 防护）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.max-connections` | `0`（不限） | 最大连接数（过载保护）；超限时直接断连（不 `fireChannelActive`，无法返回 503） |
| `server.max-parameter-count` | `10000` | 参数总数上限（防 hash DoS） |
| `server.max-http-request-header-size` | `8192` | 合并请求头大小上限 |
| `server.max-http-response-header-size` | `8192` | 响应头总大小上限（超限降级最小 500） |
| `server.max-swallow-size` | `2MB` | 错误响应后吞掉请求体上限（负数不限） |
| `server.keep-alive-timeout` / `server.max-keep-alive-requests` | `0`（不限） / `100` | keep-alive 空闲超时 / 单连接请求数上限 |
| `server.forward-headers-strategy` | `NONE` | 转发头信任策略：`NONE`/`FALSE` 不信任，`FRAMEWORK`/`NATIVE` 信任 |
| `server.http.max-in-memory-size` | `4096` | 请求体内存聚合上限（超出转 ByteBuf） |
| `server.http.max-chunk-size` | `8192` | 单 chunk 大小上限 |
| `server.http.max-initial-line-length` | `4096` | 请求起始行长度上限 |
| `server.http.max-pipelined-requests` | `16` | 同连接 pipelined 请求的排队上限；达上限**暂停读取**（`autoRead=false`）而非断连，未读字节留在 socket 缓冲，由 TCP 窗口对客户端形成背压 |
| `server.http.max-ranges` | `100` | 单个请求允许的 Range 段数上限；`0` 表示禁止多段（多段请求回退整实体），负值表示本层不额外限制 |
| `server.http.multipart.max-part-count` | `-1` | multipart part 总数上限（`<=0` 不限）。**默认值与 Boot 不同**：Boot `server.tomcat.max-part-count` 为 `50`；键语义相同（非正数即关闭该上限） |
| `server.http.multipart.max-part-header-size` | `8192` | 单个 multipart part 的 header 字节上限（`<=0` 不限），超限由增量扫描抛 `DecoderException` → **400**。**默认值与 Boot 不同**：Boot/Tomcat 的同名键默认 `512B`，本项目为 `8192` |
| `server.http.read-timeout` | `30000` | 读**空闲**超时（支持 `30s` 写法；`<=0` 关闭）。只约束读空闲：**正在处理的请求不会被掐断**（慢 SQL / 下游调用 / 异步挂起的处理器耗时可超过该值，响应仍会送达）；仅回收真正空闲的连接与半截请求，语义对齐 Tomcat `connectionTimeout` |

> **完整清单**：以上为常用项。全部已支持键由 `SupportedPropertiesTest` 维护并校验——新增配置键若未登记到
> `META-INF/additional-spring-configuration-metadata.json`，构建会失败。运行
> `mvn -pl spring-web test -Dtest=SupportedPropertiesTest -Dperf.props.dump=dump.txt` 可导出完整清单。

## 优雅关闭

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.shutdown.grace-period` | `30s` | 优雅关闭最大等待时间（支持 `30s`/`1m`/`1h` 或纯数字毫秒） |

## 启动配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.check-on-startup` | `true` | 启动时校验所有 Mapping（fail-fast） |

## Netty 调优

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.netty.workers` | `0` (自动) | Netty worker EventLoop 线程数，0 表示自动计算（CPU 核数 × 2） |
| `server.netty.write-buffer-low-watermark` | `8192` (8KB) | 写缓冲区低水位（字节） |
| `server.netty.write-buffer-high-watermark` | `32768` (32KB) | 写缓冲区高水位（字节） |
| `server.netty.transport` | `auto` | Netty transport 类型：`auto`（Linux 上自动用 native epoll，其他平台回退 NIO）、`nio`（强制 Java NIO）、`epoll`（强制 native epoll，不可用则启动失败） |
| `server.netty.so-backlog` | `1024` | TCP listen backlog（生产 Linux 高并发建议值） |
| `server.netty.so-keepalive` | `true` | 是否启用 TCP keepalive（生产长连接友好） |
| `server.netty.tcp-nodelay` | `true` | 禁用 Nagle 算法，降低小包延迟 |
| `server.netty.so-reuseaddr` | `true` | 允许端口重用 |
| `server.netty.boss-threads` | `1` | boss EventLoop 线程数（负责接收连接） |
| `server.netty.allocator-type` | `pooled` | ByteBuf 分配器类型（`pooled` 复用 ByteBuf 以降低 GC） |

## HTTP/2

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.http2.enabled` | `false` | 启用 HTTP/2 支持（浏览器客户端需配合 SSL） |

## 业务线程池

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `pool.core-pool-size` | `50` | 核心线程数 |
| `pool.max-pool-size` | `200` | 最大线程数 |
| `pool.keep-alive-time` | `60` | 空闲线程存活时间（秒） |
| `pool.queue-capacity` | `100` | 任务队列容量。默认有界：使 `maxPoolSize` 生效，满员时返回 503 而非无限排队（≤0 时设为 1） |
| `pool.default-execute-mode` | `default` | 无 `@RunInPool` 时方法的执行位置。`eventloop`=EventLoop，其他值为线程池名称 |
| `spring.threads.virtual.enabled` | `false` | JDK 21+：设为 `true` 时框架密集执行点改用虚拟线程——`default` 业务池与 batch 的批量方法（`@BatchMapping`）均以虚拟线程执行（只替换线程类型：`pool.*` 上限/队列语义与 batch 的 `consumerSize` 上限、背压语义均保持不变）；JDK < 21 时启动告警并回落平台线程 |

## SSL 配置

标准 Spring Boot SSL 配置：

```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

管理端口也支持独立 SSL 配置（前缀 `management.server.ssl.*`），配置方式同上。

## 管理端口（Actuator）

```properties
# 启用独立管理端口
management.server.port=9090
# 管理端点基础路径
management.endpoints.web.base-path=/actuator
# 暴露的端点
management.endpoints.web.exposure.include=health,info,metrics
# 管理端口独立的 HTTP/2 与请求体上限（前缀 management.server.*；未配置时用默认值，
# 不再沿用主服务器 server.* 的值）
management.server.http2.enabled=false
management.server.max-content-length=4194304
```

## 可观测性指标

当 `spring-boot-starter-actuator` 在类路径上时，框架自动注册 `MicrometerWebMetrics`，收集以下指标：

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `dispatcher.request.duration` | Timer | `method`, `path`, `status` | 请求处理耗时分布 |
| `dispatcher.exception` | Counter | `type`, `resolved` | 异常计数，按异常类型和是否被 `@ExceptionHandler` 处理分类 |
| `pool.{name}.active.threads` | Gauge | — | 指定线程池的活跃线程数 |
| `pool.{name}.queue.size` | Gauge | — | 指定线程池的排队任务数 |
| `pool.{name}.completed.tasks` | Gauge | — | 指定线程池的已完成任务数 |
| `netty.connections.active` | Gauge | — | 当前活跃 TCP 连接数 |
| `netty.eventloop.pending.tasks` | Gauge | — | Netty EventLoop 待处理任务总数 |

`{name}` 为线程池名称，对应 `register()` 时传入的名称或 Spring Bean 名称。

## 视图渲染（spring-web-view）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.web.view.engine` | 无（全部可用） | 启用的模板引擎列表（逗号分隔）：`thymeleaf` / `freemarker` / `beetl`；**不配置则注册所有 classpath 上可用的引擎** |
| `spring.thymeleaf.prefix` | `templates/` | Thymeleaf 模板前缀（classpath 相对路径） |
| `spring.thymeleaf.suffix` | `.html` | Thymeleaf 模板后缀 |
| `spring.thymeleaf.cache` | `true` | 是否开启模板缓存（开发可设 `false` 热改） |
| `spring.freemarker.prefix` | `templates/` | FreeMarker 模板前缀（classpath 相对路径） |
| `spring.freemarker.suffix` | `.ftl` | FreeMarker 模板后缀 |
| `spring.freemarker.cache` | `true` | 是否开启模板缓存 |
| `spring.beetl.prefix` | `templates/` | Beetl 模板前缀（classpath 相对路径） |
| `spring.beetl.suffix` | `.btl` | Beetl 模板后缀 |
| `spring.beetl.cache` | `true` | 是否开启模板缓存 |
| `spring.thymeleaf.encoding` | `UTF-8` | 渲染字符集，同时写入 `Content-Type`；FreeMarker / Beetl 默认 UTF-8（与 Boot 行为一致，无独立编码键） |
| `spring.mvc.view.prefix` | `/jsp/` | **仅 JSP 视图**的前缀（对齐 Boot 的 JSP 语义）；模板引擎用各自的 `spring.{engine}.prefix` |
| `spring.mvc.view.suffix` | `.jsp` | **仅 JSP 视图**的后缀 |

> 详细用法见 [视图渲染文档](view.md)。

## OpenAPI 文档

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `springperf.openapi.title` | `Spring Perf Web API` | API 文档标题 |
| `springperf.openapi.version` | `1.0.0` | API 文档版本 |
| `springperf.openapi.description` | `Spring Perf Web API` | API 文档描述 |

## Swagger UI

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `springperf.swagger-ui.webjar-version` | `5.2.0` | Swagger UI webjar 版本，需匹配 `org.webjars:swagger-ui` 依赖版本 |

## 完整配置示例

```properties
# 服务器
server.port=8080
server.servlet.context-path=/api

# HTTP
server.http.max-content-length=5242880
server.http.timeout=15000

# 异步
spring.mvc.async.request-timeout=30s

# 优雅关闭
server.shutdown.grace-period=30s

# SSL
server.ssl.enabled=false

# Netty
server.netty.workers=0
server.netty.write-buffer-low-watermark=8192
server.netty.write-buffer-high-watermark=32768

# HTTP/2
server.http2.enabled=false

# 线程池
pool.core-pool-size=100
pool.max-pool-size=500
pool.keep-alive-time=120
pool.queue-capacity=10000
pool.default-execute-mode=eventloop  # 或线程池名称

# 启动校验
server.check-on-startup=true

# 视图渲染
spring.web.view.engine=thymeleaf
spring.thymeleaf.prefix=templates/
spring.thymeleaf.suffix=.html
spring.thymeleaf.cache=true
spring.thymeleaf.encoding=UTF-8

# 管理端口
management.server.port=9090
management.endpoints.web.exposure.include=health,info

# OpenAPI
springperf.openapi.title=My API
springperf.openapi.version=2.0.0
springperf.openapi.description=My API Description
```
