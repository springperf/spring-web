package io.springperf.webtest.bridge;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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

    @GetMapping("/mime-type")
    public Map<String, String> getMimeType(@RequestParam String file, HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        result.put("mimeType", request.getServletContext().getMimeType(file));
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

    @GetMapping("/session")
    public Map<String, String> getSession(HttpServletRequest request) {
        javax.servlet.http.HttpSession session = request.getSession(true);
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
        java.util.Collection<javax.servlet.http.Part> parts = request.getParts();
        result.put("count", parts.size());
        java.util.List<String> partNames = new java.util.ArrayList<>();
        for (javax.servlet.http.Part part : parts) {
            partNames.add(part.getName());
        }
        result.put("names", partNames);
        return result;
    }
}