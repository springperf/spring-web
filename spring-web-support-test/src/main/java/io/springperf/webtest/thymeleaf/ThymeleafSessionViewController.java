package io.springperf.webtest.thymeleaf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * E2E：Servlet 场景下 Thymeleaf 模板读取真实 session / principal。
 *
 * <p>验证 {@code ServletWebExchangeProvider} 提供的 {@code IWebExchange} 使
 * Thymeleaf 的 {@code #session} 表达式对象可用（原生场景下为 null）。</p>
 */
@Controller
public class ThymeleafSessionViewController {

    /** 写入 session 后渲染模板，模板内通过 #session 读取。 */
    @GetMapping("/thymeleaf-session")
    public String thymeleafSession(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(true);
        session.setAttribute("user", "alice");
        Integer count = (Integer) session.getAttribute("count");
        session.setAttribute("count", count == null ? 1 : count + 1);
        model.addAttribute("msg", "hello-thymeleaf");
        return "session-view";
    }
}
