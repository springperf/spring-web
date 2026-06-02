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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

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
}