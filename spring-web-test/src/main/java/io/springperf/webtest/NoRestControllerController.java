package io.springperf.webtest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * 测试不含 @RestController 的控制器。
 * 使用 @Controller + 方法级 @ResponseBody，验证框架仍能正确处理。
 */
@Controller
@RequestMapping("/p0/no-rest-controller")
public class NoRestControllerController {

    @GetMapping("/echo")
    @ResponseBody
    public String echo(String msg) {
        return msg != null ? msg : "";
    }

    @PostMapping("/save")
    @ResponseBody
    public ResponseEntity<String> save(@RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED).body("saved:" + body);
    }
}
