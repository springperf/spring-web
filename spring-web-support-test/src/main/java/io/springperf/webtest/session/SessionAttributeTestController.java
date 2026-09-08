package io.springperf.webtest.session;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/session")
public class SessionAttributeTestController {

    private final SessionScopedCounter counter;

    public SessionAttributeTestController(SessionScopedCounter counter) {
        this.counter = counter;
    }

    /** 设置 session 属性，供 {@code @SessionAttribute}（单数）读取。 */
    @GetMapping("/put")
    public Map<String, Object> put(@RequestParam String name, @RequestParam String value,
                                   HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        session.setAttribute(name, value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        return result;
    }

    /** 从 session 读取单值属性（required=true 默认）。 */
    @GetMapping("/get")
    public Map<String, Object> get(@SessionAttribute("greeting") String greeting,
                                   HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("greeting", greeting);
        result.put("sessionId", request.getSession(false).getId());
        return result;
    }

    /** required=false，session 无属性时返回 null 而非报错。 */
    @GetMapping("/get-optional")
    public Map<String, Object> getOptional(
            @SessionAttribute(name = "missing", required = false) String missing) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("missing", missing);
        return result;
    }

    /** @SessionScope bean 跨请求在 session 内共享计数。 */
    @GetMapping("/counter")
    public Map<String, Object> counter(HttpServletRequest request) {
        int value = counter.increment();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", value);
        result.put("sessionId", request.getSession(true).getId());
        return result;
    }
}
