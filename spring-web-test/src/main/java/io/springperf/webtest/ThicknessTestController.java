package io.springperf.webtest;

import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * E2E 厚度测试专用端点：暴露连接身份（对端端口）以验证 keep-alive 复用与连接级隔离。
 */
@RestController
@RequestMapping("/thickness")
public class ThicknessTestController {

    @GetMapping("/conn-id")
    public Map<String, Object> connectionId(WebServerHttpRequest request) {
        Map<String, Object> m = new HashMap<>();
        InetSocketAddress remote = request.getRemoteAddress();
        InetSocketAddress local = request.getLocalAddress();
        m.put("remotePort", remote != null ? remote.getPort() : -1);
        m.put("serverPort", local != null ? local.getPort() : -1);
        return m;
    }
}