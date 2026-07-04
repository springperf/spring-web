> English | [中文](../configuration.md)

# Configuration Reference

All configuration properties are set in `application.properties`.

## Server Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | Netty HTTP listening port |
| `server.servlet.context-path` | `/` | Application context path |

## HTTP Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.http.max-content-length` | `1048576` (1MB) | Maximum request body size (bytes) |
| `server.http.timeout` | `60000` (60s) | HTTP request timeout (milliseconds) |

## Async Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.async.timeout` | `30000` (30s) | Async request (DeferredResult/Callable) timeout (milliseconds) |

## Graceful Shutdown

| Property | Default | Description |
|----------|---------|-------------|
| `server.shutdown.timeout` | `30000` (30s) | Graceful shutdown max wait time (milliseconds) |

## Startup Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.check-on-startup` | `true` | Validate all Mappings at startup (fail-fast) |

## Netty Tuning

| Property | Default | Description |
|----------|---------|-------------|
| `server.netty.workers` | `0` (auto) | Netty worker EventLoop thread count, 0 = auto (CPU cores × 2) |
| `server.netty.write-buffer-low-watermark` | `8192` (8KB) | Write buffer low watermark (bytes) |
| `server.netty.write-buffer-high-watermark` | `32768` (32KB) | Write buffer high watermark (bytes) |

## HTTP/2

| Property | Default | Description |
|----------|---------|-------------|
| `server.http2.enabled` | `false` | Enable HTTP/2 support (requires SSL for browser clients) |

## Business Thread Pool

| Property | Default | Description |
|----------|---------|-------------|
| `pool.core-pool-size` | `50` | Core pool size |
| `pool.max-pool-size` | `200` | Maximum pool size |
| `pool.keep-alive-time` | `60` | Idle thread keep-alive time (seconds) |
| `pool.queue-capacity` | Unbounded | Task queue capacity (set to 1 when ≤ 0) |
| `pool.default-execute-mode` | `default` | Default execution mode when no `@RunInPool`. `eventloop`=EventLoop, other values = pool name |

## SSL Configuration

Standard Spring Boot SSL configuration:

```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

The management port also supports independent SSL configuration (prefix `management.server.ssl.*`), configured the same way.

## Management Port (Actuator)

```properties
# Enable standalone management port
management.server.port=9090
# Management endpoint base path
management.endpoints.web.base-path=/actuator
# Exposed endpoints
management.endpoints.web.exposure.include=health,info,metrics
```

## OpenAPI Documentation

| Property | Default | Description |
|----------|---------|-------------|
| `springperf.openapi.title` | `Spring Perf Web API` | API document title |
| `springperf.openapi.version` | `1.0.0` | API document version |
| `springperf.openapi.description` | `Spring Perf Web API` | API document description |

## Swagger UI

| Property | Default | Description |
|----------|---------|-------------|
| `springperf.swagger-ui.webjar-version` | `5.2.0` | Swagger UI webjar version, must match `org.webjars:swagger-ui` dependency version |

## Complete Configuration Example

```properties
# Server
server.port=8080
server.servlet.context-path=/api

# HTTP
server.http.max-content-length=5242880
server.http.timeout=15000

# Async
server.async.timeout=30000

# Graceful shutdown
server.shutdown.timeout=30000

# SSL
server.ssl.enabled=false

# Netty
server.netty.workers=0
server.netty.write-buffer-low-watermark=8192
server.netty.write-buffer-high-watermark=32768

# HTTP/2
server.http2.enabled=false

# Thread pool
pool.core-pool-size=100
pool.max-pool-size=500
pool.keep-alive-time=120
pool.queue-capacity=10000
pool.default-execute-mode=eventloop  # or pool name

# Startup validation
server.check-on-startup=true

# Management port
management.server.port=9090
management.endpoints.web.exposure.include=health,info

# OpenAPI
springperf.openapi.title=My API
springperf.openapi.version=2.0.0
springperf.openapi.description=My API Description
```