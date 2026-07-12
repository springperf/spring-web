# Spring AI Integration Guide

This guide explains how to migrate a standard Spring AI project to this framework. It only takes two steps: **replace dependencies** + **disable WebFlux**.

---

## 1. Replace Dependencies

### 1.1 Remove the Default Web Framework

```xml
<!-- Remove spring-boot-starter-web (embedded Tomcat) -->
<!--
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
-->
```

### 1.2 Add This Framework's Starter

```xml
<dependency>
    <groupId>io.github.springperf</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring-web.version}</version>
</dependency>
```

### 1.3 Add Spring AI Dependency

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

> Spring AI version is managed via BOM. See the example project's `pom.xml` for details.

---

## 2. Disable WebFlux

Spring AI's starter pulls in `spring-boot-starter-webflux`, which triggers Spring Boot's reactive web auto-configuration. This framework is built on Netty non-blocking I/O but **does not depend on Spring WebFlux**, so WebFlux auto-configuration must be suppressed:

```yaml
spring:
  main:
    web-application-type: none
```

> `web-application-type=none` disables both Servlet and Reactive web container auto-configuration, allowing this framework to fully manage the HTTP layer.

---

## 3. Write a Controller

Controller code remains unchanged after migration. `ChatClient` injection and usage are identical:

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

The framework automatically recognizes `Flux<String>` return types and selects the streaming mode based on the request's `Accept` header:

- `text/plain` → `TextStreamEmitter`, outputs text chunks
- `text/event-stream` → `SseJsonEmitter`, outputs SSE `data:` format

---

## 4. Notes

### API Key Security

Never hardcode API keys in source code. Use placeholders with environment variable injection:

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:sk-your-key-here}
```

Set the environment variable at runtime:

```bash
export AI_API_KEY=sk-your-real-key
```

### Streaming Not Working

If `/chat/stream` returns `{"scanAvailable":true,"prefetch":-1}` instead of streaming content, the `ReactiveAdapterRegistry` is not loaded correctly. Verify:

1. `reactor-core` is on the classpath (transitive dependency of Spring AI)
2. Framework version >= 3.2.3 (includes `ReactiveReturnValueResolver` fallback to `getSharedInstance()`)

### Application Class

No special configuration needed — a standard `@SpringBootApplication` works:

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

---

## References

- [Example project source](../spring-web-examples/spring-web-example-ai)
- [Module Details](en/modules.md)
- [Spring MVC Migration Guide](en/quickstart.md)