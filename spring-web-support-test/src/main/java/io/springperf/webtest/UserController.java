package io.springperf.webtest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    @PostMapping(value = "/find/{name}", params = "v=111")
    public ResponseEntity<Map<String, Object>> find(@RequestBody RequestObj<User> req, @PathVariable("name") String name, @RequestParam("v") String v, int id, @ModelAttribute User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("tid", req.getTid());
        m.put("age", req.getData().getAge());
        m.put("name", name);
        m.put("v", v);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/create/{name:\\d+}")
    public ResponseEntity<Map<String, Object>> find(@RequestBody RequestObj<User> req, @PathVariable("name") String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("tid", req.getTid());
        m.put("age", req.getData().getAge());
        m.put("name", name);
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

    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignore) {
            }
            Map<String, Object> m = new HashMap<>();
            m.put("now", System.currentTimeMillis());
            return m;
        });
    }

    @GetMapping("/protected")
    public String protectedEndpoint() {
        return "authorized";
    }
}

