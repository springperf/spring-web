package io.springperf.example.servlet.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
public class BridgeController {

    /**
     * 访问 HttpServletRequest API（通过 support 模块桥接）
     * curl http://localhost:8084/bridge/request-info
     */
    @GetMapping("/bridge/request-info")
    public Map<String, Object> requestInfo(HttpServletRequest request) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("method", request.getMethod());
        info.put("requestURI", request.getRequestURI());
        info.put("contextPath", request.getContextPath());
        info.put("queryString", request.getQueryString());
        info.put("remoteAddr", request.getRemoteAddr());
        info.put("contentType", request.getContentType());

        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        info.put("headers", headers);
        return info;
    }

    /**
     * 演示通过 HttpServletRequest 读取请求属性
     * curl http://localhost:8084/bridge/attr-test
     */
    @GetMapping("/bridge/attr-test")
    public Map<String, Object> attributeTest(HttpServletRequest request) {
        request.setAttribute("customAttr", "set-via-servlet-bridge");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attribute", request.getAttribute("customAttr"));
        result.put("sessionId", request.getRequestedSessionId());
        return result;
    }
}