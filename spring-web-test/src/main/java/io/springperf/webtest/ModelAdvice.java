package io.springperf.webtest;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class ModelAdvice {

    @ModelAttribute("appName")
    public String appName() {
        return "SpringPerfWeb";
    }

    @ModelAttribute
    public void globalAttributes(Model model) {
        model.addAttribute("globalTitle", "Perf View Test");
    }
}