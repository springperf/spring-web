package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.ModelAttribute;

public interface InitBinderApi {

    String createWithBinder(@ModelAttribute("form") InitBinderForm form);
}
