package io.springperf.webtest.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实现 ParamTestApi 接口，方法上有路由注解，参数上无注解。
 * 包含非接口方法 responseEntityTest 用于测试 ResponseEntity 返回值。
 * <p>
 * 配合 @EnableAspectJAutoProxy(proxyTargetClass = true) 验证 CGLIB 代理场景。
 */
@RestController
@RequestMapping("/proxy-p1")
public class ParamTestController implements ParamTestApi {

    @PostMapping("/mixed/{id}")
    @Override
    public String mixedParams(String body, String id, String key, String header,
                              String optional, String withDefault) {
        return body + "|" + id + "|" + key + "|" + header + "|"
                + (optional == null ? "null" : optional) + "|" + withDefault;
    }

    @PostMapping("/empty-body")
    @Override
    public String emptyBody(String body) {
        return "got:" + (body == null ? "null" : body);
    }

    @GetMapping("/headers")
    @Override
    public String multiHeader(List<String> values, String opt) {
        return String.join(",", values) + "|" + (opt == null ? "null" : opt);
    }

    @GetMapping("/required-header")
    @Override
    public String requiredHeader(String value) {
        return value;
    }

    @GetMapping("/entity")
    public ResponseEntity<String> responseEntityTest() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Custom-Resp", "header-value")
                .body("entity-body");
    }
}