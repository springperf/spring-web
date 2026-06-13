package io.springperf.benchmark.controller.reactive;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WebFluxBenchmarkController {

    @PostMapping("/demo/echo")
    public Mono<ResponseEntity<Map<String, Object>>> echo(@RequestBody Map<String, Object> body) {
        body.put("received", true);
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @GetMapping("/demo/hello/{name}/aaaxxx")
    public Mono<Map<String, Object>> hello(@PathVariable("name") String name,
                                           @RequestParam("p1") String p1,
                                           @RequestParam("p2") String p2,
                                           @RequestParam("p3") String p3,
                                           @RequestParam("p4") String p4,
                                           @RequestParam("p5") String p5) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("p1", p1);
        m.put("p2", p2);
        m.put("p3", p3);
        m.put("p4", p4);
        m.put("p5", p5);
        return Mono.just(m);
    }

    @GetMapping("/core/deferred-result")
    public Mono<Map<String, Object>> asyncResult() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        return Mono.just(m);
    }

    @GetMapping("/core/bytes")
    public Mono<byte[]> bytes() {
        return Mono.just("Hello, Bytes!".getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/core/validate")
    public Mono<Map<String, Object>> validate(@Valid @RequestBody ValidDto dto) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", dto.getName());
        m.put("age", dto.getAge());
        return Mono.just(m);
    }

    // ==================== Large Response Body (~100KB) ====================

    private static final byte[] LARGE_RESPONSE_BODY;

    static {
        byte[] chunk = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[100 * 1024];
        for (int i = 0; i < body.length; i += chunk.length) {
            System.arraycopy(chunk, 0, body, i, Math.min(chunk.length, body.length - i));
        }
        LARGE_RESPONSE_BODY = body;
    }

    @GetMapping("/core/large-response")
    public Mono<byte[]> largeResponse() {
        return Mono.just(LARGE_RESPONSE_BODY);
    }

    // ==================== SSE Stream (模拟 LLM 流式返回) ====================

    private static final int SSE_CHUNK_COUNT = 100;
    private static final int SSE_CHUNK_SIZE = 200;
    private static final int SSE_CHUNK_INTERVAL_MS = 0;

    @GetMapping(value = "/core/sse/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseStream() {
        return Flux.range(0, SSE_CHUNK_COUNT)
                .map(i -> {
                    StringBuilder sb = new StringBuilder(SSE_CHUNK_SIZE);
                    sb.append("{\"chunk\":").append(i).append(",\"data\":\"");
                    while (sb.length() < SSE_CHUNK_SIZE - 2) {
                        sb.append("0123456789");
                    }
                    sb.append("\"}");
                    sb.setLength(SSE_CHUNK_SIZE);
                    return ServerSentEvent.<String>builder()
                            .data(sb.toString())
                            .build();
                });
    }

    public static class ValidDto {
        @NotEmpty
        private String name;
        @NotNull
        private Integer age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
