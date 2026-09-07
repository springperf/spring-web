package io.springperf.webtest.session;

import javax.servlet.http.HttpServletRequest;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 楠岃瘉 {@code @SessionAttributes}锛堝鏁帮級锛氱被绾у０鏄庡睘鎬у湪璇锋眰闂翠笌 session 鍙屽悜鍚屾銆?
 * <p>鍒嗘琛ㄥ崟璇箟锛歿@code step1} 褰曞叆閮ㄥ垎鏁版嵁 鈫?session 淇濆瓨锛泏@code step2} 浠?session
 * 鎭㈠瀵硅薄锛坽@code @ModelAttribute} 鍙傛暟澶嶇敤鍚屼竴瀹炰緥锛屼笉閲嶅缓锛夛紱{@code complete} 璋?
 * {@link SessionStatus#setComplete()} 娓呯悊 session銆?/p>
 */
@RestController
@RequestMapping("/session-attrs")
@SessionAttributes("wizard")
public class SessionAttributesTestController {

    @ModelAttribute
    public void initWizard(ModelMap model) {
        if (!model.containsKey("wizard")) {
            model.addAttribute("wizard", new Wizard());
        }
    }

    @PostMapping("/step1")
    public Map<String, Object> step1(@ModelAttribute("wizard") Wizard wizard,
                                     @RequestParam String name,
                                     HttpServletRequest request) {
        wizard.setName(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", wizard.getName());
        result.put("sessionId", request.getSession(true).getId());
        return result;
    }

    @GetMapping("/step2")
    public Map<String, Object> step2(@ModelAttribute("wizard") Wizard wizard,
                                     HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", wizard.getName());
        javax.servlet.http.HttpSession session = request.getSession(false);
        result.put("sessionId", session != null ? session.getId() : null);
        return result;
    }

    @PostMapping("/complete")
    public Map<String, Object> complete(SessionStatus sessionStatus,
                                        HttpServletRequest request) {
        sessionStatus.setComplete();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complete", true);
        javax.servlet.http.HttpSession session = request.getSession(false);
        result.put("sessionId", session != null ? session.getId() : null);
        return result;
    }

    /** 鍒嗘琛ㄥ崟鏁版嵁瀵硅薄锛堟棤 ID 璇箟锛屼粎婕旂ず session 鎭㈠锛夈€?*/
    public static class Wizard {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}