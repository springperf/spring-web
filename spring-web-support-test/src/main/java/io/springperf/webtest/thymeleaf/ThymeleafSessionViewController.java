package io.springperf.webtest.thymeleaf;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * E2E：Servlet 场景下 Thymeleaf 模板读取真实 session。
 *
 * <p>验证链路：{@code ServletWebExchangeProvider} → {@code ServletWebExchange}（桥接
 * {@code HttpSession}）→ {@code ThymeleafWebContext} 把会话属性注入模板上下文变量。</p>
 *
 * <p>模板不依赖容器专有的 {@code #session} 表达式对象，因此 master 基线（Thymeleaf 3.1
 * 已移除该对象）与本分支（3.0）的模板写法一致。</p>
 */
@Controller
public class ThymeleafSessionViewController {

    /** 写入 session 后渲染模板，模板内以普通变量读取 user / count。 */
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
