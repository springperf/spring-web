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

    /** 璁剧疆 session 灞炴€э紝渚?{@code @SessionAttribute}锛堝崟鏁帮級璇诲彇銆?*/
    @GetMapping("/put")
    public Map<String, Object> put(@RequestParam String name, @RequestParam String value,
                                   HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        session.setAttribute(name, value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        return result;
    }

    /** 浠?session 璇诲彇鍗曞€煎睘鎬э紙required=true 榛樿锛夈€?*/
    @GetMapping("/get")
    public Map<String, Object> get(@SessionAttribute("greeting") String greeting,
                                   HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("greeting", greeting);
        result.put("sessionId", request.getSession(false).getId());
        return result;
    }

    /** required=false锛宻ession 鏃犲睘鎬ф椂杩斿洖 null 鑰岄潪鎶ラ敊銆?*/
    @GetMapping("/get-optional")
    public Map<String, Object> getOptional(
            @SessionAttribute(name = "missing", required = false) String missing) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("missing", missing);
        return result;
    }

    /** @SessionScope bean 璺ㄨ姹傚湪 session 鍐呭叡浜鏁般€?*/
    @GetMapping("/counter")
    public Map<String, Object> counter(HttpServletRequest request) {
        int value = counter.increment();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", value);
        result.put("sessionId", request.getSession(true).getId());
        return result;
    }
}
