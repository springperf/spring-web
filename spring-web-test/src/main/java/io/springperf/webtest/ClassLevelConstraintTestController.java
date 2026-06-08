package io.springperf.webtest;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(value = "/class-level-constraint", method = RequestMethod.POST)
public class ClassLevelConstraintTestController {

    @RequestMapping("/echo")
    public Map<String, Object> echo() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        return result;
    }
}