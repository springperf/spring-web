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
 * 验证 {@code @SessionAttributes}（复数）：类级声明属性在请求间与 session 双向同步。
 * <p>分步表单语义：{@code step1} 录入部分数据 → session 保存；{@code step2} 从 session
 * 恢复对象（{@code @ModelAttribute} 参数复用同一实例，不重建）；{@code complete} 调
 * {@link SessionStatus#setComplete()} 清理 session。</p>
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

    /** 分步表单数据对象（无 ID 语义，仅演示 session 恢复）。 */
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