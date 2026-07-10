> [English](en/configuration.md) | 中文

# 配置参考

所有配置项在 `application.properties` 中设置。

## 服务器配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | `8080` | Netty HTTP 监听端口 |
| `server.servlet.context-path` | `/` | 应用上下文路径 |

## HTTP 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.http.max-content-length` | `1048576` (1MB) | 最大请求体大小（字节） |
| `server.http.timeout` | `60000` (60s) | HTTP 请求超时时间（毫秒） |

## 异步配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.async.timeout` | `30000` (30s) | 异步请求（DeferredResult/Callable）超时时间（毫秒） |

## 优雅关闭

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.shutdown.timeout` | `30000` (30s) | 优雅关闭最大等待时间（毫秒） |

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
| `pool.queue-capacity` | 无界 | 任务队列容量（≤0 时设为 1） |
| `pool.default-execute-mode` | `default` | 无 `@RunInPool` 时方法的执行位置。`eventloop`=EventLoop，其他值为线程池名称 |

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
server.async.timeout=30000

# 优雅关闭
server.shutdown.timeout=30000

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

# 管理端口
management.server.port=9090
management.endpoints.web.exposure.include=health,info

# OpenAPI
springperf.openapi.title=My API
springperf.openapi.version=2.0.0
springperf.openapi.description=My API Description
```