package io.springperf.webtest;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.core.async.stream.SseEmitter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/demo")
public class UserController {

    @GetMapping("/hello/{name:\\d+}/aaa*")
    public Map<String, Object> hello(@PathVariable("name") String name, @RequestParam("v") String v) {
        Map<String, Object> m = new HashMap<>();
        m.put("message", "hello " + name);
        m.put("v", v);
        return m;
    }

    protected static List<String> convertFiledErrors(List<FieldError> fieldErrors) {
        return Optional.ofNullable(fieldErrors)
                .map(fieldErrorsInner -> fieldErrorsInner.stream()
                        .map(fieldError -> fieldError.getField() + fieldError.getDefaultMessage())
                        .collect(Collectors.toList())).orElse(null);
    }

    @PostMapping(value = "/find/{name}", params = "v=111")
    @Optimize
    public ResponseEntity<Map<String, Object>> find(@Valid @RequestBody RequestObj<User> req, BindingResult result, @PathVariable("name") String name, @RequestParam("v") String v, int id, @ModelAttribute User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("tid", req.getTid());
        m.put("age", req.getData().getAge());
        m.put("name", name);
        m.put("v", v);
        m.put("result", convertFiledErrors(result.getFieldErrors()));
        return ResponseEntity.ok(m);
    }

    @PostMapping("/echo")
    public ResponseEntity<Map<String, Object>> echo(@RequestBody Map body) {
        body.put("received", true);
        return ResponseEntity.status(201).body(body);
    }

    @GetMapping("/echo")
    @ResponseStatus(HttpStatus.FOUND)
    public String echo(String received) {
        return received;
    }

    @PostMapping(value = "/read/{name}")
    public Map<String, Object> read(@PathVariable("name") String name, @ModelAttribute User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("ids", user.getIds());
        m.put("age", user.getAge());
        m.put("name", user.getName());
        m.put("name2", name);
        return m;
    }

    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignore) {
            }
            Map<String, Object> m = new HashMap<>();
            m.put("now", System.currentTimeMillis());
            return m;
        });
    }

    @PostMapping(value = "/upload")
    public Map<String, Object> upload(List<MultipartFile> file, @RequestPart("userPart") User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("ids", user.getIds());
        m.put("age", user.getAge());
        m.put("name", user.getName());
        m.put("fileName", file.get(0).getOriginalFilename());
        m.put("size", file.get(0).getSize());
        return m;
    }

    private final TaskExecutor taskExecutor;

    public UserController(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    @GetMapping(value = "/sse")
    public SseEmitter sse() {
        SseEmitter emitter = new SseEmitter();
        emitter.onError(e -> log.error("fail :" + e.getMessage(), e));
        emitter.onTimeout(() -> log.info("timeout"));
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(50);
                    emitter.send("world + " + i);
                } catch (Exception e) {
                    log.error("sendFail" + e.getMessage(), e);
                    emitter.completeWithError(e);
                }
            }
            emitter.complete();
        }).start();
        return emitter;
    }

    /**
     * 模拟 benchmark 快速发送模式：TaskExecutor + 0间隔，触发 complete() before initialize() 竞态场景
     */
    @GetMapping(value = "/sse-fast")
    public SseEmitter sseFast() {
        SseEmitter emitter = new SseEmitter(60000L);
        taskExecutor.execute(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    emitter.send("fast-" + i);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}

