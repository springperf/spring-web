package io.springperf.webtest;

import javax.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/view")
public class ViewController {

    @ModelAttribute("localKey")
    public String localAttribute() {
        return "localValue";
    }

    @GetMapping("/hello")
    public String hello(@RequestParam(value = "name", defaultValue = "World") String name, Model model) {
        model.addAttribute("name", name);
        model.addAttribute("message", "Hello " + name + "!");
        return "hello";
    }

    @GetMapping("/redirect")
    public String redirect() {
        return "redirect:hello?name=redirected";
    }

    @GetMapping("/mav")
    public ModelAndView modelAndView(@RequestParam(value = "name", defaultValue = "MAV") String name) {
        return new ModelAndView("hello")
                .addObject("name", name)
                .addObject("message", "Hello from ModelAndView " + name + "!");
    }

    @GetMapping("/model-attr")
    public String modelAttribute(@ModelAttribute("form") UserForm form, Model model) {
        model.addAttribute("message", "bound " + form.getName() + "/" + form.getAge());
        return "hello";
    }

    @GetMapping("/advice")
    public String advice(Model model) {
        model.addAttribute("name", "advice");
        model.addAttribute("message", "app=" + model.getAttribute("appName")
                + ", title=" + model.getAttribute("globalTitle"));
        return "hello";
    }

    @GetMapping("/hello-beetl")
    public String helloBeetl(@RequestParam(value = "name", defaultValue = "Beetl") String name, Model model) {
        model.addAttribute("name", name);
        model.addAttribute("message", "Hello from Beetl " + name + "!");
        return "hello-beetl";
    }

    @GetMapping("/hello-ftl")
    public String helloFtl(@RequestParam(value = "name", defaultValue = "Ftl") String name, Model model) {
        model.addAttribute("name", name);
        model.addAttribute("message", "Hello from FreeMarker " + name + "!");
        return "hello-ftl";
    }

    @GetMapping("/hello-path/{name}")
    public String helloPath(Model model) {
        model.addAttribute("message", "path variable injected");
        return "hello-path";
    }

    @GetMapping("/binding")
    public String binding(@ModelAttribute("validForm") @Valid ValidatedForm form,
                          BindingResult bindingResult, Model model) {
        model.addAttribute("hasErrors", bindingResult.hasErrors());
        return "binding";
    }

    @GetMapping("/local")
    public String local(Model model) {
        model.addAttribute("name", "local");
        model.addAttribute("message", "localKey=" + model.getAttribute("localKey"));
        return "hello";
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("boom");
    }

    @ExceptionHandler(IllegalStateException.class)
    public String handleBoom(IllegalStateException ex, Model model) {
        model.addAttribute("name", "error");
        model.addAttribute("message", "handled: " + ex.getMessage());
        return "hello";
    }
}