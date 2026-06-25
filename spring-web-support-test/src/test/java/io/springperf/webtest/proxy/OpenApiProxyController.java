package io.springperf.webtest.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAPI 代理测试控制器：覆盖代理场景下的 {@code @ResponseStatus}、路径变量、请求参数、请求体。
 * <p>
 * 在 CGLIB 代理环境下验证 {@link io.springperf.web.autoconfigure.openapi.OpenApiAdapter}
 * 能正确解析方法注解构建 OpenAPI 文档。
 */
@RestController
@RequestMapping("/openapi-proxy")
public class OpenApiProxyController {

    /**
     * 验证 @ResponseStatus(CREATED) + @PathVariable + @RequestParam 在代理下的解析。
     */
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/create/{id}")
    public Map<String, Object> create(@PathVariable String id, @RequestParam String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    /**
     * 验证 @ResponseStatus(ACCEPTED) + @RequestBody 在代理下的解析。
     */
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/update/{id}")
    public String update(@PathVariable String id, @RequestBody String body) {
        return "updated: " + id;
    }

    /**
     * 验证普通 GET + @RequestParam（无 @ResponseStatus，默认 200）在代理下的解析。
     */
    @GetMapping("/query")
    public String query(@RequestParam("q") String query,
                        @RequestParam(defaultValue = "10") int limit) {
        return "query: " + query + " limit: " + limit;
    }

    /**
     * 验证 @ResponseStatus(NO_CONTENT) + void 返回在代理下的解析。
     */
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/delete/{id}")
    public void delete(@PathVariable String id) {
    }
}
