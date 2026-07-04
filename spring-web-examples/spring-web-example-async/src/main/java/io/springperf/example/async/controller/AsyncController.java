package io.springperf.example.async.controller;

import io.springperf.web.annotation.RunInPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Slf4j
@RestController
public class AsyncController {

    // ==================== 线程行为演示 ====================

    @GetMapping("/thread/event-loop")
    @RunInPool(RunInPool.EVENTLOOP)
    public Map<String, Object> eventLoopThread() {
        Map<String, Object> m = new HashMap<>();
        m.put("thread", Thread.currentThread().getName());
        return m;
    }

    @GetMapping("/thread/biz-pool")
    @RunInPool
    public Map<String, Object> bizPoolThread() {
        Map<String, Object> m = new HashMap<>();
        m.put("thread", Thread.currentThread().getName());
        return m;
    }

    // ==================== 异步返回值 ====================

    @GetMapping("/async/deferred-result")
    public DeferredResult<Map<String, Object>> deferredResult() {
        DeferredResult<Map<String, Object>> result = new DeferredResult<>(5000L);
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            Map<String, Object> m = new HashMap<>();
            m.put("status", "async-ok");
            m.put("from", "deferred-result");
            result.setResult(m);
            log.info("DeferredResult completed");
        }).start();
        return result;
    }

    @GetMapping("/async/deferred-result-error")
    public DeferredResult<Map<String, Object>> deferredResultError() {
        DeferredResult<Map<String, Object>> result = new DeferredResult<>(5000L);
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            result.setErrorResult(new RuntimeException("async-error-occurred"));
        }).start();
        return result;
    }

    @GetMapping("/async/callable")
    public Callable<Map<String, Object>> callable() {
        return () -> {
            Thread.sleep(50);
            Map<String, Object> m = new HashMap<>();
            m.put("from", "callable");
            return m;
        };
    }
}