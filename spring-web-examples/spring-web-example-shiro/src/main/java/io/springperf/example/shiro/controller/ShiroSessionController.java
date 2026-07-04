package io.springperf.example.shiro.controller;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/shiro")
public class ShiroSessionController {

    // ==================== 原有 session 端点 ====================

    @GetMapping("/session-set")
    public Map<String, Object> sessionSet(@RequestParam String key, @RequestParam String value) {
        Subject subject = SecurityUtils.getSubject();
        Session session = subject.getSession();
        session.setAttribute(key, value);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("setKey", key);
        result.put("setValue", value);
        return result;
    }

    @GetMapping("/session-get")
    public Map<String, Object> sessionGet(@RequestParam String key) {
        Subject subject = SecurityUtils.getSubject();
        Session session = subject.getSession(false);

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

    @GetMapping("/session-info")
    public Map<String, Object> sessionInfo() {
        Subject subject = SecurityUtils.getSubject();
        Session session = subject.getSession(false);

        Map<String, Object> result = new LinkedHashMap<>();
        if (session != null) {
            result.put("sessionId", session.getId());
            result.put("creationTime", session.getStartTimestamp());
            result.put("lastAccessTime", session.getLastAccessTime());
            result.put("timeout", session.getTimeout());
        } else {
            result.put("active", false);
        }
        return result;
    }

    // ==================== 新增认证/授权端点 ====================

    @GetMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestParam String username,
                                                     @RequestParam String password) {
        Subject subject = SecurityUtils.getSubject();
        try {
            subject.login(new UsernamePasswordToken(username, password));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("username", username);
            result.put("sessionId", subject.getSession().getId());
            return ResponseEntity.ok(result);
        } catch (AuthenticationException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", "Invalid credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
    }

    @GetMapping("/logout")
    public Map<String, Object> logout() {
        Subject subject = SecurityUtils.getSubject();
        subject.logout();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/need-auth")
    public ResponseEntity<Map<String, Object>> needAuth() {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", subject.getPrincipal());
        result.put("authenticated", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/need-role")
    public ResponseEntity<Map<String, Object>> needRole(@RequestParam String role) {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
        if (!subject.hasRole(role)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Missing role: " + role);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", subject.getPrincipal());
        result.put("role", role);
        result.put("granted", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/need-perm")
    public ResponseEntity<Map<String, Object>> needPerm(@RequestParam String perm) {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }
        if (!subject.isPermitted(perm)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Missing permission: " + perm);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", subject.getPrincipal());
        result.put("permission", perm);
        result.put("granted", true);
        return ResponseEntity.ok(result);
    }
}