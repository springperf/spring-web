package io.springperf.webtest.proxy;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * P5 E2E 控制器：覆盖 HttpEntity 参数、Callable 异步、byte[]/Resource 返回值、
 * 多路径映射、多方法映射、ResponseStatusException 等代理场景。
 */
@RestController
@RequestMapping("/proxy-p5")
public class ProxyP5Controller {

    // ============ HttpEntity 参数 ============

    @PostMapping("/entity-body")
    public Map<String, Object> entityBody(HttpEntity<String> entity) {
        Map<String, Object> m = new HashMap<>();
        m.put("body", entity.getBody());
        m.put("contentType", entity.getHeaders().getContentType() != null
                ? entity.getHeaders().getContentType().toString() : null);
        return m;
    }

    // ============ Callable 异步返回 ============

    @GetMapping("/callable")
    public Callable<String> callable() {
        return () -> "callable-done";
    }

    // ============ byte[] 返回值 ============

    @GetMapping("/bytes")
    public byte[] bytes() {
        return "byte-data".getBytes();
    }

    // ============ Resource 返回值 ============

    @GetMapping("/resource")
    public Resource resource() {
        return new ByteArrayResource("resource-content".getBytes());
    }

    // ============ 多路径映射 @GetMapping({"/a","/b"}) ============

    @GetMapping({"/multi-path-a", "/multi-path-b"})
    public Map<String, Object> multiPath() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", "multi-path");
        return m;
    }

    // ============ 多方法映射 @RequestMapping(method={GET,POST}) ============

    @RequestMapping(value = "/multi-method", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> multiMethod() {
        Map<String, Object> m = new HashMap<>();
        m.put("matched", "multi-method");
        return m;
    }

    // ============ ResponseStatusException ============

    @GetMapping("/gone")
    public String gone() {
        throw new ResponseStatusException(HttpStatus.GONE, "resource moved");
    }

    // ============ @InitBinder 风格参数绑定：通过 RequestEntity 参数 ============

    @PostMapping("/request-entity")
    public Map<String, Object> requestEntity(RequestEntity<String> reqEntity) {
        Map<String, Object> m = new HashMap<>();
        m.put("body", reqEntity.getBody());
        m.put("method", reqEntity.getMethod() != null ? reqEntity.getMethod().name() : null);
        m.put("url", reqEntity.getUrl() != null ? reqEntity.getUrl().toString() : null);
        return m;
    }
}