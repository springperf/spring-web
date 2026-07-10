package io.springperf.benchmark.controller;

import io.springperf.benchmark.dto.UserReq;
import io.springperf.benchmark.dto.UserResp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 通用基准测试 Controller — 适用于 servlet 栈（Perf/Tomcat/Undertow）。
 */
@RestController
@RequestMapping("/api")
public class BenchmarkController {

    // ==================== JSON Echo ====================

    @PostMapping("/demo/echo")
    public ResponseEntity<UserResp> echo(@RequestBody UserReq req) {
        UserResp resp = new UserResp();
        resp.setId(System.currentTimeMillis());
        resp.setName(req.getName());
        resp.setAge(req.getAge());
        resp.setEmail(req.getEmail());
        resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    // ==================== GET with Path + Query ====================

    @GetMapping("/demo/hello/{name}/aaaxxx")
    public UserResp hello(@PathVariable("name") String name,
                          @RequestParam("p1") String p1,
                          @RequestParam("p2") String p2,
                          @RequestParam("p3") String p3,
                          @RequestParam("p4") String p4,
                          @RequestParam("p5") String p5) {
        UserResp resp = new UserResp();
        resp.setId(System.currentTimeMillis());
        resp.setName(name);
        resp.setAge(Integer.parseInt(p1));
        resp.setEmail(p2 + "@" + p3 + ".com");
        resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return resp;
    }

    // ==================== Async DeferredResult ====================

    @GetMapping("/core/deferred-result")
    public DeferredResult<UserResp> deferredResult() {
        DeferredResult<UserResp> result = new DeferredResult<>(5000L);
        UserResp resp = new UserResp();
        resp.setId(System.currentTimeMillis());
        resp.setName("async");
        resp.setAge(0);
        resp.setEmail("async@test.com");
        resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        result.setResult(resp);
        return result;
    }

    // ==================== Bytes ====================

    @GetMapping("/core/bytes")
    public byte[] bytes() {
        return "Hello, Bytes!".getBytes(StandardCharsets.UTF_8);
    }

    // ==================== Validation ====================

    @PostMapping("/core/validate")
    public UserResp validate(@Valid @RequestBody UserReq req) {
        // 包含 Bean Validation 验证开销
        UserResp resp = new UserResp();
        resp.setId(System.currentTimeMillis());
        resp.setName(req.getName());
        resp.setAge(req.getAge());
        resp.setEmail(req.getEmail());
        resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return resp;
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
}