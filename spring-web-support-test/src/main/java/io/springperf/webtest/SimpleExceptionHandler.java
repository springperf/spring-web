package io.springperf.webtest;


import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class SimpleExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> handleIAE(IllegalArgumentException ex) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", "BadRequest");
        m.put("message", ex.getMessage());
        return m;
    }
}
