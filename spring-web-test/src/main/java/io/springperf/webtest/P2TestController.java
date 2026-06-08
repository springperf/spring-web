package io.springperf.webtest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * P2 E2E test controller: covers placeholder path, negative conditions, exception routing.
 */
@RestController
public class P2TestController {

    // ============ 占位符路径 ============

    @RequestMapping("${test.placeholder.path}")
    public Map<String, Object> placeholderPath() {
        Map<String, Object> m = new HashMap<>();
        m.put("resolved", true);
        m.put("from", "placeholder");
        return m;
    }

    @GetMapping("/${test.placeholder.segment}/nested")
    public Map<String, Object> placeholderSegment() {
        Map<String, Object> m = new HashMap<>();
        m.put("resolved", true);
        m.put("from", "placeholder-segment");
        return m;
    }

    // ============ 负向条件: consumes ============

    @PostMapping(value = "/p2/not-xml", consumes = "!application/xml")
    public Map<String, Object> notXmlConsumes(@RequestBody(required = false) String body) {
        Map<String, Object> m = new HashMap<>();
        m.put("accepted", true);
        m.put("type", body != null && body.contains("json") ? "json" : "other");
        return m;
    }

    // ============ 负向条件: headers ============

    @GetMapping(value = "/p2/no-block-header", headers = "!X-Block")
    public Map<String, Object> noBlockHeader() {
        Map<String, Object> m = new HashMap<>();
        m.put("passed", true);
        m.put("reason", "X-Block header not present");
        return m;
    }

    // ============ 负向条件: params ============

    @GetMapping(value = "/p2/no-skip-param", params = "!skip")
    public Map<String, Object> noSkipParam() {
        Map<String, Object> m = new HashMap<>();
        m.put("passed", true);
        m.put("reason", "skip param not present");
        return m;
    }

    // ============ @ExceptionHandler 父子异常路由 ============

    /**
     * Throws RuntimeException (parent type). Should be caught by the parent handler below.
     */
    @GetMapping("/p2/parent-exception")
    public String parentException() {
        throw new RuntimeException("parent exception occurred");
    }

    /**
     * Throws IllegalArgumentException (child of RuntimeException). Should be caught by
     * the more specific child handler, not the parent handler.
     */
    @GetMapping("/p2/child-exception")
    public String childException() {
        throw new IllegalArgumentException("child exception occurred");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleChildException(IllegalArgumentException e) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", e.getMessage());
        m.put("handler", "child");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleParentException(RuntimeException e) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", e.getMessage());
        m.put("handler", "parent");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(m);
    }

    // ============ Filter 阻断 ============

    @GetMapping("/p2/blocked")
    public Map<String, Object> blockedEndpoint() {
        Map<String, Object> m = new HashMap<>();
        m.put("should", "not-reach-here");
        return m;
    }

    // ============ Filter 顺序验证 ============

    @GetMapping("/p2/filter-order")
    public Map<String, Object> filterOrder() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        return m;
    }
}
