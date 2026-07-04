package io.springperf.example.actuator.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class DemoController {

    private int visitCount;

    @GetMapping("/demo/hello")
    public Map<String, Object> hello() {
        visitCount++;
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Hello Actuator");
        body.put("visits", visitCount);
        return body;
    }
}