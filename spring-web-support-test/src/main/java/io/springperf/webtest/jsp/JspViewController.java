package io.springperf.webtest.jsp;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Controller
public class JspViewController {

    @GetMapping("/jsp-view")
    public String jspView(Model model) {
        model.addAttribute("name", "spring-perf");
        return "jsp:hello";
    }

    @GetMapping("/jsp-view-suffix")
    public String jspViewSuffix() {
        return "hello.jsp";
    }

    @GetMapping("/jsp-model")
    public String jspModel(Model model) {
        Map<String, String> map = new HashMap<>();
        map.put("name", "map-value");
        model.addAttribute("map", map);
        model.addAttribute("list", Arrays.asList("alpha", "beta"));
        model.addAttribute("user", new UserDto("zhangsan"));
        return "jsp:model";
    }

    @GetMapping("/jsp-jstl")
    public String jspJstl(Model model) {
        model.addAttribute("items", Arrays.asList("a", "b", "c"));
        model.addAttribute("flag", true);
        return "jsp:jstl";
    }
}
