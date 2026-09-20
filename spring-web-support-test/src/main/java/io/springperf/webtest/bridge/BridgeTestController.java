package io.springperf.webtest.bridge;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.Callable;

@RestController
@RequestMapping("/bridge")
public class BridgeTestController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @GetMapping("/blocked")
    public String blocked() {
        return "should-not-reach";
    }

    @GetMapping("/format")
    public String format(@RequestParam CustomName name) {
        return name.getValue();
    }

    @GetMapping("/custom-arg")
    public String customArg(@CustomArg String value) {
        return "value=" + value;
    }

    @PostMapping("/validate")
    public String validate(@RequestBody @Validated ValidatedDto dto) {
        return "name=" + dto.getName();
    }

    @GetMapping("/custom-retval")
    public CustomE2eReturnValueDto customRetval() {
        return new CustomE2eReturnValueDto("e2e-return-value");
    }

    @GetMapping("/custom-ex")
    public String throwCustomException() {
        throw new CustomE2eBridgeException();
    }

    @PostMapping(value = "/custom-convert", consumes = "application/x-custom", produces = "application/x-custom")
    public String customConvert(@RequestBody String input) {
        return "received:" + input;
    }

    @GetMapping("/async/callable")
    public Callable<String> asyncCallable() {
        return () -> {
            Thread.sleep(50);
            return "async-callable-result";
        };
    }

    @GetMapping("/async/deferred")
    public DeferredResult<String> asyncDeferred() {
        DeferredResult<String> result = new DeferredResult<>(5000L);
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            result.setResult("async-deferred-result");
        }).start();
        return result;
    }

    @GetMapping("/async/timeout")
    public org.springframework.web.context.request.async.WebAsyncTask<String> asyncTimeout() {
        // 显式 100ms 超时 + 500ms 任务：验证异步超时机制真实打断慢任务
        // （原先依赖 BridgeE2eConfig 的 100ms 全局默认超时，该值已移除——
        //   defaultTimeout 现已正确作用于所有异步请求，全局 100ms 会误杀 1s 的 /demo/async）
        return new org.springframework.web.context.request.async.WebAsyncTask<>(100L, () -> {
            Thread.sleep(500);
            return "too-late";
        });
    }

    }
