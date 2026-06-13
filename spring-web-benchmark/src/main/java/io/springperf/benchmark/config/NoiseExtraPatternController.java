package io.springperf.benchmark.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NoiseExtraPatternController {

    @GetMapping("/noise/pat/6/**")
    public void nep01() {}

    @GetMapping("/noise/pat/7/**")
    public void nep02() {}

    @GetMapping("/noise/pat/8/**")
    public void nep03() {}

    @GetMapping("/noise/pat/9/**")
    public void nep04() {}

    @GetMapping("/noise/pat/10/**")
    public void nep05() {}

    @GetMapping("/noise/pat/11/**")
    public void nep06() {}

    @GetMapping("/noise/pat/12/**")
    public void nep07() {}

    @GetMapping("/noise/pat/13/**")
    public void nep08() {}

    @GetMapping("/noise/pat/14/**")
    public void nep09() {}

    @GetMapping("/noise/pat/15/**")
    public void nep10() {}

    @GetMapping("/noise/pat/16/**")
    public void nep11() {}

    @GetMapping("/noise/pat/17/**")
    public void nep12() {}

    @GetMapping("/noise/pat/18/**")
    public void nep13() {}

    @GetMapping("/noise/pat/19/**")
    public void nep14() {}

    @GetMapping("/noise/pat/20/**")
    public void nep15() {}
}