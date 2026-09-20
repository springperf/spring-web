package io.springperf.webtest.bridge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/servlet-bridge")
public class ServletBridgeTestController {

    @GetMapping("/request-url")
    public Map<String, String> getRequestUrl(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        result.put("requestURL", request.getRequestURL().toString());
        result.put("requestURI", request.getRequestURI());
        result.put("queryString", request.getQueryString());
        result.put("scheme", request.getScheme());
        result.put("serverName", request.getServerName());
        result.put("serverPort", String.valueOf(request.getServerPort()));
        result.put("remoteAddr", request.getRemoteAddr());
        result.put("remoteHost", request.getRemoteHost());
        result.put("remotePort", String.valueOf(request.getRemotePort()));
        result.put("localAddr", request.getLocalAddr());
        result.put("localPort", String.valueOf(request.getLocalPort()));
        result.put("secure", String.valueOf(request.isSecure()));
        result.put("contextPath", request.getContextPath());
        result.put("servletPath", request.getServletPath());
        result.put("method", request.getMethod());
        return result;
    }

    @GetMapping("/redirect")
    public void redirect(HttpServletResponse response) throws IOException {
        response.sendRedirect("/api/servlet-bridge/redirect-target");
    }

    @GetMapping("/redirect-target")
    public String redirectTarget() {
        return "redirected";
    }

