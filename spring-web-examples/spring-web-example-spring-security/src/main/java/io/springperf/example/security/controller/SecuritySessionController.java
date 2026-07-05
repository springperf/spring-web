package io.springperf.example.security.controller;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/security")
public class SecuritySessionController {

    /**
     * 创建或获取当前 HttpSession，并在 session 中存入一个属性。
     */
    @GetMapping("/session-set")
    public Map<String, Object> sessionSet(@RequestParam String key, @RequestParam String value,
                                          HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        session.setAttribute(key, value);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("setKey", key);
        result.put("setValue", value);
        return result;
    }

    /**
     * 读取 HttpSession 中指定属性的值。
     */
    @GetMapping("/session-get")
    public Map<String, Object> sessionGet(@RequestParam String key,
                                          HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        Map<String, Object> result = new LinkedHashMap<>();
        if (session != null) {
            result.put("sessionId", session.getId());
            result.put("key", key);
            result.put("value", session.getAttribute(key));
        } else {
            result.put("error", "No session found");
        }
        return result;
    }

    /**
     * 获取 HttpSession 基本信息。
     */
    @GetMapping("/session-info")
    public Map<String, Object> sessionInfo(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        Map<String, Object> result = new LinkedHashMap<>();
        if (session != null) {
            result.put("sessionId", session.getId());
            result.put("creationTime", session.getCreationTime());
            result.put("lastAccessedTime", session.getLastAccessedTime());
            result.put("maxInactiveInterval", session.getMaxInactiveInterval());
        } else {
            result.put("active", false);
        }
        return result;
    }

    // ==================== 新增角色测试端点 ====================

    /**
     * ADMIN 角色可访问的端点。由 SecurityConfig 中的 hasRole("ADMIN") 保护。
     */
    @GetMapping("/admin")
    public Map<String, Object> adminEndpoint() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("role", "ADMIN");
        result.put("message", "Admin access granted");
        return result;
    }

    /**
     * USER 角色可访问的端点。由 SecurityConfig 中的 hasRole("USER") 保护。
     */
    @GetMapping("/user")
    public Map<String, Object> userEndpoint() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("role", "USER");
        result.put("message", "User access granted");
        return result;
    }
}