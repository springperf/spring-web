package io.springperf.example.data.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 400);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        if (e instanceof MethodArgumentNotValidException) {
            String msg = ((MethodArgumentNotValidException) e).getBindingResult().getFieldErrors().stream()
                    .map(err -> err.getField() + ": " + err.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            Map<String, Object> body = new HashMap<>();
            body.put("code", 400);
            body.put("message", msg);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(body);
        }
        log.error("unhandled exception", e);
        Map<String, Object> body = new HashMap<>();
        body.put("code", 500);
        body.put("message", "internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }
}