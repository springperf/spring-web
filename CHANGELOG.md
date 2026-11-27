# 变更日志

> [English Version](docs/en/changelog.md)

本项目遵循 [语义化版本控制](https://semver.org/lang/zh-CN/)。

> 本日志在 `2.7.x` / `master` / `4.1.x` 三分支同步维护：每个分支都保留全部版本的变更记录，便于跨分支查阅与同步；各节内容保持其所属分支的语境。

## [4.1.8] - 20261127
> **关于本版本**：将 master 3.5.8 的变更同步到 Spring Boot 4.1 / Spring Framework 7 / Jackson 3 基线；除版本基线与本分支新增的 4.2 兼容外，条目与 master 的 3.5.8 一致。
> ⚠️ **本版含破坏性变更**：Servlet 规范语义、视图错误与内容协商、部分配置项的校验方式均有调整，
> **升级前请先读 [破坏性变更说明](docs/breaking-changes.md) 的 4.1.8 节**。

### 新增

- **Native 工具链基线**：4.1.x 的 native smoke 门禁改用 GraalVM 25 + Native Build Tools 1.1.18（Spring Boot 4 官方要求），master/Boot 3.5.x 仍用 GraalVM 17；此前 GraalVM 17 下 AOT 反射提示不生效，原生启动报 `ServerPortInfoApplicationContextInitializer` 的 `ClassNotFoundException`
- **Spring Boot 4.2 可选兼容**：新增 `-Pspring-boot-4.2` profile（Boot 4.2.0 / Spring Framework 7.1 / Jackson 3.1.8 / Netty 4.2.20.Final），默认基线仍为 Boot 4.1.0，不切换默认依赖。CI 的 `spring-boot-versions`、`boot4-compat` 与 E2E 矩阵均追加 4.2 并保留 4.0 / 4.1。4.2 已将 Boot 4.0 阶段弃用的 API 移除、`RestTemplate` / `TestRestTemplate` 标记为弃用（未移除，现有跨版本 shim 仍可用），本分支代码无需改动即可在 4.2 下编译并通过核心测试与 E2E 冒烟
- **Servlet / Filter 动态注册**：`ServletContext.addServlet()` / `addFilter()` 现在按 Servlet 规范返回 `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic`，可配置 URL 映射、初始化参数、`load-on-startup`、`async-supported` 等；注册仅在 `ServletContextListener#contextInitialized()` 期间开放，窗口关闭后的写操作抛 `IllegalStateException`，重名与 URL 冲突也会明确报错。`addJspFile()` 仍不支持（JSP 由 `jsp:` 视图统一路由），`createServlet()` / `createFilter()` 可用
- **Servlet 监听器运行期注册**：`ServletContext.addListener()` 现在真正生效，支持 `ServletContextListener`、`HttpSessionListener`、`HttpSessionAttributeListener` 与 `HttpSessionIdListener`；会话 ID 轮换会通知 ID 监听器。运行期注册的监听器只收到注册之后发生的事件，需要完整生命周期回调的监听器请声明为 Spring Bean
- **JSP 生产默认配置**：新增 `server.servlet.jsp.development`（默认 `false`，改动 JSP 需重启生效）、`keep-generated`、`trim-spaces` 与 `init-parameters.*`（透传 Jasper，显式项优先）；Jasper 实例改由 Spring 管理，JSP 与标签类可获得依赖注入
- **WebSocket 压缩扩展**：`setAllowExtensions(true)` 现在真正协商 `permessage-deflate`（此前是空开关，未协商的压缩帧会被当明文交给业务）；协商结果可通过 `getExtensions()` / `Session#getNegotiatedExtensions()` 读取，按端点设置的 `messageSizeLimit` 也随之生效
- **WebSocket JSR-356 语义补齐**：`RemoteEndpoint.Basic` 为同步发送；`getSendStream()` / `getSendWriter()` 缓冲后按整条消息发出；`setBatchingAllowed()` / `flushBatch()` 真正攒批；`Session.addMessageHandler()` 支持动态注册；自定义 `Configurator` 的 `getEndpointInstance()` / `checkOrigin()` 生效；Ping / Pong 帧现在携带应用数据，超 125 字节按规范拒绝
- **批处理攒批时间窗口**：`@BatchMapping` 新增 `batchWindowMillis`（默认 `2`ms，设为 `0` 可关闭）。窗口内持续累积，直到达到 `maxBatchSize` 或窗口到期才提交，批大小由配置保证而非依赖业务池偶然饱和；中高并发下 IO 次数成倍下降、吞吐同时提升。低负载（在途请求撑不满一批）时窗口只是等待成本，建议显式关闭，取值建议见 [批处理文档](docs/batch.md)
- **批处理全局默认值**：新增 `springperf.batch.*`（`window-millis` / `max-batch-size` / `consumer-size` / `ring-buffer-size` / `wait-strategy` / `backpressure`），多个队列统一调优不必逐个改注解。优先级为「注解显式声明 > 全局键 > 注解内置默认值」，仅启动期读取一次；取值非法直接启动失败并点名键名与可选值，启动日志打印生效值与实际配置的键，不静默回落。已知边界：注解显式写回内置默认值时与「未声明」不可区分，此时以全局键为准。详见[批处理配置参考](docs/batch.md#配置参考)
- **视图运行期缓存失效**：新增 `ViewResolverRegistry.invalidateViewCaches()`，运行期投放或替换模板后调用即可生效（同时清理 Thymeleaf / FreeMarker / Beetl 内部模板缓存）；视图解析结果按视图名缓存，`cache=false` 仍保留热改
- **视图配置**：新增 `spring.{thymeleaf,freemarker,beetl}.order`、`spring.web.view.jsp.order`（引擎解析顺序确定且可配）与 `spring.freemarker.enabled`；新增可选的 `spring.web.view.redirect-hosts` 外部重定向 host 白名单；移除从未被读取的 `spring.beetl.mode`
- **自动配置开关**：新增 `springperf.openapi.enabled`、`springperf.swagger-ui.enabled`（可分别关闭文档端点与 Swagger UI 资源）、`springperf.web.mvc-conflict-check.enabled`（三方库传递引入 Spring MVC 时可跳过启动期冲突检查）
- **管理端口与 SSL 配置**：`management.server.*` 统一按「管理端口专属值 → 主服务器 `server.*` → 默认值」解析，HTTP/2、请求体上限、SSL 等可按需覆盖（SSL 开关同样遵循该回退）；SSL 配置改用 Spring Boot 的 `Ssl` 模型绑定，非法 `client-auth`、空 keystore 或缺失凭据在启动期直接报错
- **服务就绪时机**：`WebServerInitializedEvent` 改为在 Netty 实际绑定端口后发布，服务注册组件（Nacos / Eureka / Consul）感知就绪的时机更准确
- **`StreamingResponseBody` 支持**：返回 `StreamingResponseBody`（含 `ResponseEntity<StreamingResponseBody>`）现在会真正流式写出——`writeTo` 在业务线程池执行，字节按 8KB 分帧经既有流式写出通道送达，客户端可边写边读；`produces` 声明了具体类型时写入该 `Content-Type`，HEAD 只发响应头。此前该类型无解析器，按内容协商失败返回 500

### 变更

- **Servlet 规范语义收紧**：同一异步周期内重复 `startAsync()` 抛 `IllegalStateException`；`complete()` 幂等、`isAsyncStarted()` 在 `complete()` / `dispatch()` 后返回 `false`；`dispatch()` 期间 `DispatcherType` 为 `ASYNC`；`addListener(listener, req, resp)` 的事件携带注册时的 req/resp；`getHeaders(name)` 无该头返回空集合；`getDateHeader()` 对存在但无法解析的日期抛 `IllegalArgumentException`；`setAttribute(name, null)` 等价 `removeAttribute`；`setSessionTrackingModes()` 现在生效并按规范校验（集合非空且必须含 `COOKIE` 或 `SSL`）
- **视图错误不再静默**：视图解析异常保留原始 cause 不再被跳过；`ModelAndView` 指向不存在的视图抛异常而非 200 空白页；渲染中途抛异常不再返回 200 + 半截 HTML，改为清空缓冲后交给异常处理器（500 或自定义错误页）；`redirect:` 不再带 `text/html`，并保留 `ModelAndView` 指定的 3xx 状态码
- **内容协商失败显式报错**：无匹配转换器的 `@ResponseBody` 返回值一律 500（原先静默返回 200 + 空响应体），同时覆盖「已显式设置 `Content-Type`」与「`produces` 协商出类型」两条链路——此前只做了前者，走 `produces` 的返回值仍被静默丢弃，连 `produces` 声明的 Content-Type 都不写。与 Spring MVC 语义一致
- **视图渲染热路径降分配**：Thymeleaf 无 session 时上下文直接读取 model，不再每请求复制一份 `HashMap`；application 属性 Map 改为首次写入时创建；FreeMarker 解析缓存按 locale 分桶，去掉每请求拼接 `viewName + locale` 键；Content-Type 解析结果与 `redirect:` 的 `RedirectView` 均复用，避免重复解析/分配
- **视图输出不再分配 8 KB 编码缓冲**：三个模板引擎改为分块编码直写响应体，不再为每次渲染创建固定 8 KB 的编码缓冲；实测每请求分配 Thymeleaf 30.3 KB → 21.9 KB、FreeMarker 24.6 KB → 17.3 KB（同一 JFR 口径，含示例应用自身日志开销）。编码语义与 `OutputStreamWriter` 保持一致（不可映射字符按 REPLACE、`close()` 关闭底层流、跨块代理对不丢字符）
- **外部重定向白名单不再每请求解析**：`spring.web.view.redirect-hosts` 的解析结果按配置串缓存，配置变更后立即生效；3 个 host 的白名单每请求分配从约 660 B 降到约 5 B
- **配置启动期校验**：`spring.web.view.engine` 的未知值、`none` 与其他引擎混写、`spring.thymeleaf.mode` / `spring.freemarker.content-type` 非法值、以及视图引擎 `cache` / `enabled` 非 `true` / `false` 的值，均改为启动期报错而非静默忽略；`engine=none` 时不再装配内置 JSP（自定义 `ViewResolver` Bean 不受影响）
- **批处理默认启用攒批窗口**：相比 4.1.7，未显式配置的 `@BatchMapping` 现在默认按 2ms 窗口攒批（批大小由窗口保证，单请求延迟上界增加约 2ms）。若负载很低、希望保留旧版「消费者追上空队列即刷出」的行为，显式写 `batchWindowMillis = 0`；启动期提示也改为仅在显式关闭时出现
- **API 收敛（二进制不兼容）**：`SpringHeadersAdapter`（WebSocket 头适配器）随拆分重构移除，`NettyWebSocketSession` 构造器改为直接接收 Spring 的 `org.springframework.http.HttpHeaders`；从未被读取的 `ViewProperties.BEETL_MODE` / `BEETL_MODE_DEFAULT` 常量随 `spring.beetl.mode` 一起删除；`WebServerInitializedEventAutoConfiguration#webServerInitializedEventPublisher(...)` 签名变更（去掉 `NettyHttpServer` 参数，事件改由端口绑定后的 `NettyServerStartedEvent` 携带）。迁移写法见[破坏性变更说明](docs/breaking-changes.md)的 4.1.8 节
- **流式队列容量可配置且默认下调**：新增 `server.http.stream-max-queued-events`（默认 `4096`），SSE / 字节流发送器的入队缓冲由硬编码 `65536` 改为建连时按配置解析。JCTools 数组一次性分配，每连接由约 256KB 降到约 16KB（1000 长连接实测 251.9MB → 16.7MB）；A/B 基准每请求分配 314.7KB → 95.8KB（−69.6%），吞吐无劣化迹象。非 2 的幂向上取整，小于 2 时启动失败；同时修复降容量后同步 Publisher 在 `highWaterMark` 超过容量时入队自旋等待同栈 drain、最终 30s 超时的问题

### 修复

- **异步超时稳定按 503 收尾**：`WebAsyncTask` / `Callable` 超时后，被取消任务抛出的 `InterruptedException` 会与超时结果抢写响应槽——客户端可能拿到 500，任务若不响应中断甚至会拿到迟到的 200 + 正常结果，超时等于失效。现在超时一旦到点，此后到达的任务结果一律丢弃，按 4.1.7 起的约定稳定返回 503
- **攒批小窗口按配置生效**：`batchWindowMillis` 设为 2~5ms 这类小值时，到期会被定时器粒度放大——快 IO 场景吞吐不升反降、低负载请求白等。修复后窗口按标称值触发，`maxBatchSize` 的攒批效果真正兑现
- **会话 Cookie**：Servlet 默认会话 Cookie（`maxAge=-1`）不再被写成 `Max-Age=-1` / `Expires=<now>` 而立即过期
- **持久化会话**：启用文件会话存储时，落盘不再运行在网络线程上拖慢同一线程上的其他连接；停机时最多等待 5 秒把在途保存写完
- **405 与关闭中请求**：405 响应的 `Allow` 头汇总所有可用方法；优雅关闭期间被拒的请求返回 503
- **批处理停机与完成兜底**：停机 drain 加 5 秒上界（此前可能无限挂起）；业务线程池已关闭时被拒的批次逐条报错而非静默丢弃；批量方法返回后仍未完成的请求立即失败，不再挂到 30 秒超时
- **HTTP 日期头**：支持 RFC 850 / 1036 的两位年份日期（此前被判为无法解析）
- **转发与包含**：`INCLUDE_QUERY_STRING` 按规范只放 query string、`INCLUDE_SERVLET_PATH` 去掉 query；`INCLUDE_REQUEST_URI` 改为目标资源 URI（contextPath + 目标路径，此前误写原始请求 URI），补上 Servlet 4.0+ 的 `INCLUDE_MAPPING` / `FORWARD_MAPPING`，include 结束后不再残留 `INCLUDE_*` 属性（嵌套 include 恢复外层值）
- **HTTP/2**：修复 h2c preface 分片时的空指针
- **上传与流式响应**：并发解析 multipart 不再产生重复条目；关闭上传流、HEAD 请求、响应头超限降级等路径不再泄漏流、文件句柄或缓冲区
- **HEAD 请求访问流式端点**：`HEAD` 命中 SSE / 流式返回值的处理器时，响应已按 HEAD 语义以「仅响应头」收尾，此前却仍启动发送器并向已结束的响应写内容帧，每次请求打出一条 `EncoderException: unexpected message type: DefaultHttpContent, state: 0` 的 ERROR 日志（并把上游订阅取消掉）。现在 HEAD 不再启动流，响应头照常返回
- **上传异常路径的 direct memory 泄漏**：上传解析失败、或声明长度超限而被拒绝时，约等于请求体大小的直接内存不会被回收，长时间运行会持续上涨；同时超限上传此前可能拿到连接异常而非 413
- **异步请求的内存泄漏**：客户端在异步（含 SSE）真正开始前就断开时，该请求的缓冲与异步生命周期都不会被释放，表现为接口长期运行后直接内存只涨不降
- **WebSocket 帧处理**：分片帧按 RFC 6455 聚合后再交给业务；各种断连路径的关闭回调只触发一次；握手异常时释放请求；二进制发送不再消耗调用方 `ByteBuffer`；单帧消息与批量发送同样受大小限制和背压水位约束
- **重定向与编码**：`//host` 协议相对目标不再拼 context-path；https 无显式端口时模板取到 443（绝对 URL 正确）；带后缀的视图名三个引擎统一支持；自定义 Content-Type 未带 charset 时补上响应实际编码

### 安全

- **SSE 协议注入**：`id` / `event` 字段中的换行在写入前拒绝，避免上游值伪造额外 SSE 字段（此前依赖 Spring 补丁版本的行为）
- **外部重定向白名单**：可选 `spring.web.view.redirect-hosts` 限制外部重定向目标，可阻断含 userinfo 混淆的地址
## [2.7.8] - 20261126
> ⚠️ **本版含破坏性变更**：Servlet 规范语义、视图错误与内容协商、部分配置项的校验方式均有调整，
> **升级前请先读 [破坏性变更说明](docs/breaking-changes.md) 的 2.7.8 节**。

### 新增

- **Servlet / Filter 动态注册**：`ServletContext.addServlet()` / `addFilter()` 现在按 Servlet 规范返回 `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic`，可配置 URL 映射、初始化参数、`load-on-startup`、`async-supported` 等；注册仅在 `ServletContextListener#contextInitialized()` 期间开放，窗口关闭后的写操作抛 `IllegalStateException`，重名与 URL 冲突也会明确报错。`addJspFile()` 仍不支持（JSP 由 `jsp:` 视图统一路由），`createServlet()` / `createFilter()` 可用
- **Servlet 监听器运行期注册**：`ServletContext.addListener()` 现在真正生效，支持 `ServletContextListener`、`HttpSessionListener`、`HttpSessionAttributeListener` 与 `HttpSessionIdListener`；会话 ID 轮换会通知 ID 监听器。运行期注册的监听器只收到注册之后发生的事件，需要完整生命周期回调的监听器请声明为 Spring Bean
- **JSP 生产默认配置**：新增 `server.servlet.jsp.development`（默认 `false`，改动 JSP 需重启生效）、`keep-generated`、`trim-spaces` 与 `init-parameters.*`（透传 Jasper，显式项优先）；Jasper 实例改由 Spring 管理，JSP 与标签类可获得依赖注入
- **WebSocket 压缩扩展**：`setAllowExtensions(true)` 现在真正协商 `permessage-deflate`（此前是空开关，未协商的压缩帧会被当明文交给业务）；协商结果可通过 `getExtensions()` / `Session#getNegotiatedExtensions()` 读取，按端点设置的 `messageSizeLimit` 也随之生效
- **WebSocket JSR-356 语义补齐**：`RemoteEndpoint.Basic` 为同步发送；`getSendStream()` / `getSendWriter()` 缓冲后按整条消息发出；`setBatchingAllowed()` / `flushBatch()` 真正攒批；`Session.addMessageHandler()` 支持动态注册；自定义 `Configurator` 的 `getEndpointInstance()` / `checkOrigin()` 生效；Ping / Pong 帧现在携带应用数据，超 125 字节按规范拒绝
- **批处理攒批时间窗口**：`@BatchMapping` 新增 `batchWindowMillis`（默认 `2`ms，设为 `0` 可关闭）。窗口内持续累积，直到达到 `maxBatchSize` 或窗口到期才提交，批大小由配置保证而非依赖业务池偶然饱和；中高并发下 IO 次数成倍下降、吞吐同时提升。低负载（在途请求撑不满一批）时窗口只是等待成本，建议显式关闭，取值建议见 [批处理文档](docs/batch.md)
- **批处理全局默认值**：新增 `springperf.batch.*`（`window-millis` / `max-batch-size` / `consumer-size` / `ring-buffer-size` / `wait-strategy` / `backpressure`），多个队列统一调优不必逐个改注解。优先级为「注解显式声明 > 全局键 > 注解内置默认值」，仅启动期读取一次；取值非法直接启动失败并点名键名与可选值，启动日志打印生效值与实际配置的键，不静默回落。已知边界：注解显式写回内置默认值时与「未声明」不可区分，此时以全局键为准。详见[批处理配置参考](docs/batch.md#配置参考)
- **视图运行期缓存失效**：新增 `ViewResolverRegistry.invalidateViewCaches()`，运行期投放或替换模板后调用即可生效（同时清理 Thymeleaf / FreeMarker / Beetl 内部模板缓存）；视图解析结果按视图名缓存，`cache=false` 仍保留热改
- **视图配置**：新增 `spring.{thymeleaf,freemarker,beetl}.order`、`spring.web.view.jsp.order`（引擎解析顺序确定且可配）与 `spring.freemarker.enabled`；新增可选的 `spring.web.view.redirect-hosts` 外部重定向 host 白名单；移除从未被读取的 `spring.beetl.mode`
- **自动配置开关**：新增 `springperf.openapi.enabled`、`springperf.swagger-ui.enabled`（可分别关闭文档端点与 Swagger UI 资源）、`springperf.web.mvc-conflict-check.enabled`（三方库传递引入 Spring MVC 时可跳过启动期冲突检查）
- **管理端口与 SSL 配置**：`management.server.*` 统一按「管理端口专属值 → 主服务器 `server.*` → 默认值」解析，HTTP/2、请求体上限、SSL 等可按需覆盖（SSL 开关同样遵循该回退）；SSL 配置改用 Spring Boot 的 `Ssl` 模型绑定，非法 `client-auth`、空 keystore 或缺失凭据在启动期直接报错
- **服务就绪时机**：`WebServerInitializedEvent` 改为在 Netty 实际绑定端口后发布，服务注册组件（Nacos / Eureka / Consul）感知就绪的时机更准确
- **`StreamingResponseBody` 支持**：返回 `StreamingResponseBody`（含 `ResponseEntity<StreamingResponseBody>`）现在会真正流式写出——`writeTo` 在业务线程池执行，字节按 8KB 分帧经既有流式写出通道送达，客户端可边写边读；`produces` 声明了具体类型时写入该 `Content-Type`，HEAD 只发响应头。此前该类型无解析器，按内容协商失败返回 500

### 变更

- **Servlet 规范语义收紧**：同一异步周期内重复 `startAsync()` 抛 `IllegalStateException`；`complete()` 幂等、`isAsyncStarted()` 在 `complete()` / `dispatch()` 后返回 `false`；`dispatch()` 期间 `DispatcherType` 为 `ASYNC`；`addListener(listener, req, resp)` 的事件携带注册时的 req/resp；`getHeaders(name)` 无该头返回空集合；`getDateHeader()` 对存在但无法解析的日期抛 `IllegalArgumentException`；`setAttribute(name, null)` 等价 `removeAttribute`；`setSessionTrackingModes()` 现在生效并按规范校验（集合非空且必须含 `COOKIE` 或 `SSL`）
- **视图错误不再静默**：视图解析异常保留原始 cause 不再被跳过；`ModelAndView` 指向不存在的视图抛异常而非 200 空白页；渲染中途抛异常不再返回 200 + 半截 HTML，改为清空缓冲后交给异常处理器（500 或自定义错误页）；`redirect:` 不再带 `text/html`，并保留 `ModelAndView` 指定的 3xx 状态码
- **内容协商失败显式报错**：无匹配转换器的 `@ResponseBody` 返回值一律 500（原先静默返回 200 + 空响应体），同时覆盖「已显式设置 `Content-Type`」与「`produces` 协商出类型」两条链路——此前只做了前者，走 `produces` 的返回值仍被静默丢弃，连 `produces` 声明的 Content-Type 都不写。与 Spring MVC 语义一致
- **视图渲染热路径降分配**：Thymeleaf 无 session 时上下文直接读取 model，不再每请求复制一份 `HashMap`；application 属性 Map 改为首次写入时创建；FreeMarker 解析缓存按 locale 分桶，去掉每请求拼接 `viewName + locale` 键；Content-Type 解析结果与 `redirect:` 的 `RedirectView` 均复用，避免重复解析/分配
- **视图输出不再分配 8 KB 编码缓冲**：三个模板引擎改为分块编码直写响应体，不再为每次渲染创建固定 8 KB 的编码缓冲；实测每请求分配 Thymeleaf 30.3 KB → 21.9 KB、FreeMarker 24.6 KB → 17.3 KB（同一 JFR 口径，含示例应用自身日志开销）。编码语义与 `OutputStreamWriter` 保持一致（不可映射字符按 REPLACE、`close()` 关闭底层流、跨块代理对不丢字符）
- **外部重定向白名单不再每请求解析**：`spring.web.view.redirect-hosts` 的解析结果按配置串缓存，配置变更后立即生效；3 个 host 的白名单每请求分配从约 660 B 降到约 5 B
- **配置启动期校验**：`spring.web.view.engine` 的未知值、`none` 与其他引擎混写、`spring.thymeleaf.mode` / `spring.freemarker.content-type` 非法值、以及视图引擎 `cache` / `enabled` 非 `true` / `false` 的值，均改为启动期报错而非静默忽略；`engine=none` 时不再装配内置 JSP（自定义 `ViewResolver` Bean 不受影响）
- **批处理默认启用攒批窗口**：相比 2.7.7，未显式配置的 `@BatchMapping` 现在默认按 2ms 窗口攒批（批大小由窗口保证，单请求延迟上界增加约 2ms）。若负载很低、希望保留旧版「消费者追上空队列即刷出」的行为，显式写 `batchWindowMillis = 0`；启动期提示也改为仅在显式关闭时出现
- **API 收敛（二进制不兼容）**：`SpringHeadersAdapter`（WebSocket 头适配器）随拆分重构移除，`NettyWebSocketSession` 构造器改为直接接收 Spring 的 `org.springframework.http.HttpHeaders`；从未被读取的 `ViewProperties.BEETL_MODE` / `BEETL_MODE_DEFAULT` 常量随 `spring.beetl.mode` 一起删除；`WebServerInitializedEventAutoConfiguration#webServerInitializedEventPublisher(...)` 签名变更（去掉 `NettyHttpServer` 参数，事件改由端口绑定后的 `NettyServerStartedEvent` 携带）。迁移写法见[破坏性变更说明](docs/breaking-changes.md)的 2.7.8 节
- **流式队列容量可配置且默认下调**：新增 `server.http.stream-max-queued-events`（默认 `4096`），SSE / 字节流发送器的入队缓冲由硬编码 `65536` 改为建连时按配置解析。JCTools 数组一次性分配，每连接由约 256KB 降到约 16KB（1000 长连接实测 251.9MB → 16.7MB）；A/B 基准每请求分配 314.7KB → 95.8KB（−69.6%），吞吐无劣化迹象。非 2 的幂向上取整，小于 2 时启动失败；同时修复降容量后同步 Publisher 在 `highWaterMark` 超过容量时入队自旋等待同栈 drain、最终 30s 超时的问题
- **支持范围为 Spring Boot 2.7.x**：`2.7.x` 分支的目标版本与验证基线均为 **Spring Boot 2.7.x**（当前 2.7.18），`spring-boot-2.7` Profile 默认激活并锁定该版本，CI 的 `spring-boot-versions` 矩阵以 `2.7` 为唯一入口。详见[版本兼容性说明](docs/compatibility.md)

### 修复

- **异步超时稳定按 503 收尾**：`WebAsyncTask` / `Callable` 超时后，被取消任务抛出的 `InterruptedException` 会与超时结果抢写响应槽——客户端可能拿到 500，任务若不响应中断甚至会拿到迟到的 200 + 正常结果，超时等于失效。现在超时一旦到点，此后到达的任务结果一律丢弃，按 2.7.7 起的约定稳定返回 503
- **攒批小窗口按配置生效**：`batchWindowMillis` 设为 2~5ms 这类小值时，到期会被定时器粒度放大——快 IO 场景吞吐不升反降、低负载请求白等。修复后窗口按标称值触发，`maxBatchSize` 的攒批效果真正兑现
- **会话 Cookie**：Servlet 默认会话 Cookie（`maxAge=-1`）不再被写成 `Max-Age=-1` / `Expires=<now>` 而立即过期
- **持久化会话**：启用文件会话存储时，落盘不再运行在网络线程上拖慢同一线程上的其他连接；停机时最多等待 5 秒把在途保存写完
- **405 与关闭中请求**：405 响应的 `Allow` 头汇总所有可用方法；优雅关闭期间被拒的请求返回 503
- **批处理停机与完成兜底**：停机 drain 加 5 秒上界（此前可能无限挂起）；业务线程池已关闭时被拒的批次逐条报错而非静默丢弃；批量方法返回后仍未完成的请求立即失败，不再挂到 30 秒超时
- **HTTP 日期头**：支持 RFC 850 / 1036 的两位年份日期（此前被判为无法解析）
- **转发与包含**：`INCLUDE_QUERY_STRING` 按规范只放 query string、`INCLUDE_SERVLET_PATH` 去掉 query；`INCLUDE_REQUEST_URI` 改为目标资源 URI（contextPath + 目标路径，此前误写原始请求 URI），补上 Servlet 4.0+ 的 `INCLUDE_MAPPING` / `FORWARD_MAPPING`，include 结束后不再残留 `INCLUDE_*` 属性（嵌套 include 恢复外层值）
- **HTTP/2**：修复 h2c preface 分片时的空指针
- **上传与流式响应**：并发解析 multipart 不再产生重复条目；关闭上传流、HEAD 请求、响应头超限降级等路径不再泄漏流、文件句柄或缓冲区
- **HEAD 请求访问流式端点**：`HEAD` 命中 SSE / 流式返回值的处理器时，响应已按 HEAD 语义以「仅响应头」收尾，此前却仍启动发送器并向已结束的响应写内容帧，每次请求打出一条 `EncoderException: unexpected message type: DefaultHttpContent, state: 0` 的 ERROR 日志（并把上游订阅取消掉）。现在 HEAD 不再启动流，响应头照常返回
- **上传异常路径的 direct memory 泄漏**：上传解析失败、或声明长度超限而被拒绝时，约等于请求体大小的直接内存不会被回收，长时间运行会持续上涨；同时超限上传此前可能拿到连接异常而非 413
- **异步请求的内存泄漏**：客户端在异步（含 SSE）真正开始前就断开时，该请求的缓冲与异步生命周期都不会被释放，表现为接口长期运行后直接内存只涨不降
- **WebSocket 帧处理**：分片帧按 RFC 6455 聚合后再交给业务；各种断连路径的关闭回调只触发一次；握手异常时释放请求；二进制发送不再消耗调用方 `ByteBuffer`；单帧消息与批量发送同样受大小限制和背压水位约束
- **重定向与编码**：`//host` 协议相对目标不再拼 context-path；https 无显式端口时模板取到 443（绝对 URL 正确）；带后缀的视图名三个引擎统一支持；自定义 Content-Type 未带 charset 时补上响应实际编码

### 安全

- **SSE 协议注入**：`id` / `event` 字段中的换行在写入前拒绝，避免上游值伪造额外 SSE 字段（此前依赖 Spring 补丁版本的行为）
- **外部重定向白名单**：可选 `spring.web.view.redirect-hosts` 限制外部重定向目标，可阻断含 userinfo 混淆的地址

## [3.5.8] - 20261125（预计）
> ⚠️ **本版含破坏性变更**：Servlet 规范语义、视图错误与内容协商、部分配置项的校验方式均有调整，
> **升级前请先读 [破坏性变更说明](docs/breaking-changes.md) 的 3.5.8 节**。

### 新增

- **Servlet / Filter 动态注册**：`ServletContext.addServlet()` / `addFilter()` 现在按 Servlet 规范返回 `ServletRegistration.Dynamic` / `FilterRegistration.Dynamic`，可配置 URL 映射、初始化参数、`load-on-startup`、`async-supported` 等；注册仅在 `ServletContextListener#contextInitialized()` 期间开放，窗口关闭后的写操作抛 `IllegalStateException`，重名与 URL 冲突也会明确报错。`addJspFile()` 仍不支持（JSP 由 `jsp:` 视图统一路由），`createServlet()` / `createFilter()` 可用
- **Servlet 监听器运行期注册**：`ServletContext.addListener()` 现在真正生效，支持 `ServletContextListener`、`HttpSessionListener`、`HttpSessionAttributeListener` 与 `HttpSessionIdListener`；会话 ID 轮换会通知 ID 监听器。运行期注册的监听器只收到注册之后发生的事件，需要完整生命周期回调的监听器请声明为 Spring Bean
- **JSP 生产默认配置**：新增 `server.servlet.jsp.development`（默认 `false`，改动 JSP 需重启生效）、`keep-generated`、`trim-spaces` 与 `init-parameters.*`（透传 Jasper，显式项优先）；Jasper 实例改由 Spring 管理，JSP 与标签类可获得依赖注入
- **WebSocket 压缩扩展**：`setAllowExtensions(true)` 现在真正协商 `permessage-deflate`（此前是空开关，未协商的压缩帧会被当明文交给业务）；协商结果可通过 `getExtensions()` / `Session#getNegotiatedExtensions()` 读取，按端点设置的 `messageSizeLimit` 也随之生效
- **WebSocket JSR-356 语义补齐**：`RemoteEndpoint.Basic` 为同步发送；`getSendStream()` / `getSendWriter()` 缓冲后按整条消息发出；`setBatchingAllowed()` / `flushBatch()` 真正攒批；`Session.addMessageHandler()` 支持动态注册；自定义 `Configurator` 的 `getEndpointInstance()` / `checkOrigin()` 生效；Ping / Pong 帧现在携带应用数据，超 125 字节按规范拒绝
- **批处理攒批时间窗口**：`@BatchMapping` 新增 `batchWindowMillis`（默认 `2`ms，设为 `0` 可关闭）。窗口内持续累积，直到达到 `maxBatchSize` 或窗口到期才提交，批大小由配置保证而非依赖业务池偶然饱和；中高并发下 IO 次数成倍下降、吞吐同时提升。低负载（在途请求撑不满一批）时窗口只是等待成本，建议显式关闭，取值建议见 [批处理文档](docs/batch.md)
- **批处理全局默认值**：新增 `springperf.batch.*`（`window-millis` / `max-batch-size` / `consumer-size` / `ring-buffer-size` / `wait-strategy` / `backpressure`），多个队列统一调优不必逐个改注解。优先级为「注解显式声明 > 全局键 > 注解内置默认值」，仅启动期读取一次；取值非法直接启动失败并点名键名与可选值，启动日志打印生效值与实际配置的键，不静默回落。已知边界：注解显式写回内置默认值时与「未声明」不可区分，此时以全局键为准。详见[批处理配置参考](docs/batch.md#配置参考)
- **视图运行期缓存失效**：新增 `ViewResolverRegistry.invalidateViewCaches()`，运行期投放或替换模板后调用即可生效（同时清理 Thymeleaf / FreeMarker / Beetl 内部模板缓存）；视图解析结果按视图名缓存，`cache=false` 仍保留热改
- **视图配置**：新增 `spring.{thymeleaf,freemarker,beetl}.order`、`spring.web.view.jsp.order`（引擎解析顺序确定且可配）与 `spring.freemarker.enabled`；新增可选的 `spring.web.view.redirect-hosts` 外部重定向 host 白名单；移除从未被读取的 `spring.beetl.mode`
- **自动配置开关**：新增 `springperf.openapi.enabled`、`springperf.swagger-ui.enabled`（可分别关闭文档端点与 Swagger UI 资源）、`springperf.web.mvc-conflict-check.enabled`（三方库传递引入 Spring MVC 时可跳过启动期冲突检查）
- **管理端口与 SSL 配置**：`management.server.*` 统一按「管理端口专属值 → 主服务器 `server.*` → 默认值」解析，HTTP/2、请求体上限、SSL 等可按需覆盖（SSL 开关同样遵循该回退）；SSL 配置改用 Spring Boot 的 `Ssl` 模型绑定，非法 `client-auth`、空 keystore 或缺失凭据在启动期直接报错
- **服务就绪时机**：`WebServerInitializedEvent` 改为在 Netty 实际绑定端口后发布，服务注册组件（Nacos / Eureka / Consul）感知就绪的时机更准确
- **`StreamingResponseBody` 支持**：返回 `StreamingResponseBody`（含 `ResponseEntity<StreamingResponseBody>`）现在会真正流式写出——`writeTo` 在业务线程池执行，字节按 8KB 分帧经既有流式写出通道送达，客户端可边写边读；`produces` 声明了具体类型时写入该 `Content-Type`，HEAD 只发响应头。此前该类型无解析器，按内容协商失败返回 500

### 变更

- **Servlet 规范语义收紧**：同一异步周期内重复 `startAsync()` 抛 `IllegalStateException`；`complete()` 幂等、`isAsyncStarted()` 在 `complete()` / `dispatch()` 后返回 `false`；`dispatch()` 期间 `DispatcherType` 为 `ASYNC`；`addListener(listener, req, resp)` 的事件携带注册时的 req/resp；`getHeaders(name)` 无该头返回空集合；`getDateHeader()` 对存在但无法解析的日期抛 `IllegalArgumentException`；`setAttribute(name, null)` 等价 `removeAttribute`；`setSessionTrackingModes()` 现在生效并按规范校验（集合非空且必须含 `COOKIE` 或 `SSL`）
- **视图错误不再静默**：视图解析异常保留原始 cause 不再被跳过；`ModelAndView` 指向不存在的视图抛异常而非 200 空白页；渲染中途抛异常不再返回 200 + 半截 HTML，改为清空缓冲后交给异常处理器（500 或自定义错误页）；`redirect:` 不再带 `text/html`，并保留 `ModelAndView` 指定的 3xx 状态码
- **内容协商失败显式报错**：无匹配转换器的 `@ResponseBody` 返回值一律 500（原先静默返回 200 + 空响应体），同时覆盖「已显式设置 `Content-Type`」与「`produces` 协商出类型」两条链路——此前只做了前者，走 `produces` 的返回值仍被静默丢弃，连 `produces` 声明的 Content-Type 都不写。与 Spring MVC 语义一致
- **视图渲染热路径降分配**：Thymeleaf 无 session 时上下文直接读取 model，不再每请求复制一份 `HashMap`；application 属性 Map 改为首次写入时创建；FreeMarker 解析缓存按 locale 分桶，去掉每请求拼接 `viewName + locale` 键；Content-Type 解析结果与 `redirect:` 的 `RedirectView` 均复用，避免重复解析/分配
- **视图输出不再分配 8 KB 编码缓冲**：三个模板引擎改为分块编码直写响应体，不再为每次渲染创建固定 8 KB 的编码缓冲；实测每请求分配 Thymeleaf 30.3 KB → 21.9 KB、FreeMarker 24.6 KB → 17.3 KB（同一 JFR 口径，含示例应用自身日志开销）。编码语义与 `OutputStreamWriter` 保持一致（不可映射字符按 REPLACE、`close()` 关闭底层流、跨块代理对不丢字符）
- **外部重定向白名单不再每请求解析**：`spring.web.view.redirect-hosts` 的解析结果按配置串缓存，配置变更后立即生效；3 个 host 的白名单每请求分配从约 660 B 降到约 5 B
- **配置启动期校验**：`spring.web.view.engine` 的未知值、`none` 与其他引擎混写、`spring.thymeleaf.mode` / `spring.freemarker.content-type` 非法值、以及视图引擎 `cache` / `enabled` 非 `true` / `false` 的值，均改为启动期报错而非静默忽略；`engine=none` 时不再装配内置 JSP（自定义 `ViewResolver` Bean 不受影响）
- **批处理默认启用攒批窗口**：相比 3.5.7，未显式配置的 `@BatchMapping` 现在默认按 2ms 窗口攒批（批大小由窗口保证，单请求延迟上界增加约 2ms）。若负载很低、希望保留旧版「消费者追上空队列即刷出」的行为，显式写 `batchWindowMillis = 0`；启动期提示也改为仅在显式关闭时出现
- **API 收敛（二进制不兼容）**：`SpringHeadersAdapter`（WebSocket 头适配器）随拆分重构移除，`NettyWebSocketSession` 构造器改为直接接收 Spring 的 `org.springframework.http.HttpHeaders`；从未被读取的 `ViewProperties.BEETL_MODE` / `BEETL_MODE_DEFAULT` 常量随 `spring.beetl.mode` 一起删除；`WebServerInitializedEventAutoConfiguration#webServerInitializedEventPublisher(...)` 签名变更（去掉 `NettyHttpServer` 参数，事件改由端口绑定后的 `NettyServerStartedEvent` 携带）。迁移写法见[破坏性变更说明](docs/breaking-changes.md)的 3.5.8 节
- **流式队列容量可配置且默认下调**：新增 `server.http.stream-max-queued-events`（默认 `4096`），SSE / 字节流发送器的入队缓冲由硬编码 `65536` 改为建连时按配置解析。JCTools 数组一次性分配，每连接由约 256KB 降到约 16KB（1000 长连接实测 251.9MB → 16.7MB）；A/B 基准每请求分配 314.7KB → 95.8KB（−69.6%），吞吐无劣化迹象。非 2 的幂向上取整，小于 2 时启动失败；同时修复降容量后同步 Publisher 在 `highWaterMark` 超过容量时入队自旋等待同栈 drain、最终 30s 超时的问题

### 修复

- **异步超时稳定按 503 收尾**：`WebAsyncTask` / `Callable` 超时后，被取消任务抛出的 `InterruptedException` 会与超时结果抢写响应槽——客户端可能拿到 500，任务若不响应中断甚至会拿到迟到的 200 + 正常结果，超时等于失效。现在超时一旦到点，此后到达的任务结果一律丢弃，按 3.5.7 起的约定稳定返回 503
- **攒批小窗口按配置生效**：`batchWindowMillis` 设为 2~5ms 这类小值时，到期会被定时器粒度放大——快 IO 场景吞吐不升反降、低负载请求白等。修复后窗口按标称值触发，`maxBatchSize` 的攒批效果真正兑现
- **会话 Cookie**：Servlet 默认会话 Cookie（`maxAge=-1`）不再被写成 `Max-Age=-1` / `Expires=<now>` 而立即过期
- **持久化会话**：启用文件会话存储时，落盘不再运行在网络线程上拖慢同一线程上的其他连接；停机时最多等待 5 秒把在途保存写完
- **405 与关闭中请求**：405 响应的 `Allow` 头汇总所有可用方法；优雅关闭期间被拒的请求返回 503
- **批处理停机与完成兜底**：停机 drain 加 5 秒上界（此前可能无限挂起）；业务线程池已关闭时被拒的批次逐条报错而非静默丢弃；批量方法返回后仍未完成的请求立即失败，不再挂到 30 秒超时
- **HTTP 日期头**：支持 RFC 850 / 1036 的两位年份日期（此前被判为无法解析）
- **转发与包含**：`INCLUDE_QUERY_STRING` 按规范只放 query string、`INCLUDE_SERVLET_PATH` 去掉 query；`INCLUDE_REQUEST_URI` 改为目标资源 URI（contextPath + 目标路径，此前误写原始请求 URI），补上 Servlet 4.0+ 的 `INCLUDE_MAPPING` / `FORWARD_MAPPING`，include 结束后不再残留 `INCLUDE_*` 属性（嵌套 include 恢复外层值）
- **HTTP/2**：修复 h2c preface 分片时的空指针
- **上传与流式响应**：并发解析 multipart 不再产生重复条目；关闭上传流、HEAD 请求、响应头超限降级等路径不再泄漏流、文件句柄或缓冲区
- **HEAD 请求访问流式端点**：`HEAD` 命中 SSE / 流式返回值的处理器时，响应已按 HEAD 语义以「仅响应头」收尾，此前却仍启动发送器并向已结束的响应写内容帧，每次请求打出一条 `EncoderException: unexpected message type: DefaultHttpContent, state: 0` 的 ERROR 日志（并把上游订阅取消掉）。现在 HEAD 不再启动流，响应头照常返回
- **上传异常路径的 direct memory 泄漏**：上传解析失败、或声明长度超限而被拒绝时，约等于请求体大小的直接内存不会被回收，长时间运行会持续上涨；同时超限上传此前可能拿到连接异常而非 413
- **异步请求的内存泄漏**：客户端在异步（含 SSE）真正开始前就断开时，该请求的缓冲与异步生命周期都不会被释放，表现为接口长期运行后直接内存只涨不降
- **WebSocket 帧处理**：分片帧按 RFC 6455 聚合后再交给业务；各种断连路径的关闭回调只触发一次；握手异常时释放请求；二进制发送不再消耗调用方 `ByteBuffer`；单帧消息与批量发送同样受大小限制和背压水位约束
- **重定向与编码**：`//host` 协议相对目标不再拼 context-path；https 无显式端口时模板取到 443（绝对 URL 正确）；带后缀的视图名三个引擎统一支持；自定义 Content-Type 未带 charset 时补上响应实际编码

### 安全

- **SSE 协议注入**：`id` / `event` 字段中的换行在写入前拒绝，避免上游值伪造额外 SSE 字段（此前依赖 Spring 补丁版本的行为）
- **外部重定向白名单**：可选 `spring.web.view.redirect-hosts` 限制外部重定向目标，可阻断含 userinfo 混淆的地址

## [2.7.7] - 20261013
> ⚠️ **本版含破坏性变更**：配置键、响应分帧、Servlet `flush` 语义、条件请求/Range、
> 扩展点签名均有调整，**升级前请先读 [破坏性变更说明](docs/breaking-changes.md)**。

### 新增

- **静态资源与视图**：新增可插拔 SPI `WebExchangeProvider`（`supports()` 能力探测 + `createExchange()` + `getOrder()`），模板中可直接拿到**真实** session 与 principal；视图/编码相关配置键统一
- **HTTP 协议语义对齐**：条件请求（`If-None-Match` / `If-Modified-Since`）、字节范围（`Accept-Ranges`、单段 `206`、多段 `multipart/byteranges`）、响应分帧按 RFC 7231/7232/7233 收紧
- **服务端配置对齐 Spring Boot**：连接与请求上限、keep-alive 超时/请求数、multipart 上限、错误响应策略、转发头策略等一组配置键（见配置手册与破坏性变更说明的 2.7.7 节）
- **会话持久化**：`server.servlet.session.persistent` / `.store-dir` / `.persistent-exclude`（JDK 序列化，每 session 一文件，不落盘 `transient` 属性），并新增反序列化白名单键 `server.servlet.session.persistent-deserialization-filter`
- **配置刷新**：监听 `EnvironmentChangeEvent` 清空框架属性缓存，配置中心改动无需重启

### 变更

- **响应分帧**：`flush(true)` 由「一次性」改为 chunked 渐进式输出，提交后仍可继续写入，由 `endStream()` 写终止块
- **Servlet `PrintWriter`**：`print` / `println` 不再自动提交（对齐 Tomcat `autoFlush=false`），需显式 `flush()` / `flushBuffer()`
- **扩展点签名变更**（源码级不兼容，详见破坏性变更说明的 2.7.7 节「扩展点签名变更」）
- **会话、重定向与条件请求的错误映射**调整；管理端口与转发语义调整（均见破坏性变更说明的 2.7.7 节）

### 安全

- **请求参数数量上限**：新增 `server.max-parameter-count`（默认 10000），防 hash 碰撞 DoS

### 优化

- **热路径分配**：JFR 驱动的若干优化（请求分发、属性查找、只读响应头视图缓存等），降低每请求分配与 CPU
- **流式拷贝线程池**：兜底流拷贝改用专用守护线程池，不再退化为并行度受限的公共池

### 修复

- **会话并发**：过期会话清理改为在写锁内条件摘除，修复「刚清理的过期会话被并发落盘写回、重启后复活」；会话写入串行化，`tryInvalidate` 原子失效
- **异步 / SSE 生命周期**：`DeferredResult` 终态只触发一次、超时任务及时清理、`ByteBuf` 引用计数配对；`markStreamCompleted` 改为抢占式，杜绝双终止块
- **Servlet / websocket**：`;jsessionid=` 从 URI 回读（Cookie 优先）、same-site 值规范化、JSR-356 端点生命周期与会话收尾
- **视图与桥接**：Thymeleaf 会话变量注入、FreeMarker 适配、`WebMvcConfigurer#addViewControllers` 桥接（未能桥接的回调打 WARN，不再静默）
- **core / http**：错误输入处理、日志锁、文件名守卫、media type 顺序、惰性请求状态与 head 标志可见性

### 构建

- **门禁与 master 对齐**：接入 `mvnw`、enforcer `requirePluginVersions`、javadoc doclint（JDK 8 口径）、`check-docs` / 符号校验，并清理 doclint 报错
- **CI**：采用 master 的 workflow 集（CI / CodeQL / dependabot / coverage-aggregate）
- **覆盖率**：按「单测 + E2E 合并」口径重生成 `coverage-report.md`

### 文档

- **同步台账**：2.7.x 同步缺口与迁移清单逐文件定性
- **事实修正**：清除漏入本分支的 jakarta / Spring Boot 3 措辞与失效符号引用

## [4.1.7] - 20261008
> **关于本版本**：`4.1.x` 是从 `master`（纯 Spring Boot 3.5.x）**拆分出来独立发布**的下游分支，
> 首个发布版本号为 **`4.1.7`**：Spring Boot 4.0 / 4.1 + Spring Framework 7 + Jackson 3，不再兼顾 3.5.x。
> 因此首个版本的变更包含「分支拆分与适配」本身，其余条目与 master 的 `3.5.7` 基本一致（后者原文附于本节之后）。

### 变更

- **分支定位**：本分支（`4.1.x`）**专用 Spring Boot 4.1**（`-Pspring-boot-4.0` 可切 4.0），**不再兼顾 3.5.x**——「一份代码同时支持两版」的目标由 master（纯 3.5.x）与本分支的分工取代。默认构建即为 4.1，无需 `-P` 切换。master 侧的差异与同步规则见 [4.1.x 适配指南](.agent/context/4.1.x-adaptation-checklist.md)
- **兼容层整体移除**：`WebHttpHeaders` 的 8 个 MethodHandle 版本分支、`Boot4WebServerInitializedEventBridge` 的 ASM 运行时字节码生成、`ResponseStatusExceptionAdapter` 的方法名反射探测、`ListenableFutureAdapter`（Spring 7 已无该类型，原实现是死代码）等，均已删除，改为直接面向 Spring 7 / Jackson 3 的 API 实现
- **JSON 栈迁移到 Jackson 3**：`com.fasterxml.jackson.databind` → `tools.jackson.databind`（注解仍用 `com.fasterxml.jackson.annotation`）。Jackson 3 把 `FAIL_ON_NULL_FOR_PRIMITIVES` 默认值由 `false` 改为 `true`，本框架经 `JacksonMappers.defaultMapper()` 显式关闭以保持与 master 一致
- **Netty 跟随 Boot 4 BOM**：`4.1.137.Final` → `4.2.15.Final`（Boot 4 的基线）。删除了本项目对 14 个 Netty artifact 的钉版
- **Spring AI 升到 2.x**：1.x 针对 Boot 3，其 `OpenAiApi` 调用了 Spring 7 已改签名的 `HttpHeaders.addAll(MultiValueMap)`

### 修复

- **转发头解析**：补上 `X-Forwarded-Host` / `X-Forwarded-Port` / `X-Forwarded-Prefix` 的支持。此前只有 scheme（proto）被解析，host/port 一直借 Boot 的 `ForwardedHeaderFilter` 兜底——而 Boot 4 的 `spring-boot-starter-web` 不再传递 servlet 容器，该 filter 无处挂载
- **`+json` 后缀媒体类型**：`application/vnd.spring-boot.actuator.v2+json` 这类带前缀的 `+json` 类型此前不匹配 JSON 转换器，导致 Actuator 端点返回 200 + 空 body
- **String 请求体**：`@RequestBody String` 在 `application/json` 下必然 400（框架从未注册 `StringHttpMessageConverter`）；补上并让它排在 Jackson 之前（Spring 语义：String 原样读取，不按 JSON 解析）

## [3.5.7] - 20261008
> ⚠️ **本版含破坏性变更**：配置键、响应分帧、Servlet `flush` 语义、条件请求/Range、
> 扩展点签名均有调整，**升级前请先读 [破坏性变更说明](docs/breaking-changes.md)**。

### 新增

- **静态资源与视图**：`WebExchangeProvider` 可插拔 SPI，模板中可直接拿到真实 session 与 principal；视图/编码相关配置键统一
- **HTTP 协议语义对齐**：条件请求（`If-None-Match` / `If-Modified-Since`）、字节范围（含多段 `multipart/byteranges` 与 `Accept-Ranges`）、响应分帧按 RFC 7231/7232/7233 收紧
- **服务端配置对齐 Spring Boot**：连接与请求上限、keep-alive 超时/请求数、multipart 上限、错误响应策略、国际化绑定等一组配置键（见配置手册与 [破坏性变更说明](docs/breaking-changes.md) 的 3.5.7 节）
- **批量处理**：`@BatchMapping` 支持在虚拟线程上执行（JDK 21+），保留 `consumerSize` 上限与背压语义
- **配置刷新**：属性快照与基于 Spring Cloud 环境变更的刷新

### 变更

- **分支策略**：`master` 收敛为**纯 Spring Boot 3.5.x**——此前叠加的 Spring Boot 4 / Spring Framework 7 兼容层（`WebHttpHeaders` 的版本分支与 MethodHandle、`MediaTypeUtils`、`ResponseStatusExceptionAdapter`、`Boot4WebServerInitializedEventBridge`、SB4 Maven Profile 等）已全部移除；4.x 适配改由 `4.1.x` 分支承接（同步规则见 [4.1.x 适配指南](.agent/context/4.1.x-adaptation-checklist.md)）
- **响应分帧**：`flush(true)` 由「一次性」改为 chunked 渐进式输出
- **Servlet `PrintWriter`**：`print`/`println` 不再自动提交（对齐 Tomcat `autoFlush=false`）
- **扩展点签名变更**（源码级不兼容，详见 [破坏性变更说明](docs/breaking-changes.md) 的 3.5.7 节「扩展点签名变更」）
- **会话、重定向与条件请求的错误映射**调整；管理端口与转发语义调整（均见 [破坏性变更说明](docs/breaking-changes.md) 的 3.5.7 节）

### 安全

- **会话固定（Session Fixation）防护**：会话 ID 在登录前后轮换，杜绝固定攻击
- **Multipart DoS 防护**：按声明 `Content-Length` 提前 fail-fast + 流式累计字节上限（覆盖 chunked），超限返回 413 而非断连
- **Fastjson 反序列化硬化**：默认关闭 `SupportAutoType` 并开启 `ErrorOnNotSupportAutoType`，阻断 `@type` gadget 链
- **请求参数数量上限**：`server.max-parameter-count` 默认 10000，防 hash 碰撞 DoS

### 优化

- **热路径分配**：JFR 驱动的若干优化（请求分发、属性查找、`WebHttpHeaders` 的只读视图缓存等），降低每请求分配与 CPU
- **兼容层移除带来的性能收益**：`WebHttpHeaders` 的 8 个 `MultiValueMap` 方法此前经 `MethodHandle` + varargs `invokeWithArguments` 调用（每次分配 `Object[]`），而 `get()`/`entrySet()`/`keySet()` 均在每请求热路径上；现改为 `super.*()` 直调

### 修复

- **会话并发**：过期会话清理改为在写锁内条件摘除，修复「刚清理的过期会话被并发落盘写回、重启后复活」；会话写入串行化
- **异步 / SSE 生命周期**：`DeferredResult` 终态只触发一次、超时任务及时清理、`ByteBuf` 引用计数配对，避免长连接下的泄漏与提前释放
- **自动配置注册表残留**：`AutoConfiguration.imports` 中仍注册着已删除的 `Boot4WebServerInitializedEventAutoConfiguration`，会导致 Spring 上下文启动失败
- **404/405 状态共享污染**、**Filter 链异常路径**、**`WebContext` 生命周期**、**路由边界**等一批既有缺陷

## [2.7.6] - 20260910
### 新增

- **视图渲染模块 `spring-web-view`**：Model/View 提升为请求管线一等公民（`ModelContext` 请求级容器 + `View`/`ViewResolver` SPI），支持 `@ControllerAdvice @ModelAttribute` 预置、本地 `@ModelAttribute` 方法、`@PathVariable`/`BindingResult` 合并；多模板引擎（Thymeleaf / FreeMarker / Beetl）共存、`spring.web.view.engine` 多选；`redirect:` 前缀视图与 `ViewReturnValueResolver`（Thymeleaf 3.0 适配为纯 `IContext`、FreeMarker 2.3.32）
- **JSP 渲染**：集成 Apache Jasper + JSTL（`JasperJspServlet`），`JspViewResolver` 提供 `jsp:` 前缀 / `.jsp` 后缀视图名并自动注册 `/**/*.jsp` 路由；`spring-web-mvc-support` 桥接 `ModelAndView` / JSP 视图（javax.servlet + JSTL 1.2 适配）
- **Servlet 对象路由**：`SupportServletRegistry` 扫描 Servlet Bean / `@WebServlet` 注解 `url-pattern` 注册为框架路由；`ServletInvoker`、`PerfServletConfig`
- **JSR-356 `@ServerEndpoint` 桥接**（`spring-web-websocket`）：标准 WebSocket 端点注解支持，涵盖 `@OnOpen/@OnMessage/@OnClose/@OnError`、`@PathParam`、Decoder/Encoder、自定义 `Configurator`、`JsrWebSocketContainer` 端点生命周期（javax.websocket 适配）
- **方法级拦截器与 `@SessionAttributes`**：`@ControllerAdvice` 拦截器按 handler controller 类型匹配；`@SessionAttributes` 跨请求同步 Model 属性、`SessionStatus` 清理，`@SessionAttribute`/`SessionStatus` 参数自动装配
- **HEAD 语义（RFC 7231 §4.3.2）**：无显式 HEAD handler 时自动映射到 GET，响应体抑制但保留真实 `Content-Length`（资源文件/流式/字节路径均覆盖）
- **Linux 原生传输**：新增 `server.netty.transport=auto/nio/epoll`（默认 auto），Linux 自动启用 native epoll
- **生产级 Netty 默认值**：`SO_BACKLOG` 1024、`SO_KEEPALIVE` true、`max-content-length` 4MB；业务线程池默认有界队列 100，使 `maxPoolSize` 扩容与 503 快速失败真正生效

### 变更

- **`spring-web-support` 模块拆分**：拆分为 `spring-web-servlet`（纯 Servlet 桥接：Filter/Servlet/HttpSession/JSP/`@SessionAttribute` 等官方 spring-web 语义，零 SpringMVC 依赖）与 `spring-web-mvc-support`（SpringMVC 兼容桥接：`org.springframework.web.servlet.*` 重写类、`WebMvcConfigurer`/`HandlerInterceptor`/`RequestBodyAdvice` 桥接）。仅需 Servlet 兼容的用户只依赖 `spring-web-servlet`；`spring-web-mvc-support` 依赖 `spring-web-servlet`。starter 自动配置相应拆为 `SpringWebServletAutoConfiguration` + `SpringWebMvcSupportAutoConfiguration`（分别按模块 class 条件生效）

### 安全

- **Multipart DoS 防护**：`SupportMultipartResolver` 对声明 `Content-Length` 提前 fail-fast + 流式累计字节上限（覆盖 chunked），超限对齐 `HttpObjectAggregator` 返回 413 而非关闭连接
- **Fastjson 反序列化硬化**：默认关闭 `SupportAutoType` 并开启 `ErrorOnNotSupportAutoType`，阻止 `@type` gadget 攻击
- **Session Fixation 防护**：会话固定攻击防护与安全审计修复

### 优化

- **组件注册并发安全**：`WebComponentContainer` 改用 `ConcurrentHashMap` + `compute` 原子合并，运行期动态注册无读-写竞争、无残留 destroy
- **连接指标隔离**：`NettyMetricsHandler` 由共享单例改为每服务器独立实例，主端口与管理端口连接数各自统计
- **Locale 参数解析**：未绑定线程 Locale 时按请求 `Accept-Language` 解析，尊重拦截器/ControllerAdvice 覆盖，不使用 `ThreadLocal`（零线程池残留）
- **异常注入修正**：`@ExceptionHandler` 命中 cause 链时注入与参数类型兼容的最具体异常，避免返回 `ClassCastException` 误报 500

### 修复

- **404/405 状态共享污染**：改为每请求新建 `StacklessResponseStatusException`（`fillInStackTrace` 已禁用，零开销），避免 `@ExceptionHandler` 修改 headers/body 影响后续请求
- **Filter 链异常路径**：初始化请求上下文并触发完整异常处理与 `afterCompletion`，避免拦截器读到 null / 线程残留 ThreadLocal
- **`WebContext` 生命周期**：启动失败清理已初始化组件并保留 fail-fast 重试；`destroy()` 后子组件状态复位，支持 stop/restart 重新初始化
- **异步兜底**：未配置默认业务线程池且方法未显式指定 executor 时懒加载复用 `SimpleAsyncTaskExecutor`，消除 NPE
- **路由边界**：`PrefixPathRouterOptimizer` 无前缀索引时显式拒绝（避免数组越界）；`SuffixPathRouterOptimizer` 边界守卫
- **异步与流式边界**：asynchronous handler 异常处理、`AbstractNettyStreamSender` 完成边界加固
- **静态资源方法收紧**：`ResourceRequestHandler` 仅允许 GET（HEAD 由路由层自动映射并只回元数据，跳过文件 IO）
- **JSR-356 生命周期加固**：`@OnOpen` 失败 / 非规范关闭码路径兜底销毁 Decoder/Encoder，`@OnClose` 异常不再跳过 codec 销毁
- **视图解析失败不再静默**：无法解析的视图名抛 `IllegalArgumentException`（500 + 错误上下文），替代空响应 200；`ViewResolver` 解析异常记录日志
- **`redirect:` 语义对齐**：仅视图方法生效，不再劫持 `@ResponseBody`/`@RestController` 返回的 `"redirect:/x"` 字符串为 302；目标 URL 已含 query 时用 `&` 续接
- **`WebComponentContainer` 命名冲突**：落败组件不再被误初始化/重复注册路由，防止副作用与资源泄漏
- **Servlet 桥接 session 永不过期 → 无界内存增长**：`PerfHttpSessionManager.createSession()` 未应用会话超时，`maxInactiveInterval` 恒为 0，清理线程永不回收 session；现按 `server.servlet.session.timeout` 设置过期（Spring Boot Duration 语义：裸数字=秒、支持 `30m/1h/1d` 后缀，默认 30 分钟）
- **`server.http.read-timeout` 默认值失效**：`HTTP_READ_TIMEOUT` 未加入默认值表，未配置时读超时保护（文档声明的 30s）实际关闭；现已修复
- **异步返回值序列化异常被吞**：`asyncDispatch` 中返回值解析异常仅记录日志、响应悬挂至超时；现路由到 `ExceptionRegistry` 并 flush，`afterCompletion` 携带真实异常
- **404/405 路径级拦截器路径约束失效**：无 handler 时仍按请求路径过滤 include/exclude，路径级拦截器不再对任意 404 触发
- **重叠通配符路由按特异性匹配**：`/user/{id}` 与 `/user/*` 等重叠通配符最精确者优先（literal > {var} > * > **），对齐 Spring MVC；`PathPatternUtils.comparePathRuleSpecificity` 提供比较逻辑
- **流式错误结束不再挂起**：`AbstractNettyStreamSender.complete(.., failure)` 错误终止时关闭连接（不再写正常 `LastHttpContent`），客户端感知异常截断

### 构建

- **JaCoCo 覆盖率门槛**：全模块覆盖率 TOTAL 92.1%（spring-web 92.7% / servlet 91.5% / websocket 90.5% / view 95.5%），新增 `scripts/coverage-report.sh/ps1` 报告脚本（迁移至 `scripts/` 目录）
- **E2E 增强**：随机端口避免冲突、优雅停机 drain、TLS depth / mTLS、native 降级与性能门限测试、连接复用与请求厚度测试；`BaseE2ETest` 读写超时放宽到 30s 降低偶发 flake
- **测试加固**：`AsyncSupportRegistryTest` 同构 stub 加 `lenient()`；`HttpBodyCodecRegistryCoverageTest` 排序断言不依赖并发遍历顺序；`ResponseEntityExceptionHandlerBranchesTest` 改用确定有参方法；恢复 `placeholder.txt` 测试资源并补 `.gitignore` 排除规则

### 文档

- **视图渲染文档**：`docs/view.md`（中英）与 2.7.6 版本同步
- **模块文档**：随 `spring-web-support` 拆分，`spring-web-servlet` 与 `spring-web-mvc-support` 独立成章并给出选择建议

## [3.5.6] - 20260906
### 新增

- **视图渲染模块 `spring-web-view`**：Model/View 提升为请求管线一等公民（`ModelContext` 请求级容器 + `View`/`ViewResolver` SPI），支持 `@ControllerAdvice @ModelAttribute` 预置、本地 `@ModelAttribute` 方法、`@PathVariable`/`BindingResult` 合并；多模板引擎（Thymeleaf / FreeMarker / Beetl）共存、`spring.web.view.engine` 多选；`redirect:` 前缀视图与 `ViewReturnValueResolver`
- **JSP 渲染**：集成 Apache Jasper + JSTL（`JasperJspServlet`），`JspViewResolver` 提供 `jsp:` 前缀 / `.jsp` 后缀视图名并自动注册 `/**/*.jsp` 路由；`spring-web-mvc-support` 桥接 `ModelAndView` / JSP 视图
- **Servlet 对象路由**：`SupportServletRegistry` 扫描 Servlet Bean / `@WebServlet` 注解 `url-pattern` 注册为框架路由；`ServletInvoker`、`PerfServletConfig`
- **JSR-356 `@ServerEndpoint` 桥接**（`spring-web-websocket`）：标准 WebSocket 端点注解支持，涵盖 `@OnOpen/@OnMessage/@OnClose/@OnError`、`@PathParam`、Decoder/Encoder、自定义 `Configurator`、`JsrWebSocketContainer` 端点生命周期
- **方法级拦截器与 `@SessionAttributes`**：`@ControllerAdvice` 拦截器按 handler controller 类型匹配；`@SessionAttributes` 跨请求同步 Model 属性、`SessionStatus` 清理，`@SessionAttribute`/`SessionStatus` 参数自动装配
- **GraalVM native-image AOT 支持**：`ControllerBeanFactoryInitializationAotProcessor` 为控制器/泛型 DTO 注册反射 hints，`SpringWebRuntimeHints` 补全 resource/反射注册；`ServerEndpointBeanFactoryInitializationAotProcessor` 覆盖端点；native-image CI workflow 与 `native-smoke-test.sh`
- **HEAD 语义（RFC 7231 §4.3.2）**：无显式 HEAD handler 时自动映射到 GET，响应体抑制但保留真实 `Content-Length`（资源/文件/流式/字节路径均覆盖）
- **Linux 原生传输**：新增 `server.netty.transport=auto/nio/epoll`（默认 auto），Linux 自动启用 native epoll；Netty 依赖由 `netty-all` 拆分为具体模块并附带 `linux-x86_64/aarch_64/riscv64` native classifier
- **生产级 Netty 默认值**：`SO_BACKLOG` 1024、`SO_KEEPALIVE` true、`max-content-length` 4MB；业务线程池默认有界队列（100，使 `maxPoolSize` 扩容与 503 快速失败真正生效）
- **`@RunInEventloop` 与配置项补全**的传输选型、`publishLocalServerPort` 以 `MapPropertySource` 隔离主/管理端口

### 变更

- **`spring-web-support` 模块拆分**：拆分为 `spring-web-servlet`（纯 Servlet 桥接：Filter/Servlet/HttpSession/JSP/`@SessionAttribute` 等官方 spring-web 语义，零 SpringMVC 依赖）与 `spring-web-mvc-support`（SpringMVC 兼容桥接：`org.springframework.web.servlet.*` 重写类、`WebMvcConfigurer`/`HandlerInterceptor`/`RequestBodyAdvice` 桥接）。仅需 Servlet 兼容的用户只依赖 `spring-web-servlet`，classpath 不引入任何 `org.springframework.web.servlet.*` 类；`spring-web-mvc-support` 依赖 `spring-web-servlet`。starter 自动配置相应拆为 `SpringWebServletAutoConfiguration` + `SpringWebMvcSupportAutoConfiguration`（分别按模块 class 条件生效）。旧坐标 `io.github.springperf:spring-web-support` 移除
- **响应状态码 API**：`WebServerHttpResponse.getStatus()` 返回类型由 `HttpStatus` 改为 `HttpStatusCode`，支持非标准状态码（如 499/599/507）按原始码值写入响应；`sendError` 新增 `HttpStatusCode` 重载，servlet 桥接 `setStatus/sendError` 同步支持（不再对非标准码抛异常）

### 安全

- **Multipart DoS 防护**：`SupportMultipartResolver` 对声明 `Content-Length` 提前 fail-fast + 流式累计字节上限（覆盖 chunked），超限对齐 `HttpObjectAggregator` 返回 413 而非关闭连接
- **Fastjson 反序列化硬化**：默认关闭 `SupportAutoType` 并开启 `ErrorOnNotSupportAutoType`，阻断 `@type` gadget 链
- **Session Fixation 防护**：会话固定攻击防护与安全审计修复

### 优化

- **组件注册并发安全**：`WebComponentContainer` 改用 `ConcurrentHashMap` + `compute` 原子合并，运行期动态注册无读-写竞争、无误 destroy
- **连接指标隔离**：`NettyMetricsHandler` 由共享单例改为每服务器独立实例，主端口与管理端口连接数各自统计
- **Locale 参数解析**：未绑定线程 Locale 时按请求 `Accept-Language` 解析，尊重拦截器/ControllerAdvice 覆盖，不写 `ThreadLocal`（零线程池残留）
- **异常注入修正**：`@ExceptionHandler` 命中 cause 链时注入与参数类型兼容的最具体异常，避免反射 `ClassCastException` 误报 500

### 修复

- **404/405 状态共享污染**：改为每请求新建 `StacklessResponseStatusException`（`fillInStackTrace` 已禁，零开销），避免 `@ExceptionHandler` 修改 headers/body 影响后续请求
- **Filter 链异常路径**：初始化请求上下文并触发完整异常处理与 `afterCompletion`，避免拦截器读到 null / 线程残留 ThreadLocal
- **`WebContext` 生命周期**：启动失败清理已初始化组件并保留 fail-fast 重试；`destroy()` 后子组件状态复位，支持 stop/restart 重新初始化
- **异步兜底**：未配置默认业务线程池且方法未显式指定 executor 时懒加载复用 `SimpleAsyncTaskExecutor`，消除 NPE
- **路由边界**：`PrefixPathRouterOptimizer` 无前缀索引时显式拒绝（避免数组越界）；`SuffixPathRouterOptimizer` 边界守卫
- **异步与流式边界**：asynchronous handler 异常处理、`AbstractNettyStreamSender` 完成边界加固
- **静态资源方法收窄**：`ResourceRequestHandler` 仅允许 GET（HEAD 由路由层自动映射并只回元数据，跳过文件 IO）
- **JSR-356 生命周期加固**：`@OnOpen` 失败 / 非规范关闭码路径兜底销毁 Decoder/Encoder，`@OnClose` 异常不再跳过 codec 销毁
- **视图解析失败不再静默**：无法解析的视图名抛 `IllegalArgumentException`（500 + 错误上下文），替代空白 200；`ViewResolver` 解析异常记录日志
- **`redirect:` 语义对齐**：仅视图方法生效，不再劫持 `@ResponseBody`/`@RestController` 返回的 `"redirect:/x"` 字符串为 302；目标 URL 已含 query 时用 `&` 续接
- **`WebComponentContainer` 命名冲突**：落败组件不再被误初始化/重复注册路由，防止副作用与资源泄漏
- **Locale 参数泛型解析测试修复**：`Optional<Locale>` 用可解析泛型的方法载体验证包装语义
- **Servlet 桥接 session 永不过期 → 无界内存增长**：`PerfHttpSessionManager.createSession()` 未应用会话超时，`maxInactiveInterval` 恒为 0，清理线程永不回收 session；现按 `server.servlet.session.timeout` 设置过期（Spring Boot Duration 语义：裸数字=秒、支持 `30m/1h/1d` 后缀，默认 30 分钟）
- **`server.http.read-timeout` 默认值失效**：`HTTP_READ_TIMEOUT` 未加入默认值表，未配置时读超时保护（文档声明的 30s）实际关闭；现已修复
- **非标准状态码崩溃**：`PerfHttpServletResponse.setStatus/sendError` 与核心 `setStatusCode` 对 499/599 等非标准码抛 `IllegalArgumentException`；现按原始码值写入响应
- **异步返回值序列化异常被吞**：`asyncDispatch` 中返回值解析异常仅记录日志、响应悬挂至超时；现路由到 `ExceptionRegistry` 并 flush，`afterCompletion` 携带真实异常
- **404/405 路径级拦截器路径约束失效**：无 handler 时仍按请求路径过滤 include/exclude，路径级拦截器不再对任意 404 触发
- **重叠通配符路由按特异性匹配**：`/user/{id}` 与 `/user/*` 等重叠通配符最精确者优先（literal > {var} > * > **），对齐 Spring MVC
- **流式错误结束不标记**：`AbstractNettyStreamSender.complete(.., failure)` 错误终止时关闭连接（不再写正常 `LastHttpContent`），客户端感知异常截断

### 构建

- **Spring Boot 升级 3.5.16**（默认 profile 切换为 `spring-boot-3.5`，3.2.x 保留为可选 profile）；Netty 升级 4.1.137
- **JaCoCo 覆盖率门禁**：全模块覆盖率 TOTAL 92.09%（spring-web 91.58% / websocket 91.92% / support 93.44% / batch 91.96%），新增 `coverage-report.sh/ps1` 报告脚本
- **E2E 增强**：随机端口避免冲突、优雅停机 drain、TLS depth / mTLS、native 降级与性能门限测试、连接复用与请求厚度测试
- **GraalVM native-image CI**：`ControllerBeanFactoryInitializationAotProcessor` 相关 AOT 构建测试

### 文档

- **视图渲染文档**：`docs/view.md`（中英）与 3.5.6 版本同步
- **兼容性/配置/模块文档**：随 Spring Boot 3.5.16、Netty 4.1.137、AOT 支持同步更新（中英）

## [3.2.5] - 20260824
### 新增

- **Servlet 规范桥（servlet spec bridge）**：`spring-web-support` 新增完整 Servlet 适配层，提供 `PerfServletContext`、`PerfAsyncContext`、`PerfRequestDispatcher`、`PerfWebConnection`、`PerfHttpPrincipal`、`ServletPartAdapter`、`PerfFilterConfig`、`ForwardWebServerHttpRequest`、`NettyHttpServletRequest` 等实现，支持在 Netty 上运行 Servlet 规范 Filter 链、`AsyncContext`、`RequestDispatcher` 转发、`HttpSession`、`Part` 等能力；新增 `SupportDispatcherHandler` 桥接分发器
- **`@RunInEventloop` 元注解**：`@Optimize` / `@ReactiveSupport` 标注 `@RunInEventloop`，业务方法可直接在 EventLoop 上执行，减少线程切换
- **Netty / Body / AccessLog 配置暴露**：`PropertiesConstant` 新增对应配置项，`NettyHttpServer` / `AccessLogWebFilter` 支持外部化配置
- **WSL 一键基准测试**：新增 `wsl-run-all.sh` / `wsl-benchmark.sh` / `wsl-server.sh` / `wsl-convert-cp.sh` 脚本、`WSL_SETUP.md` 与 `no-sleep.ps1`，支持可选 JFR 采集
- **报告生成器增强**：`ReportGenerator` 跳过损坏的结果 JSON，缺失容器在报告中渲染 FAIL 列并附带提示
- **内部实现文档**：新增 `docs/internals/00~19` 全量架构、请求链路与设计决策文档

### 优化

- **解析热路径分配削减**：请求参数解析、`InMemoryHttpSessionStorage`、`MicrometerWebMetrics` 路径减少中间对象分配
- **批处理 SSE 发送**：`AbstractNettyStreamSender` 新增 `sendAll()` 批量发送，避免逐条 flush，并修复 drain 重入
- **大请求体释放优化**：大体积请求体通过 `duplicate` 引用替代 refcount 加锁，消除锁开销

### 修复

- **Multipart**：`destroy()` 释放 decoder 以归还池化内存；修复 destroy 顺序、`undecodedChunk`、refcount 泄漏；`RequestPart` 缺失时返回 400 而非 500
- **WebSocket**：升级被拒绝时释放请求；修复 h2c fragment buffer 泄漏与 WS upgrade 泄漏
- **Async**：异步超时改为懒调度并修正 metrics 计时；`SSE emitter select`、`ReactiveSupport` 首个请求 NPE、冷流发布者背压 refill 修复
- **路由与参数绑定**：`SuffixPathRouterOptimizer` off-by-one 修复；映射与参数解析启动期 fail-fast 校验；`AbstractNamedValueResolver` 支持泛型集合元素类型转换；`getReader` 回落 UTF-8 编码
- **异常处理**：`ResponseStatusException` 头通过跨版本适配器正确拷贝
- **文件下载**：使用 `Content-Length` 定长帧，避免 chunked 传输
- **大请求体**：异步读取与释放同步，避免已释放 `ByteBuf` 被访问
- **管理端口隔离**：management dispatcher 不再参与主端口路由查找
- **Interceptor**：`afterCompletion` 语义修正
- **Netty 层**：`@Sharable` 处理器生命周期与 h2c buffer leak guard；`NettyHttpHandler` 请求路径 NPE 修复
- **Batch 队列**：`DisruptorQueue` RingBuffer 大小 clamp、溢出保护；`BatchScanner` 泛型类型解析修复
- **SSL PEM 解析**：`SslContextFactory` 私钥与证书解析修复
- **FilterWrapper**：`id` 稳定性修正
- **访问日志**：`AccessLogWebFilter` 错误状态记录与格式修正

### 文档

- **AI 上下文迁移**：`CLAUDE.md` / `.claude` 迁移至 `AGENTS.md` / `.agent`
- **benchmark 数据刷新**：WSL / 多实例趋势图数据更新
- **内部实现文档**：servlet bridge / async 流式 / batch 模块文档补齐

## [2.7.5] - 20260824
### 新增

- **Servlet 规范桥（servlet spec bridge）**：新增完整 Servlet 适配层，提供 `PerfServletContext`、`PerfAsyncContext`、`PerfRequestDispatcher`、`PerfWebConnection`、`PerfHttpPrincipal`、`ServletPartAdapter`、`PerfFilterConfig`、`ForwardWebServerHttpRequest`、`NettyHttpServletRequest` 等实现，支持在 Netty 上运行 Servlet 规范 Filter 链、`AsyncContext`、`RequestDispatcher` 转发、`HttpSession`、`Part` 等能力（配合 javax.servlet 适配）
- **`@RunInEventloop` 元注解**：`@Optimize` / `@ReactiveSupport` 标注 `@RunInEventloop`，业务方法可直接在 EventLoop 上执行，减少线程切换
- **Netty / Body / AccessLog 配置暴露**：`PropertiesConstant` 新增对应配置项，`NettyHttpServer` / `AccessLogWebFilter` 支持外部化配置
- **WSL 一键基准测试**：新增 `wsl-run-all.sh` / `wsl-benchmark.sh` / `wsl-server.sh` / `wsl-convert-cp.sh` 脚本、`WSL_SETUP.md` 与 `no-sleep.ps1`，支持可选 JFR 采集
- **报告生成器增强**：`ReportGenerator` 跳过损坏的结果 JSON，缺失容器在报告中渲染 FAIL 列并附带提示
- **内部实现文档**：新增 `docs/internals/00~19` 全量架构、请求链路与设计决策文档

### 重构

- **Servlet 会话语义增强**：`PerfHttpSession` 对 `HttpSessionBindingListener` 的值绑定 / 解绑事件处理完善

### 优化

- **解析热路径分配削减**：请求参数解析、`InMemoryHttpSessionStorage`、`MicrometerWebMetrics` 路径减少中间对象分配
- **批处理 SSE 发送**：`AbstractNettyStreamSender` 新增 `sendAll()` 批量发送，避免逐条 flush，并修复 drain 重入
- **大请求体释放优化**：大体积请求体通过 `duplicate` 引用替代 refcount 加锁，消除锁开销

### 修复

- **Multipart**：`destroy()` 释放 decoder 以归还池化内存；修复 destroy 顺序、`undecodedChunk`、refcount 泄漏；`RequestPart` 缺失时返回 400 而非 500
- **WebSocket**：升级被拒绝时释放请求；修复 h2c fragment buffer 泄漏与 WS upgrade 泄漏
- **Async**：异步超时改为懒调度并修正 metrics 计时；`SSE emitter select`、`ReactiveSupport` 首个请求 NPE、冷流发布者背压 refill 修复
- **路由与参数绑定**：`SuffixPathRouterOptimizer` off-by-one 修复；映射与参数解析启动期 fail-fast 校验；`AbstractNamedValueResolver` 支持泛型集合元素类型转换；`getReader` 回落 UTF-8 编码
- **文件下载**：使用 `Content-Length` 定长帧，避免 chunked 传输
- **大请求体**：异步读取与释放同步，避免已释放 `ByteBuf` 被访问
- **管理端口隔离**：management dispatcher 不再参与主端口路由查找
- **Interceptor**：`afterCompletion` 语义修正
- **Netty 层**：`@Sharable` 处理器生命周期与 h2c buffer leak guard；`NettyHttpHandler` 请求路径 NPE 修复
- **Batch 队列**：`DisruptorQueue` RingBuffer 大小 clamp、溢出保护；`BatchScanner` 泛型类型解析修复
- **SSL PEM 解析**：`SslContextFactory` 支持 classpath 前缀与流式加载
- **FilterWrapper**：实例级唯一 `id`，避免同类多实例被按类名去重误杀
- **访问日志**：`AccessLogWebFilter` 错误状态记录与格式修正

### 构建

- **`.gitattributes`**：shell 脚本强制 LF 行尾
- **基准模块**：支持可选 JFR 采集依赖

### 文档

- **benchmark 数据刷新**：WSL / 多实例趋势图数据更新
- **内部实现文档**：servlet bridge / async 流式 / batch 模块文档补齐

## [2.7.4] - 20260806
### 新增

- **`JsonConverter.toJsonBytes()` API**：`JsonConverter` 接口新增直接输出 `byte[]` 的方法，Jackson / Fastjson 转换器实现，跳过中间 `String` 拷贝，降低响应序列化开销
- **SSE 测试体系扩充**：新增 `DefaultNettyStreamSenderTest`、`EarlyEncodeNettyStreamSenderTest`、`HttpBodyCodecRegistryWriteNegotiationTest`、`NettyHttpHeadersAdapterTest` 等
- **一键 JFR flame graph 脚本**：`spring-web-benchmark/jfr-hotspot.sh` / `analyze-jfr2.sh`，批量生成 CPU 热点火焰图；`analyze_jfr.py` CPU 热点分析脚本纳入版本控制

### 重构

- **SSE 流式发送重构**：`NettyStreamSender` 拆分为 `DefaultNettyStreamSender`（EventLoop 延迟编码）与 `EarlyEncodeNettyStreamSender`（App 线程早编码 byte[] 快照），支持数据提前冻结
- **HTTP 头零拷贝视图**：新增 `NettyHttpHeadersAdapter`，`WebHttpHeaders` 直接委托 Netty 头存储，消除请求头 O(n) 拷贝
- **Body 内容协商重写**：`HttpBodyCodecRegistry` 写路径协商逻辑重构，新增写协商专项测试
- **Arg 模块优化**：`SpringHandlerMethodArgumentResolverAdapter` 重构为 `Provider` 形式；validator 在方法参数上下文缓存
- **Retval 模块优化**：`ReturnValueResolverRegistry` / `MethodReturnValueContext` 重构
- **404/405 免栈异常**：改用 `StacklessResponseStatusException`，降低请求路径异常构造开销
- **线程池安全替换**：`BizPoolRegistry` 支持安全替换旧线程池

### 优化

- **Jackson converter**：序列化路径优化
- **CORS 异常映射**：`CorsRegistry` / 异常解析器异常映射精简

### 修复

- **SSE 单条消息溢出**：`EarlyEncodeNettyStreamSender` 单条消息超过 `maxFlushBytes` 时直接独立写入，不再抛 `IndexOutOfBoundsException`
- **`sendError()` JSON 转义**：错误响应 message 转义引号 / 反斜杠 / 控制字符，避免畸形 JSON
- **`NettyServerHttpResponse.flush()` 失败后 buffer 置空**：防止返回已释放的 ByteBuf
- **路由匹配数组越界**：`NameValueExpressionSupport` / `ParamOrHeaderMatcher` / `SuffixPathRouterOptimizer` 修复
- **Async 状态校验**：`AsyncSupportUtils` / `PerfAsyncWebRequest` 状态校验逻辑修正
- **错误信息修正**：`AbstractFastFailHttpServletRequest` 10 处复制粘贴错误信息改为对应方法名
- **Actuator 管理端口 Bean**：补 `@ConditionalOnMissingBean`
- **Batch 优雅停机**：`DisruptorQueue` / `NettyHttpServer` 停机流程完善

### 构建

- **`spring-boot-maven-plugin` 版本纳入根 pom `pluginManagement`**：子模块插件版本与 `${spring-boot.version}` 对齐

### 文档

- **README 默认语言改为英文**，新增 `README_CN.md`
- **同步 master 的 `2.7.x-migration-checklist.md`** backport 降级适配指南

## [3.2.4] - 20260805
### 新增

- **`JsonConverter.toJsonBytes()` API**：`JsonConverter` 接口新增直接输出 `byte[]` 的方法，Jackson / Fastjson 转换器实现，跳过中间 `String` 拷贝，降低响应序列化开销
- **Spring Boot 4.x 事件适配桥**：新增 `Boot4WebServerInitializedEventBridge` 及 `Boot4WebServerInitializedEventAutoConfiguration`，SB4 环境下正确发射 `WebServerInitializedEvent`
- **GraalVM native-image 运行时提示**：新增 `SpringWebRuntimeHints`，补充 SB4 / 原生镜像场景的反射注册配置
- **SSE 测试体系扩充**：新增 `DefaultNettyStreamSenderTest`、`EarlyEncodeNettyStreamSenderTest`、`HttpBodyCodecRegistryWriteNegotiationTest`、`NettyHttpHeadersAdapterTest` 等
- **一键 JFR flame graph 脚本**：`spring-web-benchmark/jfr-hotspot.sh` / `analyze-jfr2.sh`，批量生成 CPU 热点火焰图；`analyze_jfr.py` CPU 热点分析脚本纳入版本控制

### 重构

- **SSE 流式发送重构**：`NettyStreamSender` 拆分为 `DefaultNettyStreamSender`（EventLoop 延迟编码）与 `EarlyEncodeNettyStreamSender`（App 线程早编码 byte[] 快照），支持数据提前冻结
- **HTTP 头零拷贝视图**：新增 `NettyHttpHeadersAdapter`，`WebHttpHeaders` 直接委托 Netty 头存储，消除请求头 O(n) 拷贝
- **Body 内容协商重写**：`HttpBodyCodecRegistry` 写路径协商逻辑重构，新增写协商专项测试
- **Arg 模块优化**：`SpringHandlerMethodArgumentResolverAdapter` 重构为 `Provider` 形式；validator 在方法参数上下文缓存
- **Retval 模块优化**：`ReturnValueResolverRegistry` / `MethodReturnValueContext` 重构
- **404/405 免栈异常**：改用 `StacklessResponseStatusException`，降低请求路径异常构造开销
- **线程池安全替换**：`BizPoolRegistry` 支持安全替换旧线程池

### 优化

- **Jackson converter**：序列化路径优化
- **CORS 异常映射**：`CorsRegistry` / 异常解析器异常映射精简

### 修复

- **SSE 单条消息溢出**：`EarlyEncodeNettyStreamSender` 单条消息超过 `maxFlushBytes` 时直接独立写入，不再抛 `IndexOutOfBoundsException`
- **`sendError()` JSON 转义**：错误响应 message 转义引号 / 反斜杠 / 控制字符，避免畸形 JSON
- **`NettyServerHttpResponse.flush()` 失败后 buffer 置空**：防止返回已释放的 ByteBuf
- **路由匹配数组越界**：`NameValueExpressionSupport` / `ParamOrHeaderMatcher` / `SuffixPathRouterOptimizer` 修复
- **Async 状态校验**：`AsyncSupportUtils` / `PerfAsyncWebRequest` 状态校验逻辑修正
- **错误信息修正**：`AbstractFastFailHttpServletRequest` 10 处复制粘贴错误信息改为对应方法名
- **Actuator 管理端口 Bean**：补 `@ConditionalOnMissingBean`
- **Batch 优雅停机**：`DisruptorQueue` / `NettyHttpServer` 停机流程完善

### 构建

- **`spring-boot-maven-plugin` 版本纳入根 pom `pluginManagement`**：子模块插件版本与 `${spring-boot.version}` 对齐
- **JDK 25 构建支持**：Lombok 升级至 1.18.46、Mockito 升级至 5.19.0、ByteBuddy 升级至 1.18.11，修复 JDK 25 下 Lombok 注解处理崩溃（`TypeTag::UNKNOWN`）及测试期兼容性

### 文档

- **README 默认语言改为英文**，新增 `README_CN.md`
- **`module.md` 等 AI 上下文文档更新**

## [3.2.3] - 20260710
### 新增

- **AI/LLM 集成示例**：新增 `spring-web-example-ai` 示例子模块，演示 Spring AI（OpenAI 兼容 API）的同步对话与 SSE 流式交互
- **Metrics SPI 机制**：新增 `WebMetrics` / `NoOpWebMetrics` 核心指标 SPI，在请求路径零开销（NoOp 通过 JIT 常量折叠消除计时路径）
  - `MicrometerWebMetrics` — 基于 Micrometer 的实现，记录 `dispatcher.request.duration`（Timer，按 method/path/status 标签）、`dispatcher.exception`（Counter，按 type/resolved 标签）
  - `BatchMetrics` / `NoOpBatchMetrics` — 批处理模块指标 SPI
  - `MicrometerBatchMetrics` — 基于 Micrometer 的批处理指标实现，记录入队/丢弃/溢出/处理耗时/批次大小/队列容量等指标
  - 自动装配：`spring-boot-starter-web` 引入 Micrometer 时自动激活，无需额外配置
- **BizPoolRegistry 增强**：自动发现 Spring 容器中的 `ExecutorService` Bean 并注册为线程池；`@RunInPool("beanName")` 未命中本地 pools 时兜底到 Spring 容器按名称查找
- **BizPoolRegistry Micrometer Gauges**：自动为已注册的线程池注册 active threads / queue size / completed tasks 三个 Gauge
- **Netty 层指标**：新增 `NettyMetricsHandler`（Sharable ChannelHandler），通过 `channelActive`/`channelInactive` 追踪活跃 TCP 连接数
  - 自动注册 `netty.connections.active` Gauge（当前活跃 TCP 连接数）
  - 自动注册 `netty.eventloop.pending.tasks` Gauge（所有 EventLoop 待处理任务总数）
  - 暴露 `NettyHttpServer.getWorkerGroup()` / `getActiveConnectionCount()` 用于指标注册

### 重构

- **Benchmark 模块重构**：消除冗余的 `*-filter` 独立子模块（perf-filter / tomcat-filter / undertow-filter / webflux-filter），将 filter 场景合并到对应主模块中，Maven profiles 从 10 个精简到 5 个；删除 Windows 批处理脚本，统一为 shell 脚本
- **Benchmark 场景重命名**：`validatePost` → `valid`、`sseStream` → `sse`、`jsonEchoLarge` / `largeResponse` → `bytesLarge`，语义更清晰
- **Benchmark DTO**：新增 `UserReq` / `UserResp` DTO 类，统一请求/响应模型
- **报告生成器增强**：`ReportGenerator` 重构，`GcMetrics` / `Jdk8GcLogParser` / `Jdk11GcLogParser` 增强 GC 日志解析

### 安全

- **转发头保护**：`Forwarded` / `X-Forwarded-Proto` 头默认不信任，新增 `server.use-forwarded-headers=false` 配置开关；部署在反向代理后方时需显式开启，防止客户端伪造 scheme
- **CRLF 注入防护**：`NettyMultipartFile.buildContentDisposition()` 对 name/filename 进行 `\r` / `\n` 清洗，防止 multipart 文件上传时 HTTP 响应头分裂攻击
- **HTTP 解析器限制**：新增 `server.http.max-initial-line-length`（默认 4KB）、`server.http.max-header-size`（默认 8KB）、`server.http.max-chunk-size`（默认 8KB）配置项，防止超大请求行/头部/chunk DoS 攻击
- **读超时保护**：新增 `server.http.read-timeout`（默认 30s）配置项，请求体聚合前防止慢速客户端无限期占用连接
- **WebSocket Origin 校验增强**：`setAllowedOrigins` 传入空列表时拒绝所有带 Origin 头的跨域请求（之前空列表等同于未配置）
- **异常信息收敛**：`ExceptionRegistry.handle()` 未处理异常时返回通用 `Internal Server Error` 而非将异常类名/消息暴露给客户端

### 优化

- **访问日志**：新增 `AccessLogWebFilter`，通过 `server.accesslog.enabled=true` 开启，以最早 Order 在 Filter 链最外层统计完整请求耗时；日志格式：`remoteAddr method uri statusCode elapsedMs "user-agent"`
- **日志参数化**：全局使用 SLF4J `{}` 占位符替代字符串拼接（InterceptorRegistry、PathPatternRouter、NettyServerHttpResponse、BackpressureHandler 等），消除日志关闭时的字符串构造开销
- **异常日志增强**：关键路径异常日志包含完整堆栈（`NettyStreamSender`、SSE 超时清理异常等），便于问题定位
- **`BizPoolRegistry.register()`**：接受 `ExecutorService` 而非 `ThreadPoolExecutor`，支持更广泛的线程池类型
- **优雅关闭**：新增 `NettyHttpHandler.setShuttingDown()`，关闭时新到达请求直接返回 503 `Service Unavailable`；`ManagementNettyHttpServer.stop()` 先拒绝新请求再关闭 EventLoopGroup，避免优雅关闭过程中处理新请求
- **应用层背压**：`DispatcherHandler.handleWithMappingResult()` 捕获 `RejectedExecutionException`，业务线程池满载时返回 503 + `Retry-After: 5` 头，避免阻塞 EventLoop 线程；优雅关闭中线程池已关闭时回退到 EventLoop 兜底执行

### 修复

- **SSE 超时监测任务未清除**：`NettyStreamSender.onCompleteSuccess()` / `onCompleteError()` 中调用 `resp.setTimeout(null, -1)` 取消超时调度任务，防止流结束后超时任务残留导致连接泄漏

### 文档

- **README / CONTRIBUTING / SECURITY 文档更新**
- **文档内容纠正**

## [2.7.3] - 20260710
### 新增

- **Metrics SPI 机制**：新增 `WebMetrics` / `NoOpWebMetrics` 核心指标 SPI，在请求路径零开销（NoOp 通过 JIT 常量折叠消除计时路径）
  - `MicrometerWebMetrics` — 基于 Micrometer 的实现，记录 `dispatcher.request.duration`（Timer，按 method/path/status 标签）、`dispatcher.exception`（Counter，按 type/resolved 标签）
  - `BatchMetrics` / `NoOpBatchMetrics` — 批处理模块指标 SPI
  - `MicrometerBatchMetrics` — 基于 Micrometer 的批处理指标实现，记录入队/丢弃/溢出/处理耗时/批次大小/队列容量等指标
  - 自动装配：`spring-boot-starter-web` 引入 Micrometer 时自动激活，无需额外配置
- **BizPoolRegistry 增强**：自动发现 Spring 容器中的 `ThreadPoolExecutor` Bean 并注册为线程池；`@RunInPool("beanName")` 未命中本地 pools 时兜底到 Spring 容器按名称查找
- **BizPoolRegistry Micrometer Gauges**：自动为已注册的线程池注册 active threads / queue size / completed tasks 三个 Gauge
- **Netty 层指标**：新增 `NettyMetricsHandler`（Sharable ChannelHandler），通过 `channelActive`/`channelInactive` 追踪活跃 TCP 连接数
  - 自动注册 `netty.connections.active` Gauge（当前活跃 TCP 连接数）
  - 自动注册 `netty.eventloop.pending.tasks` Gauge（所有 EventLoop 待处理任务总数）
  - 暴露 `NettyHttpServer.getWorkerGroup()` / `getActiveConnectionCount()` 用于指标注册

### 重构

- **Benchmark 模块重构**：消除冗余的 `*-filter` 独立子模块（perf-filter / tomcat-filter / undertow-filter / webflux-filter），将 filter 场景合并到对应主模块中，Maven profiles 从 10 个精简到 5 个；删除 Windows 批处理脚本，统一为 shell 脚本
- **Benchmark 场景重命名**：`validatePost` → `valid`、`sseStream` → `sse`、`jsonEchoLarge` / `largeResponse` → `bytesLarge`，语义更清晰
- **Benchmark DTO**：新增 `UserReq` / `UserResp` DTO 类，统一请求/响应模型
- **报告生成器增强**：`ReportGenerator` 重构，`GcMetrics` / `Jdk8GcLogParser` / `Jdk11GcLogParser` 增强 GC 日志解析

### 安全

- **转发头保护**：`Forwarded` / `X-Forwarded-Proto` 头默认不信任，新增 `server.use-forwarded-headers=false` 配置开关；部署在反向代理后方时需显式开启，防止客户端伪造 scheme
- **CRLF 注入防护**：`NettyMultipartFile.buildContentDisposition()` 对 name/filename 进行 `\r` / `\n` 清洗，防止 multipart 文件上传时 HTTP 响应头分裂攻击
- **HTTP 解析器限制**：新增 `server.http.max-initial-line-length`（默认 4KB）、`server.http.max-header-size`（默认 8KB）、`server.http.max-chunk-size`（默认 8KB）配置项，防止超大请求行/头部/chunk DoS 攻击
- **读超时保护**：新增 `server.http.read-timeout`（默认 30s）配置项，请求体聚合前防止慢速客户端无限期占用连接
- **WebSocket Origin 校验增强**：`setAllowedOrigins` 传入空列表时拒绝所有带 Origin 头的跨域请求（之前空列表等同于未配置）
- **异常信息收敛**：`ExceptionRegistry.handle()` 未处理异常时返回通用 `Internal Server Error` 而非将异常类名/消息暴露给客户端

### 优化

- **访问日志**：新增 `AccessLogWebFilter`，通过 `server.accesslog.enabled=true` 开启，以最早 Order 在 Filter 链最外层统计完整请求耗时；日志格式：`remoteAddr method uri statusCode elapsedMs "user-agent"`
- **日志参数化**：全局使用 SLF4J `{}` 占位符替代字符串拼接（InterceptorRegistry、PathPatternRouter、NettyServerHttpResponse、BackpressureHandler 等），消除日志关闭时的字符串构造开销
- **异常日志增强**：关键路径异常日志包含完整堆栈（`NettyStreamSender`、SSE 超时清理异常等），便于问题定位
- **`BizPoolRegistry.register()`**：接受 `ExecutorService` 而非 `ThreadPoolExecutor`，支持更广泛的线程池类型
- **优雅关闭**：新增 `NettyHttpHandler.setShuttingDown()`，关闭时新到达请求直接返回 503 `Service Unavailable`；`ManagementNettyHttpServer.stop()` 先拒绝新请求再关闭 EventLoopGroup，避免优雅关闭过程中处理新请求
- **应用层背压**：`DispatcherHandler.handleWithMappingResult()` 捕获 `RejectedExecutionException`，业务线程池满载时返回 503 + `Retry-After: 5` 头，避免阻塞 EventLoop 线程；优雅关闭中线程池已关闭时回退到 EventLoop 兜底执行

### 修复

- **SSE 超时监测任务未清除**：`NettyStreamSender.onCompleteSuccess()` / `onCompleteError()` 中调用 `resp.setTimeout(null, -1)` 取消超时调度任务，防止流结束后超时任务残留导致连接泄漏

### 文档

- **README / CONTRIBUTING / SECURITY 文档更新**
- **文档内容纠正**

## [3.2.2] - 20260705
### 新增

- **Servlet 会话支持**：桥接模块完整实现 `jakarta.servlet.http.HttpSession` 接口
  - `PerfHttpSession` — 基于框架请求上下文的 Servlet HttpSession 实现，支持属性存取、过期跟踪和失效生命周期
  - `PerfHttpSessionManager` — 会话管理器，支持创建/获取/失效操作
  - `HttpSessionStorage` / `InMemoryHttpSessionStorage` — 可插拔的会话存储 SPI 及默认内存实现
  - `ServletAdapterContext` 增强，将会话传播到 Servlet API 包装器
- **示例模块扩展**：从 5 个增加到 12 个可运行的示例子模块
  - `spring-web-example-actuator` — Actuator 端点监控示例
  - `spring-web-example-async` — 异步请求处理（Callable/DeferredResult/SSE）示例
  - `spring-web-example-data` — Spring Data JPA 仓储集成示例
  - `spring-web-example-openapi` — OpenAPI 3.0 文档自动生成示例
  - `spring-web-example-swaggerui` — Swagger UI 静态资源服务示例
  - `spring-web-example-shiro` — Apache Shiro 认证与会话管理示例
  - `spring-web-example-spring-security` — Spring Security 认证与会话管理示例
- **英文文档**：完整翻译全部项目文档（10 篇，涵盖概述、快速开始、高级用法、性能基准、配置、扩展、模块、性能原则、兼容性），维护于 `docs/en/` 目录
- **Swagger UI 自动配置**：新增 `SwaggerUiAutoConfiguration`，当 `swagger-ui` 在 classpath 时自动提供静态资源；`SwaggerUiProperties` 配置路径和资源位置
- **OpenAPI 文档端点**：新增 `OpenApiDocController`，在可配置路径提供 OpenAPI JSON 规范

### 优化

- **`DispatcherHandler` 优化**：精简过滤器链流程和错误路径处理
- **多版本兼容性验证**：通过 Maven profiles 验证 Spring Boot 3.0.x ~ 4.1.x 兼容性

## [2.7.2] - 20260704
### 新增

- **Servlet 会话支持**：桥接模块完整实现 `javax.servlet.http.HttpSession` 接口
  - `PerfHttpSession` — 基于框架请求上下文的 Servlet HttpSession 实现，支持属性存取、过期跟踪和失效生命周期
  - `PerfHttpSessionManager` — 会话管理器，支持创建/获取/失效操作
  - `HttpSessionStorage` / `InMemoryHttpSessionStorage` — 可插拔的会话存储 SPI 及默认内存实现
  - `ServletAdapterContext` 增强，将会话传播到 Servlet API 包装器
- **示例模块扩展**：从 5 个增加到 12 个可运行的示例子模块
  - `spring-web-example-actuator` — Actuator 端点监控示例
  - `spring-web-example-async` — 异步请求处理（Callable/DeferredResult/SSE）示例
  - `spring-web-example-data` — Spring Data JPA 仓储集成示例
  - `spring-web-example-openapi` — OpenAPI 3.0 文档自动生成示例
  - `spring-web-example-swaggerui` — Swagger UI 静态资源服务示例
  - `spring-web-example-shiro` — Apache Shiro 认证与会话管理示例
  - `spring-web-example-spring-security` — Spring Security 认证与会话管理示例
- **英文文档**：完整翻译全部项目文档（10 篇，涵盖概述、快速开始、高级用法、性能基准、配置、扩展、模块、性能原则、兼容性），维护于 `docs/en/` 目录
- **Swagger UI 自动配置**：新增 `SwaggerUiAutoConfiguration`，当 `swagger-ui` 在 classpath 时自动提供静态资源；`SwaggerUiProperties` 配置路径和资源位置
- **OpenAPI 文档端点**：新增 `OpenApiDocController`，在可配置路径提供 OpenAPI JSON 规范

### 优化

- **`DispatcherHandler` 优化**：精简过滤器链流程和错误路径处理
- **兼容性验证**：通过 Maven profile 验证 Spring Boot 2.7.x 兼容性

## [2.7.1] - 20260701
### 新增

- **Examples 模块**：新增 `spring-web-examples` 聚合模块及 5 个示例子模块：
  - `spring-web-example-rest` — REST API 综合示例（Controller/Filter/Interceptor/异常处理）
  - `spring-web-example-batch` — 批量请求处理示例（`BatchUserController` CRUD）
  - `spring-web-example-realtime` — 实时通信示例（WebSocket 聊天 + SSE 推送）
  - `spring-web-example-servlet-bridge` — Servlet 桥接示例（Filter/Interceptor 桥接）
  - `spring-web-example-upload` — 文件上传示例（单/多文件上传、存储服务）
- **批量请求处理模块**：新增 `spring-web-batch` 模块，透明聚合高并发同类型请求为批量处理；`BatchRequest` / `@BatchMapping` / `BatchRegistry` / `BatchInvoker` 等核心组件；基于 Disruptor RingBuffer 实现背压与流量控制；自动批处理无需时间窗口

### 优化

- **配置属性重构**：`ApplicationProperties` / `PropertiesConstant` 提取公共方法，重构大小/时间配置解析，新增 `WebServerProperties` 配置类

## [3.2.0] - 20260628
### 重构

- **Jakarta EE 迁移**：`javax.servlet` → `jakarta.servlet` 6.0，Servlet API 从 4.0.1 升级到 6.0.0；`javax.validation` → `jakarta.validation`；所有桥接层代码适配 Jakarta API
- **Spring Boot 3.2.12 升级**：父 POM 从 2.7.18 升级至 3.2.12，JDK 基线从 8 提升至 17；移除显式管理的 `lombok`/`junit-jupiter`/`mockito`/`byte-buddy`/`assertj`/`servlet-api` 等版本，委托给 Spring Boot BOM 管理
- **SpringDoc OpenAPI 升级**：从 `springdoc-openapi-common` 1.7.0 迁移到 `springdoc-openapi-starter-common` 2.3.0，适配 Spring Boot 3.x 包结构
- **Spring Boot Admin 升级**：Client 版本从 2.7.10 升级到 3.2.0，适配 Jakarta API

### 新增

- **GraalVM native-image 支持**：新增 `reflect-config.json` / `resource-config.json` / `proxy-config.json` native-image 预配置，支持 GraalVM 原生镜像编译；新增 `GraalVmNativeImageConfigTest` 验证配置正确性
- **虚拟线程 E2E 测试**：新增 `VirtualThreadE2ETest`，覆盖 JDK 21 虚拟线程场景下的请求处理链路

### 优化

- **代码兼容性适配**：全局调整 `javax.*` 引用为 `jakarta.*`，包括所有 Filter/Servlet/Session 相关类及测试代码
- **Fallback 响应优化**：`ReturnValueResolverRegistry` / `BaseWebServerHttpResponse` 增加 fallback 兜底处理
- **CI 构建流升级**：JDK 17 基线构建流程适配

## [2.7.0] - 20260628
### 重构

- **版本号体系重构**：主版本号从 1.x.x 升级为 2.7.x，与 Spring Boot 主版本号对齐；项目版本从 1.0.5 升级到 2.7.0；所有子模块 parent version 同步升级

### 优化

- **Spring Boot 升级到 2.7.18**：父 POM 从 2.6.15 升级至 2.7.18，同步升级 Spring Boot Admin Client 至 2.7.10
- **CI 配置更新**：适配 Spring Boot 2.7.18 构建环境

## [1.0.5] - 20260625
### 新增

- **WebSocket 模块**：新增 `spring-web-websocket` 模块，`WebSocketConfigurer` / `WebSocketHandlerRegistration` / `WebSocketHandlerRegistry` 注册 API；`NettyWebSocketSession` 全双工通信；`WebSocketRoutingHandler` 路由分发；`WebSocketAutoConfiguration` 自动配置；E2E 测试覆盖文本、二进制、路径路由场景
- **OpenAPI 集成**：新增 `OpenApiAutoConfiguration` 自动配置 + `OpenApiAdapter`，将框架端点元数据映射为 OpenAPI 3.0 规范 JSON，支持 Swagger UI 展示；E2E 测试覆盖完整请求链路
- **`PipelineCustomizer`**：Netty ChannelPipeline 自定义扩展点，允许用户在 HTTP/1.1 和 HTTP/2 的 ChannelInitializer 中注入自定义 handler
- **`ExceptionHandlerAdvice` 增强**：处理 `HttpMediaTypeNotAcceptableException` 等更多异常类型
- **`ServletFilterPatternUtils`**：统一 Servlet Filter 路径匹配工具类，支持精确/前缀/后缀/路径匹配模式
- **`pool.default-execute-mode` 配置**：无 `@RunInPool` 时方法的默认执行策略，默认 `default`（线程池），设为 `eventloop` 可全局切回 EventLoop

### 重构

- **WebFilter 架构重构**：`WebFilter` / `WebFilterRegistration` / `WebFilterRegistry` / `DefaultFilterChain` / `RuntimeMappingWebFilter` 整体迁移到 `core/filter` 包；删除旧的 `match` 包（`ExactMatch` / `PathMatch` / `PrefixMatch` / `SuffixMatch`），统一使用 `ServletFilterPatternUtils`

## [1.0.4] - 20260621
### 新增

- **Spring Cloud 服务注册发现兼容**：在 Netty 服务器启动后发射 `WebServerInitializedEvent`，使 Nacos/Eureka/Consul 等服务注册组件能正确感知服务器就绪状态；使用 JDK 动态代理包装 `WebServerApplicationContext`，无需 Servlet 容器
- **Spring Boot Admin 兼容**：新增 `PerfApplicationFactory` 替代 SBA Client 的 `DefaultApplicationFactory`，从本框架配置自动计算 serviceUrl/managementUrl/healthUrl，不依赖 `HttpServletRequest`；新增 `SpringBootAdminClientAutoConfiguration` 自动配置，仅当用户引入 `spring-boot-admin-starter-client` 时激活
- **`NettyHttpServer.getActualPort()`**：暴露实际绑定的端口号，支持随机端口场景

## [1.0.3] - 20260618
### 新增

- **HTTP/2 支持**：新增 `Http2ChannelInitializer` 统一管理 HTTP/1.1 和 HTTP/2 的 ChannelInitializer，支持 h2（TLS ALPN）和 h2c（明文 prior knowledge）自动协商；新增 `server.http2.enabled` 配置开关
- **Netty worker 线程池可配置**：新增 `server.netty.workers` 配置项，允许自定义 worker EventLoopGroup 线程数，默认 2 × CPU 核数
- **WriteBufferWaterMark 可配置**：新增 `server.netty.write-buffer-low-watermark` / `write-buffer-high-watermark` 配置项，支持背压阈值调优（默认低水位 8KB、高水位 32KB）
- **`writeBytes(byte[])` 高效写入**：`WebServerHttpResponse` 新增 `writeBytes()` 方法，Content-Length + 单次 writeAndFlush，避免 chunked 编码，对已知大小的小负载显著提升性能
- **JMH 基准测试模块**：新增 `spring-web-benchmark` 模块，覆盖 Perf/Tomcat/Undertow/WebFlux 四种服务器，含裸机/Filter/Interceptor/SSE 对比场景及自动报告生成（GC 日志解析、内存快照）
- **文档**：新增 `docs/benchmark.md` 基准测试说明文档、README 补充

### 优化

- **SSE drain() 竞态修复**：wip 归零但队列仍有数据时主动重调度 drain，防止 `NettyStreamSender` 在生产者快于 drain 时数据残留丢失；使用 `DefaultHttpContent` 包装每个 SSE 写入块
- **NettyHttpServer 绑定失败安全清理**：端口绑定失败时及时 shutdown boss/worker EventLoopGroup，防止线程残留阻止 JVM 退出
- **代码风格统一**：`BaseWebServerHttpResponse` / `NettyServerHttpResponse` 多处格式化及方法提取

## [1.0.2] - 20260608
### 修复

- `InvokableHandlerMethod.createMethodParameters()` 获取参数，当 Controller 被代理时返回代理类的桥接方法而非真实方法导致无法正确读取参数注解
- `MappingRegistry.initComponentPhase1()` 扫描 Controller 时注解被代理对象擦除导致无法读取 `@RequestMapping`
- `MappingRegistry.initComponentPhase1()` 解析类级别 `@RequestMapping` 时仅提取 `value/path` 作为路径前缀，忽略了 `method`、`params`、`headers`、`consumes`、`produces` 约束属性

### 新增

- **类级别 @RequestMapping 约束合并**：`initComponentPhase1()` 提取类级别的 `method/params/headers/consumes/produces` 约束，通过 `mergeMatchers()` / `mergeMatcherPair()` 与方法级别约束合并（同类型合并内部列表，不同类型追加）
- **占位符解析**：`initComponentPhase1()` / `initMethodMappingContext()` 中对路径、params、headers、consumes、produces 支持 `${...}` 占位符，通过 `Environment.resolvePlaceholders()` 运行时解析
- **Spring Data Web 兼容**：新增 `SpringDataWebCompatibilityAutoConfiguration`，自动注册 `PageableHandlerMethodArgumentResolver` / `SortHandlerMethodArgumentResolver`，支持 Spring Data 分页排序参数绑定
- **统一文档注释**：为 `WebComponent`、`LifecycleWebComponent`、`WebFilter`、`FilterChain`、`HandlerInterceptor`、`CorsRegistration`、`WebServerHttpRequest`、`WebServerHttpResponse`、`RequestContext`、`Matcher`、`Router` 等全部核心接口补充 JavaDoc，明确组件契约与生命周期语义
- **新增 E2E 测试套件**：Proxy 代理继承测试（~40 个文件覆盖 Controller/API 继承、条件装配、参数绑定、过滤器排序、异常处理器等场景）、CoreFeatures E2E（P0/P1/P2）、类级别约束 E2E、并发压测、管理端口冲突测试、静态资源安全测试、Netty 错误路径测试、HTTP 请求生命周期测试
- **新增单元测试**：MappingRegistryTest 新增 13 个类级别约束合并测试、InvokableHandlerMethodTest 增强代理类参数注解测试、DispatcherHandlerTest / NettyHttpHandlerTest / AsyncSupportRegistryTest 增强

## [1.0.1] - 20260606
### 重构

- 统一类命名：`Perf*` → `SpringWeb*` / `Actuator*`，消除 `perf` 前缀歧义
- Actuator 管理端口引入独立分发器 `ManagementDispatcherHandler`，切断与主 `DispatcherHandler` 的循环依赖

### 新增

- **spring-web-support bridge 层**：兼容 Spring MVC 的 `WebMvcConfigurer` 适配（`WebMvcConfigurerBridge`），支持通过 Spring MVC 配置接口注入拦截器、参数解析器、返回值处理器、异常处理器
- **资源文件处理**：`ResourceHandlerRegistry`，支持静态资源映射和 classpath 资源服务
- **WebFilter 支持**：支持响应式风格的 `WebFilter` 过滤器链
- **管理端口 SSL**：`management.server.ssl.*` 配置支持，管理端口 HTTPS 独立部署
- **拦截器生命周期**：`LifecycleInterceptor`，监控请求处理全生命周期
- **自定义类型格式化器**：`Formatter` / `Converter` 注册表，支持 `@DateTimeFormat` 等注解
- **参数绑定**：`@RequestParam` 数据绑定增强，支持 `WebDataBinder` 注册表
- **异步支持可配置**：`AsyncSupportConfigurer`，允许自定义 async timeout / task executor
- **异常解析器递归深度限制**：最大 10 层，防止无限递归
- **映射注册表优化**：单遍遍历替代三次流遍历

### 修复

- `MetaUtils.getDefaultValue` 中使用 `==` 比较 `ValueConstants.DEFAULT_NONE`，注解代理返回不同 String 引用导致比较始终为 false，`@RequestHeader(required=true)` 不生效
- `AbstractNamedValueNullableResolver.handleMissingValue` 抛出 `IllegalStateException`（500），应抛出 `ResponseStatusException(BAD_REQUEST)` 返回 400
- `WebContext` 构造函数未设置 `webContext` 自引用，导致 `WebComponentContainer.registerWebComponent(Class)` 在管理端口模式下 NPE

## [1.0.0] - 20260603
### 新增

- 基于 Netty 4.1 的 REST Web 服务器
- Spring Boot 自动配置支持（`spring-boot-starter-web`）
- 注解驱动的 Controller 映射（`@RestController`、`@RequestMapping` 等）
- 参数解析与返回值处理
- JSON 序列化（Jackson / Fastjson2 可选）
- 文件上传支持
- SSE（Server-Sent Events）支持
- 异步响应（ListenableFuture）
- 拦截器与过滤器链
- 全局异常处理（`@ExceptionHandler`、`@RestControllerAdvice`）
- CORS 配置支持
- 路径匹配（Ant 风格 + 正则）
- Actuator 集成（独立管理端口）
- Servlet API 兼容模块（`spring-web-support`）
- 管理端点独立分发器（ManagementDispatcherHandler）
- 映射注册表优化（单遍遍历替代三次流遍历）
- 异常解析器递归深度限制（最大 10 层）
