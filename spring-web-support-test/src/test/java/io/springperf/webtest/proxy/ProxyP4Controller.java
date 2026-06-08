package io.springperf.webtest.proxy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * P4 E2E 控制器：覆盖 produces/consumes 正条件、@CrossOrigin、DeferredResult、
 * {@code @ResponseStatus} 异常、@CookieValue 等代理场景。
 * <p>
 * 此类所有方法都通过 CGLIB 代理调用，验证框架在代理环境下的完整功能。
 */
@RestController
@RequestMapping("/proxy-p4")
public class ProxyP4Controller {

    // ============ produces 正条件 ============

    /**
     * produces = "application/json"：仅当请求 Accept 包含 application/json 时匹配。
     */
    @GetMapping(value = "/json-only", produces = "application/json")
    public Map<String, Object> jsonOnly() {
        Map<String, Object> m = new HashMap<>();
        m.put("format", "json");
        return m;
    }

    // ============ consumes 正条件 ============

    /**
     * consumes = "application/json"：仅当请求 Content-Type 为 application/json 时匹配。
     */
    @PostMapping(value = "/consume-json", consumes = "application/json")
    public Map<String, Object> consumeJson(@RequestBody(required = false) String body) {
        Map<String, Object> m = new HashMap<>();
        m.put("accepted", true);
        m.put("body", body);
        return m;
    }

    // ============ @CrossOrigin ============

    /**
     * 允许 https://example.com 跨域访问。
     */
    @CrossOrigin(origins = "https://example.com")
    @GetMapping("/cors")
    public Map<String, Object> cors() {
        Map<String, Object> m = new HashMap<>();
        m.put("origin", "allowed");
        return m;
    }

    // ============ DeferredResult 异步 ============

    /**
     * 异步返回 DeferredResult，验证代理 Controller 的异步支持。
     */
    @GetMapping("/async")
    public DeferredResult<ResponseEntity<Map<String, Object>>> async() {
        DeferredResult<ResponseEntity<Map<String, Object>>> result = new DeferredResult<>(5000L);
        CompletableFuture.supplyAsync(() -> {
            Map<String, Object> m = new HashMap<>();
            m.put("async", "done");
            return ResponseEntity.ok(m);
        }).whenComplete((res, ex) -> {
            if (ex != null) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", ex.getMessage());
                result.setErrorResult(ResponseEntity.status(500).body(err));
            } else {
                result.setResult(res);
            }
        });
        return result;
    }

    // ============ @ResponseStatus 异常 ============

    /**
     * 抛出 {@link BlockedResourceException}，其 @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
     * 应被框架解析为 429 状态码。
     */
    @GetMapping("/blocked-resource")
    public String blockedResource() {
        throw new BlockedResourceException("resource is blocked");
    }

}