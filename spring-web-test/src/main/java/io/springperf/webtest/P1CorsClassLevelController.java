package io.springperf.webtest;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试类级别 @CrossOrigin 注解。
 */
@RestController
@RequestMapping("/p1/cors-class")
@CrossOrigin(origins = "http://class-level.example.com")
public class P1CorsClassLevelController {

    @GetMapping("/test")
    public Map<String, Object> corsClassLevel() {
        Map<String, Object> m = new HashMap<>();
        m.put("cors", "class-level");
        return m;
    }
}