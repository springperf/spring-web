package io.springperf.webtest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * gzip 压缩 E2E 测试控制器：提供大/小 JSON、非白名单二进制三类响应，配合 {@code server.compression.*} 验证压缩器行为。
 */
@RestController
@RequestMapping("/compression")
public class CompressionTestController {

    @GetMapping("/json-large")
    public Map<String, Object> jsonLarge() {
        Map<String, Object> m = new HashMap<>();
        StringBuilder sb = new StringBuilder(20000);
        for (int i = 0; i < 2000; i++) {
            sb.append("0123456789");
        }
        m.put("data", sb.toString());
        return m;
    }

    @GetMapping("/json-small")
    public Map<String, Object> jsonSmall() {
        Map<String, Object> m = new HashMap<>();
        m.put("data", "small");
        return m;
    }

    @GetMapping(value = "/binary-large", produces = "application/octet-stream")
    public byte[] binaryLarge() {
        byte[] b = new byte[20000];
        Arrays.fill(b, (byte) 'a');
        return b;
    }
}
