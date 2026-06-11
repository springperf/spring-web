package io.springperf.benchmark.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用基准测试 Controller — 适用于 servlet 栈（Perf/Tomcat/Undertow）。
 * <p>
 * 所有端点与 spring-web-test 保持一致的注解风格，确保公平对比。
 */
@RestController
@RequestMapping("/api")
public class BenchmarkController {

    // ==================== JSON Echo ====================

    @PostMapping("/demo/echo")
    public ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
        body.put("received", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ==================== GET with Path + Query ====================

    @GetMapping("/demo/hello/{name}/aaaxxx")
    public Map<String, Object> hello(@PathVariable("name") String name,
                                     @RequestParam("p1") String p1,
                                     @RequestParam("p2") String p2,
                                     @RequestParam("p3") String p3,
                                     @RequestParam("p4") String p4,
                                     @RequestParam("p5") String p5) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("p1", p1);
        m.put("p2", p2);
        m.put("p3", p3);
        m.put("p4", p4);
        m.put("p5", p5);
        return m;
    }

    // ==================== Async DeferredResult ====================

    @GetMapping("/core/deferred-result")
    public DeferredResult<Map<String, Object>> deferredResult() {
        DeferredResult<Map<String, Object>> result = new DeferredResult<>(5000L);
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        result.setResult(m);
        return result;
    }

    // ==================== Bytes ====================

    @GetMapping("/core/bytes")
    public byte[] bytes() {
        return "Hello, Bytes!".getBytes(StandardCharsets.UTF_8);
    }

    // ==================== Validation ====================

    @PostMapping("/core/validate")
    public Map<String, Object> validate(@Valid @RequestBody ValidDto dto) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", dto.getName());
        m.put("age", dto.getAge());
        return m;
    }

    // ==================== Large Response Body (~100KB) ====================

    private static final byte[] LARGE_RESPONSE_BODY;

    static {
        byte[] chunk = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[100 * 1024];
        for (int i = 0; i < body.length; i += chunk.length) {
            System.arraycopy(chunk, 0, body, i, Math.min(chunk.length, body.length - i));
        }
        LARGE_RESPONSE_BODY = body;
    }

    @GetMapping("/core/large-response")
    public byte[] largeResponse() {
        return LARGE_RESPONSE_BODY;
    }

    // ==================== DTO ====================

    public static class ValidDto {
        @NotEmpty
        private String name;
        @NotNull
        private Integer age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
