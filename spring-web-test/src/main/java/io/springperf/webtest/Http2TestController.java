package io.springperf.webtest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class Http2TestController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        Map<String, Object> m = new HashMap<>();
        m.put("message", "hello");
        return m;
    }

    @PostMapping("/sample-json-body")
    public Map<String, Object> sampleJsonBody(@RequestBody Map<String, Object> body) {
        Map<String, Object> m = new HashMap<>();
        m.put("received", body);
        return m;
    }
}