package io.springperf.webtest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * E2E 边界场景控制器：覆盖 HTTP 方法完整性、状态码、参数多值、中文编码等。
 */
@RestController
@RequestMapping("/edge")
public class EdgeCaseController {

    // ==================== HTTP 方法完整性 ====================

    @GetMapping("/method")
    public Map<String, Object> getMethod() {
        return method("GET");
    }

    @PostMapping("/method")
    public Map<String, Object> postMethod() {
        return method("POST");
    }

    @PutMapping("/method")
    public Map<String, Object> putMethod() {
        return method("PUT");
    }

    @DeleteMapping("/method")
    public Map<String, Object> deleteMethod() {
        return method("DELETE");
    }

    @PatchMapping("/method")
    public Map<String, Object> patchMethod() {
        return method("PATCH");
    }

    @RequestMapping("/method-all")
    public Map<String, Object> methodAll() {
        return method("ALL");
    }

    private Map<String, Object> method(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("method", name);
        return m;
    }

    // ==================== 状态码 ====================

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/no-content")
    public void noContent() {
        // 204 No Content
    }

    @GetMapping("/status/{code}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable int code) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        return ResponseEntity.status(code).body(m);
    }

    // ==================== 中文/UTF-8 编码 ====================

    @GetMapping("/chinese")
    public Map<String, Object> chinese(@RequestParam(value = "name", required = false) String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("greeting", "你好，世界");
        m.put("name", name != null ? name : "默认");
        return m;
    }

    @PostMapping("/chinese-body")
    public Map<String, Object> chineseBody(@RequestBody Map<String, Object> body) {
        Map<String, Object> m = new HashMap<>();
        m.put("received", body);
        return m;
    }

    // ==================== @RequestParam 多值 ====================

    @GetMapping("/multi-param")
    public Map<String, Object> multiParam(@RequestParam("ids") String[] ids,
                                          @RequestParam(value = "tags", required = false) java.util.List<String> tags) {
        Map<String, Object> m = new HashMap<>();
        m.put("ids", ids != null ? ids.length : 0);
        m.put("first", ids != null && ids.length > 0 ? ids[0] : null);
        m.put("tags", tags);
        return m;
    }

    // ==================== 404/405 区分（供测试） ====================

    @GetMapping("/only-get")
    public Map<String, Object> onlyGet() {
        return method("GET");
    }

    @GetMapping("/path-var/{id}")
    public Map<String, Object> pathVar(@PathVariable("id") String id) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        return m;
    }

    // ==================== 重定向 ====================

    @GetMapping("/redirect-to")
    public io.springperf.web.view.RedirectView redirectTo() {
        return new io.springperf.web.view.RedirectView("/api/edge/method");
    }
}
