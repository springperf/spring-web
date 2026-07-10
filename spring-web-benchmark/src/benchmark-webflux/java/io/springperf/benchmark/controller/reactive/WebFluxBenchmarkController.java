package io.springperf.benchmark.controller.reactive;

import io.springperf.benchmark.dto.UserReq;
import io.springperf.benchmark.dto.UserResp;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
public class WebFluxBenchmarkController {

    @PostMapping("/demo/echo")
    public Mono<ResponseEntity<UserResp>> echo(@RequestBody Mono<UserReq> req) {
        return req.map(r -> {
            UserResp resp = new UserResp();
            resp.setId(System.currentTimeMillis());
            resp.setName(r.getName());
            resp.setAge(r.getAge());
            resp.setEmail(r.getEmail());
            resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        });
    }

    @GetMapping("/demo/hello/{name}/aaaxxx")
    public Mono<UserResp> hello(@PathVariable("name") String name,
                                @RequestParam("p1") String p1,
                                @RequestParam("p2") String p2,
                                @RequestParam("p3") String p3,
                                @RequestParam("p4") String p4,
                                @RequestParam("p5") String p5) {
        UserResp resp = new UserResp();
        resp.setId(System.currentTimeMillis());
        resp.setName(name);
        resp.setAge(Integer.parseInt(p1));
        resp.setEmail(p2 + "@" + p3 + ".com");
        resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return Mono.just(resp);
    }

    @GetMapping("/core/deferred-result")
    public Mono<UserResp> asyncResult() {
        UserResp resp = new UserResp();
        resp.setId(System.currentTimeMillis());
        resp.setName("async");
        resp.setAge(0);
        resp.setEmail("async@test.com");
        resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return Mono.just(resp);
    }

    @GetMapping("/core/bytes")
    public Mono<byte[]> bytes() {
        return Mono.just("Hello, Bytes!".getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/core/validate")
    public Mono<UserResp> validate(@Valid @RequestBody Mono<UserReq> req) {
        return req.map(r -> {
            UserResp resp = new UserResp();
            resp.setId(System.currentTimeMillis());
            resp.setName(r.getName());
            resp.setAge(r.getAge());
            resp.setEmail(r.getEmail());
            resp.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return resp;
        });
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

    // ==================== SSE Stream ====================

    private static final int SSE_CHUNK_COUNT = 100;
    private static final int SSE_CHUNK_SIZE = 200;
    private static final int SSE_CHUNK_INTERVAL_MS = 0;

    @GetMapping(value = "/core/sse/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseStream() {
        return buildSseFlux(0);
    }

    private Flux<ServerSentEvent<String>> buildSseFlux(long intervalMs) {
        Flux<ServerSentEvent<String>> flux = Flux.range(0, SSE_CHUNK_COUNT)
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
        if (intervalMs > 0) {
            flux = flux.delayElements(java.time.Duration.ofMillis(intervalMs));
        }
        return flux;
    }
}