package io.springperf.example.rest.controller;

import io.springperf.example.rest.exception.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResult<String> health() {
        return ApiResult.success("OK");
    }
}