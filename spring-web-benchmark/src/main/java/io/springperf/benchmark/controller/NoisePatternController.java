package io.springperf.benchmark.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NoisePatternController {

    @GetMapping("/noise/pat/1/**")
    public void np01() {}

    @GetMapping("/noise/pat/2/**")
    public void np02() {}

    @GetMapping("/noise/pat/3/**")
    public void np03() {}

    @GetMapping("/noise/pat/4/**")
    public void np04() {}

    @GetMapping("/noise/pat/5/**")
    public void np05() {}
}
