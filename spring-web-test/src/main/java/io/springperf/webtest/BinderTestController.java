package io.springperf.webtest;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.beans.PropertyEditorSupport;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/binder")
public class BinderTestController {

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, "value", new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue("edited:" + text);
            }
        });
    }

    @GetMapping("/test")
    public Map<String, Object> binderTest(@ModelAttribute BinderDto dto) {
        Map<String, Object> m = new HashMap<>();
        m.put("value", dto.getValue());
        return m;
    }

    public static class BinderDto {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
