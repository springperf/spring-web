# Spring AI 集成指南

本文说明如何将标准 Spring AI 项目迁移到本框架。核心就两步：**替换依赖** + **禁用 WebFlux**。

---

## 一、替换依赖

### 1. 移除默认 Web 框架

```xml
<!-- 移除 spring-boot-starter-web（内嵌 Tomcat） -->
<!--
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
-->
```

### 2. 添加本框架的 Starter

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 3. 添加 Spring AI 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

> Spring AI 版本通过 BOM 统一管理，参见示例项目的 `pom.xml`。

---

## 二、禁用 WebFlux

Spring AI 的 Starter 会拉入 `spring-boot-starter-webflux`，触发 Spring Boot 的响应式 Web 自动配置。本框架基于 Netty 非阻塞 I/O，但**不依赖 Spring WebFlux**，因此需要关闭 WebFlux 的自动初始化：

```yaml
spring:
  main:
    web-application-type: none
```

> `web-application-type=none` 会同时禁用 Servlet 和 Reactive 两种 Web 容器自动配置，让本框架完全接管 HTTP 层。

---

## 三、编写 Controller

迁移后，Controller 代码无需改动。`ChatClient` 注入和调用方式完全不变：

```java
@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final ChatClient chatClient;

    public AiChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("message") String message) {
        return chatClient.prompt().user(message).call().content();
    }

    @GetMapping("/chat/stream")
    public Flux<String> chatStream(@RequestParam("message") String message) {
        return chatClient.prompt().user(message).stream().content();
    }
}
```

框架会自动识别 `Flux<String>` 返回值，根据请求的 `Accept` 头选择流式输出方式：

- `text/plain` → `TextStreamEmitter`，逐块输出文本
- `text/event-stream` → `SseJsonEmitter`，以 SSE `data:` 格式输出

---

## 四、注意事项

### API Key 安全

不要在代码中硬编码 API Key。使用占位符 + 环境变量注入：

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:sk-your-key-here}
```

运行时设置环境变量：

```bash
export AI_API_KEY=sk-your-real-key
```

### 流式不工作

如果 `/chat/stream` 返回 `{"scanAvailable":true,"prefetch":-1}` 而非流式内容，说明 `ReactiveAdapterRegistry` 未正确加载。确认：

1. `reactor-core` 在 classpath 上（Spring AI 的传递依赖）
2. 本框架版本 >= 3.2.3（含 `ReactiveReturnValueResolver` 的 `getSharedInstance()` 兜底）

### 启动类

启动类无需特殊配置，标准的 `@SpringBootApplication` 即可：

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

---

## 参考

- [示例项目源码](../spring-web-examples/spring-web-example-ai)
- [模块详解](modules.md)
- [从 Spring MVC 迁移指南](quickstart.md)