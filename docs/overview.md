# Spring-Perf Web

基于 Netty 构建的高性能 Web 框架，兼容 Spring 编程模型，可作为 Spring MVC 的即换即用替代方案。

## 特点

- **高性能** — 基于 Netty 4.1 事件循环，无阻塞 IO，无 Servlet 容器开销
- **零侵入** — 复用 `@RequestMapping`、`@RequestParam`、`@RequestBody`、`@ExceptionHandler`、`HandlerInterceptor` 等 Spring 注解与抽象
- **可伸缩** — 支持 `@RunInPool` 将业务逻辑按需调度到业务线程池，避免阻塞 EventLoop
- **异步原生** — 内置 DeferredResult、Callable、StreamEmitter、SSE、响应式流（Reactive Streams）支持
- **可扩展** — 参数解析器、返回值处理器、编解码拦截器、异常解析器、过滤器等关键节点均提供 SPI
- **生态兼容** — 可桥接 Servlet Filter、Spring MVC HandlerInterceptor、`RequestBodyAdvice`/`ResponseBodyAdvice`
- **Actuator 集成** — 支持 Spring Boot Actuator 端点，可选独立管理端口

## 模块简介

| 模块 | 说明 |
|------|------|
| `spring-web` | 核心框架：Netty 服务器、请求分发、参数解析、返回值处理、拦截器、CORS、异常处理、JSON 编解码等 |
| `spring-web-support` | 可选桥接层：集成 Servlet API、Spring MVC 拦截器、`RequestBodyAdvice`/`ResponseBodyAdvice`、Servlet Filter |
| `spring-boot-starter-web` | Spring Boot 自动配置：一键启用，自动装配所有组件，支持 Actuator |
| `spring-web-test` | 测试示例应用：包含示例控制器、过滤器、拦截器及 E2E 测试 |
| `spring-web-support-test` | Support 模块的集成测试应用 |

## 快速入口

- [快速上手](quickstart.md) — 3 分钟集成到你的项目
- [模块详解](modules.md) — 各模块职责与内部设计
- [扩展点指南](extensions.md) — 所有 SPI 与自定义方式
- [配置参考](configuration.md) — 完整配置项列表
- [高级主题](advanced.md) — 异步、流式、响应式、性能优化