    /**
     * 桥接模式 SSE：返回 native {@code SseEmitter}，经 servlet 响应包装写出。
     * 用于补齐桥接模式流式路径的 E2E（见 {@code ServletBridgeSseE2eTest}）。
     */
    @GetMapping(value = "/sse-stream", produces = "text/event-stream;charset=UTF-8")
    public io.springperf.web.core.async.stream.SseEmitter sseStream() {
        io.springperf.web.core.async.stream.SseEmitter emitter =
                new io.springperf.web.core.async.stream.SseEmitter();
        Thread worker = new Thread(() -> {
            try {
                emitter.send("bridge-a");
                Thread.sleep(30);
                emitter.send("bridge-b");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, "bridge-sse-worker");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    /**
     * Spring 兼容的 mvc {@code SseEmitter}（extends ResponseBodyEmitter extends StreamEmitter）：
     * 经 {@code ResponseBodyEmitterReturnValueResolver} 注入 encodeFunction 后走同一 native 内核。
     */
    @GetMapping(value = "/sse-mvc", produces = "text/event-stream;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter sseMvc() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        Thread worker = new Thread(() -> {
            try {
                emitter.send("mvc-a");
                Thread.sleep(30);
                emitter.send("mvc-b");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, "bridge-mvc-sse-worker");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    /** Spring 兼容的 {@code ResponseBodyEmitter}：非 SSE，按 codec 编码后顺序写出。 */
    @GetMapping(value = "/emitter", produces = "text/plain;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter emitter() {
        org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter();
        Thread worker = new Thread(() -> {
            try {
                emitter.send("emitter-1");
                Thread.sleep(30);
                emitter.send("emitter-2");
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, "bridge-emitter-worker");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    /**
     * {@code StreamingResponseBody}：独立 {@code @FunctionalInterface}（不是 StreamEmitter），
     * 当前无 resolver 认领 → 用于固化「未实现」的实测行为（见 ServletBridgeSseE2eTest）。
     */
    @GetMapping(value = "/streaming-response-body", produces = "text/plain;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody streamingResponseBody() {
        return out -> {
            out.write("srb-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write("srb-2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };
    }

    /** 桥接模式 SSE 异常终止：发一段后 completeWithError → 截断收尾（与 native 语义一致）。 */
    @GetMapping(value = "/sse-error", produces = "text/event-stream;charset=UTF-8")
    public io.springperf.web.core.async.stream.SseEmitter sseError() {
        io.springperf.web.core.async.stream.SseEmitter emitter =
                new io.springperf.web.core.async.stream.SseEmitter();
        Thread worker = new Thread(() -> {
            try {
                emitter.send("bridge-err-1");
                Thread.sleep(50);
                emitter.completeWithError(new IllegalStateException("bridge-boom"));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, "bridge-sse-error-worker");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    @GetMapping("/mime-type")
    public Map<String, String> getMimeType(@RequestParam String file, HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        result.put("mimeType", request.getServletContext().getMimeType(file));
        return result;
    }

    /** 处理器抛异常 → 期望 500（用于桥接 vs native 错误码一致性对照）。 */
    @GetMapping("/error-500")
    public String error500() {
        throw new IllegalStateException("bridge-boom");
    }

    /** 异步超时（150ms）→ 期望与 native 相同的超时状态码对照。 */
    @GetMapping("/async-timeout")
    public org.springframework.web.context.request.async.DeferredResult<String> asyncTimeout() {
        org.springframework.web.context.request.async.DeferredResult<String> result =
                new org.springframework.web.context.request.async.DeferredResult<>(150L);
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            result.setResult("bridge-late");
        }, "bridge-async-timeout-worker");
        worker.setDaemon(true);
        worker.start();
        return result;
    }

    @GetMapping("/server-info")
    public Map<String, String> getServerInfo(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        result.put("serverInfo", request.getServletContext().getServerInfo());
        result.put("majorVersion", String.valueOf(request.getServletContext().getMajorVersion()));
        result.put("contextPath", request.getServletContext().getContextPath());
        result.put("servletContextName", request.getServletContext().getServletContextName());
        return result;
    }

    @GetMapping("/character-encoding")
    public Map<String, String> getCharacterEncoding(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        result.put("characterEncoding", request.getCharacterEncoding());
        result.put("contentType", request.getContentType());
        result.put("protocol", request.getProtocol());
        result.put("dispatcherType", request.getDispatcherType().name());
        return result;
    }

    @PostMapping("/content-type")
    public Map<String, String> setContentType(@RequestBody Map<String, String> body,
                                              HttpServletRequest request, HttpServletResponse response) {
        response.setContentType(body.get("contentType"));
        Map<String, String> result = new HashMap<>();
        result.put("contentType", response.getContentType());
        result.put("characterEncoding", response.getCharacterEncoding());
        return result;
    }

    @GetMapping("/auth-type")
    public Map<String, String> getAuthType(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        result.put("authType", request.getAuthType());
        result.put("remoteUser", request.getRemoteUser());
        result.put("userPrincipal", String.valueOf(request.getUserPrincipal()));
        return result;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestParam String username, @RequestParam String password,
                                     HttpServletRequest request) throws jakarta.servlet.ServletException {
        request.login(username, password);
        Map<String, String> result = new HashMap<>();
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        result.put("sessionId", session != null ? session.getId() : null);
        result.put("remoteUser", request.getRemoteUser());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) throws jakarta.servlet.ServletException {
        request.logout();
        Map<String, String> result = new HashMap<>();
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        result.put("sessionId", session != null ? session.getId() : null);
        result.put("remoteUser", request.getRemoteUser());
        return result;
    }

    @GetMapping("/session")
    public Map<String, String> getSession(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(true);
        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("creationTime", String.valueOf(session.getCreationTime()));
        result.put("maxInactiveInterval", String.valueOf(session.getMaxInactiveInterval()));
        result.put("new", String.valueOf(session.isNew()));
        return result;
    }

    @GetMapping("/do-forward")
    public void doForward(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request.getRequestDispatcher("/servlet-bridge/forward-target").forward(request, response);
    }

    @GetMapping("/forward-target")
    public String forwardTarget() {
        return "forwarded";
    }

    @GetMapping("/do-include")
    public void doInclude(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request.getRequestDispatcher("/servlet-bridge/include-target").include(request, response);
        response.getWriter().write("+after-include");
    }

    @GetMapping("/include-target")
    public String includeTarget() {
        return "included";
    }

    @PostMapping("/parts")
    public Map<String, Object> getParts(HttpServletRequest request) throws Exception {
        Map<String, Object> result = new HashMap<>();
        java.util.Collection<jakarta.servlet.http.Part> parts = request.getParts();
        result.put("count", parts.size());
        java.util.List<String> partNames = new java.util.ArrayList<>();
        for (jakarta.servlet.http.Part part : parts) {
            partNames.add(part.getName());
        }
        result.put("names", partNames);
        return result;
    }
}