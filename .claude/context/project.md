## 项目概述

基于 Netty 构建的高性能自定义 Web 框架，作为 SpringMVC 的替代方案。
通过依赖该框架并移除 SpringMVC 依赖，即可无缝完成业务项目的 Web 框架替换并获得高性能。
复用 Spring/SpringWeb 的 DI、`@RequestMapping`、校验、拦截器等概念，让业务代码无侵入使用。

## 项目配置

- Java 8 源码兼容
- Spring Boot 2.6.15 父 POM
- Netty 4.1.110.Final
- Jackson 2.17.2、Fastjson 2.0.60（provided）
- Lombok 1.18.24
- 测试：Spring Boot Test + JUnit 5.8.2 + OkHttp 4.12.0 + AssertJ 3.25.3 + Mockito 4.11.0 + Actuator