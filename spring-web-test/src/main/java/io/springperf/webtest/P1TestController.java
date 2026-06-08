package io.springperf.webtest;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.core.async.stream.SseJsonEmitter;
import io.springperf.web.json.JacksonConverter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * P1 E2E 测试控制器：覆盖重要功能场景。
 */
@RestController
@RequestMapping("/p1")
public class P1TestController {

    // ============ TypeMismatchException ============

    @GetMapping("/type-mismatch")
    public String typeMismatch(@RequestParam("id") int id) {
        return "ok:" + id;
    }

    // ============ PATCH method ============

    @PatchMapping("/patch-test")
    public String patchMethod() {
        return "patch-ok";
    }

    // ============ @ResponseStatus 多状态码 ============

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/status-201")
    public Map<String, Object> status201() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", 201);
        return m;
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PutMapping("/status-202")
    public Map<String, Object> status202() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", 202);
        return m;
    }

    // ============ 文件下载 Content-Disposition ============

    @GetMapping("/download-resource")
    public org.springframework.core.io.Resource downloadResource() {
        return new org.springframework.core.io.ClassPathResource("static/test.txt");
    }

    @GetMapping("/download-file")
    public java.io.File downloadFile() {
        return new java.io.File("pom.xml");
    }

    // ============ @Optimize 行为验证 ============

    @GetMapping("/optimize-check")
    @Optimize
    public Map<String, Object> optimizeCheck() {
        Map<String, Object> m = new HashMap<>();
        m.put("optimized", true);
        m.put("thread", Thread.currentThread().getName());
        return m;
    }

    // ============ @RequestPart 多个文件（通过 /p1/multi-part） ============

    @PostMapping(value = "/multi-part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String multiPart(@RequestPart("file1") MultipartFile f1,
                            @RequestPart("file2") MultipartFile f2) {
        return f1.getOriginalFilename() + "-" + f2.getOriginalFilename();
    }

    // ============ SseJsonEmitter ============

    // ============ 重复 Mapping 场景 ============

    @GetMapping("/duplicate")
    public String duplicateFirst() {
        return "duplicate-first";
    }

    @GetMapping("/duplicate")
    public String duplicateSecond() {
        return "duplicate-second";
    }

    // ============ SseJsonEmitter ============

    @GetMapping("/sse-json")
    public SseJsonEmitter sseJson() {
        SseJsonEmitter emitter = new SseJsonEmitter(new JacksonConverter());
        new Thread(() -> {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("msg", "hello");
                emitter.send(data);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }
}
