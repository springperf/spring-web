package io.springperf.webtest;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.core.async.stream.StreamJsonEmitter;
import io.springperf.web.core.async.stream.TextStreamEmitter;
import io.springperf.web.json.JacksonConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

@RestController
@RequestMapping("/core")
public class CoreFeaturesController {

    // ==================== 参数解析 ====================

    @GetMapping("/header")
    public Map<String, Object> testRequestHeader(@RequestHeader("X-Custom-Header") String headerValue,
                                                  @RequestHeader("User-Agent") String userAgent) {
        Map<String, Object> m = new HashMap<>();
        m.put("xCustomHeader", headerValue);
        m.put("userAgent", userAgent);
        return m;
    }

    @PostMapping("/http-entity")
    public String testHttpEntity(HttpEntity<String> httpEntity) {
        String body = httpEntity.getBody();
        return "received: " + (body != null ? body : "");
    }

    // ==================== 异步返回值 ====================

    @GetMapping("/deferred-result")
    public DeferredResult<Map<String, Object>> testDeferredResult() {
        DeferredResult<Map<String, Object>> result = new DeferredResult<>(5000L);
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            Map<String, Object> m = new HashMap<>();
            m.put("status", "ok");
            result.setResult(m);
        }).start();
        return result;
    }

    @GetMapping("/callable")
    public Callable<Map<String, Object>> testCallable() {
        return () -> {
            Thread.sleep(50);
            Map<String, Object> m = new HashMap<>();
            m.put("from", "callable");
            return m;
        };
    }

    @GetMapping("/listenable-future")
    public ListenableFuture<String> testListenableFuture() {
        SettableListenableFuture<String> future = new SettableListenableFuture<>();
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            future.set("listenable-future-result");
        }).start();
        return future;
    }

    // ==================== 同步返回值 ====================

    @GetMapping("/bytes")
    public byte[] testBytes() {
        return "Hello, Bytes!".getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/resource")
    public Resource testResource() {
        return new ClassPathResource("static/test.txt");
    }

    @GetMapping("/input-stream")
    public InputStream testInputStream() {
        return new ByteArrayInputStream("Hello, Stream!".getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 流式推送 ====================

    @GetMapping("/stream-json")
    public StreamJsonEmitter testStreamJson() {
        StreamJsonEmitter emitter = new StreamJsonEmitter(new JacksonConverter());
        new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(50);
                    Map<String, Object> m = new HashMap<>();
                    m.put("index", i);
                    emitter.send(m);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    @GetMapping("/text-stream")
    public TextStreamEmitter testTextStream() {
        TextStreamEmitter emitter = new TextStreamEmitter();
        new Thread(() -> {
            try {
                emitter.send("line1");
                Thread.sleep(50);
                emitter.send("line2");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    // ==================== 异常处理 ====================

    @GetMapping("/exception/controller-handler")
    public String triggerControllerException() {
        throw new IllegalStateException("Controller exception occurred");
    }

    @GetMapping("/exception/illegal-argument")
    public String triggerIllegalArgument() {
        throw new IllegalArgumentException("bad argument");
    }

    @GetMapping("/exception/response-status-exception")
    public String triggerResponseStatusException() {
        throw new ResponseStatusException(HttpStatus.BANDWIDTH_LIMIT_EXCEEDED, "带宽限制");
    }

    @GetMapping("/exception/custom-status-exception")
    public String triggerCustomStatusException() {
        throw new ResourceNotFoundException();
    }

    // ==================== void 返回值 ====================

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/void")
    public void voidReturn() {
        // void method - should return 204 No Content
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleControllerException(IllegalStateException e) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", e.getMessage());
        m.put("from", "controller-handler");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(m);
    }

    // ==================== CORS ====================

    @CrossOrigin(origins = "http://example.com")
    @GetMapping("/cors/annotated")
    public Map<String, Object> corsAnnotated() {
        Map<String, Object> m = new HashMap<>();
        m.put("cors", "annotated");
        return m;
    }

    // ==================== 拦截器 ====================

    @GetMapping("/interceptor/pass")
    public Map<String, Object> interceptorPass() {
        Map<String, Object> m = new HashMap<>();
        m.put("intercepted", false);
        return m;
    }

    // LoginInterceptor 拦截方法名为 "name" 的方法
    @GetMapping("/name")
    public String name() {
        return "should be blocked";
    }

    // ==================== @RunInPool 线程行为 ====================

    @GetMapping("/pool/event-loop")
    public Map<String, Object> runInEventLoop() {
        Map<String, Object> m = new HashMap<>();
        m.put("thread", Thread.currentThread().getName());
        return m;
    }

    @GetMapping("/pool/biz-pool")
    @RunInPool
    public Map<String, Object> runInBizPool() {
        Map<String, Object> m = new HashMap<>();
        m.put("thread", Thread.currentThread().getName());
        return m;
    }

    // ==================== @Valid 校验 ====================

    @PostMapping("/validate")
    public Map<String, Object> testValidation(@Validated @RequestBody ValidObj obj) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", obj.getName());
        m.put("age", obj.getAge());
        return m;
    }

    // ==================== @RequestParam 高级特性 ====================

    @GetMapping("/param-advanced")
    public Map<String, Object> paramAdvanced(
            @RequestParam("req") String required,
            @RequestParam(value = "opt", required = false) String optional,
            @RequestParam(value = "def", defaultValue = "defaultVal") String withDefault) {
        Map<String, Object> m = new HashMap<>();
        m.put("required", required);
        m.put("optional", optional);
        m.put("withDefault", withDefault);
        return m;
    }

    // ==================== @RequestHeader 高级特性 ====================

    @GetMapping("/header-advanced")
    public Map<String, Object> headerAdvanced(
            @RequestHeader("X-Required") String required,
            @RequestHeader(value = "X-Optional", required = false) String optional,
            @RequestHeader(value = "X-With-Default", defaultValue = "defaultHeaderVal") String withDefault) {
        Map<String, Object> m = new HashMap<>();
        m.put("required", required);
        m.put("optional", optional);
        m.put("withDefault", withDefault);
        return m;
    }

    // ==================== Consumes/Produces 内容协商 ====================

    @PostMapping(value = "/consumes-json", consumes = "application/json")
    public String consumesJson(@RequestBody String body) {
        return "json:" + body;
    }

    @PostMapping(value = "/consumes-xml", consumes = "application/xml")
    public String consumesXml(@RequestBody String body) {
        return "xml:" + body;
    }

    @GetMapping(value = "/produces-json", produces = "application/json")
    public Map<String, Object> producesJson() {
        Map<String, Object> m = new HashMap<>();
        m.put("format", "json");
        return m;
    }

    @GetMapping(value = "/produces-xml", produces = "application/xml")
    public String producesXml() {
        return "<response>xml</response>";
    }

    // ==================== Headers 条件匹配 ====================

    @GetMapping(value = "/headers-custom", headers = "X-Custom=myvalue")
    public String headersCustom() {
        return "header-matched";
    }

    // ==================== Params 条件不匹配（负向） ====================

    @GetMapping(value = "/params-mismatch", params = "x=y")
    public String paramsMismatch() {
        return "params-matched";
    }

    // ==================== 返回值高级 ====================

    @GetMapping("/file-download")
    public File fileDownload() {
        return new File("pom.xml");
    }

    @GetMapping("/completion-stage")
    public CompletionStage<String> completionStage() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> "completion-stage-result");
    }

    @GetMapping("/async-task")
    public WebAsyncTask<String> asyncTask() {
        return new WebAsyncTask<>(5000L, () -> {
            Thread.sleep(50);
            return "async-task-result";
        });
    }

    // ==================== 异步超时 ====================

    @GetMapping("/async-timeout")
    public WebAsyncTask<String> asyncTimeout() {
        return new WebAsyncTask<>(100L, () -> {
            Thread.sleep(500);
            return "too-late";
        });
    }

    // ==================== 日期序列化 ====================

    @GetMapping("/date-format")
    public Map<String, Object> dateFormat() {
        Map<String, Object> m = new HashMap<>();
        m.put("date", new Date());
        m.put("message", "date-test");
        return m;
    }

    // ==================== 拦截器生命周期测试 ====================

    @GetMapping("/lifecycle/check")
    public Map<String, Object> lifecycleCheck() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        return m;
    }
}