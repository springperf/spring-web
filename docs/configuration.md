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
| `server.http.timeout` | 无超时 | HTTP 请求超时时间（毫秒） |

## 启动配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.check-on-startup` | `true` | 启动时校验所有 Mapping（fail-fast） |

## 业务线程池

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `pool.core-pool-size` | `50` | 核心线程数 |
| `pool.max-pool-size` | `200` | 最大线程数 |
| `pool.keep-alive-time` | `60` | 空闲线程存活时间（秒） |

## SSL 配置

标准 Spring Boot SSL 配置：

```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
```

## 管理端口（Actuator）

```properties
# 启用独立管理端口
management.server.port=9090
# 管理端点基础路径
management.endpoints.web.base-path=/actuator
# 暴露的端点
management.endpoints.web.exposure.include=health,info,metrics
```

## 完整配置示例

```properties
# 服务器
server.port=8080
server.servlet.context-path=/api

# HTTP
server.http.max-content-length=5242880
server.http.timeout=15000

# SSL
server.ssl.enabled=false

# 线程池
pool.core-pool-size=100
pool.max-pool-size=500
pool.keep-alive-time=120

# 启动校验
server.check-on-startup=true

# 管理端口
management.server.port=9090
management.endpoints.web.exposure.include=health,info
```