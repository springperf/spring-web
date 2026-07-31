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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
    private static final String SSE_DATA;

    static {
        StringBuilder sb = new StringBuilder(200);
        sb.append("{\"chunk\":0,\"data\":\"");
        while (sb.length() < 198) {
            sb.append("0123456789");
        }
        sb.append("\"}");
        sb.setLength(200);
        SSE_DATA = sb.toString();
    }

    @GetMapping(value = "/core/sse/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseStream() {
        return Flux.range(0, SSE_CHUNK_COUNT)
                .map(i -> ServerSentEvent.<String>builder()
                        .data(SSE_DATA)
                        .build());
    }
}