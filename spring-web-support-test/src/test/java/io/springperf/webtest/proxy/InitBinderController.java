package io.springperf.webtest.proxy;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.beans.PropertyEditorSupport;

/**
 * 实现 InitBinderApi 接口，通过 @InitBinder 对 @ModelAttribute 字段进行转换。
 * <p>
 * CGLIB 代理场景下验证：@InitBinder 方法在代理类上仍能被正确发现并执行。
 */
@RestController
@RequestMapping("/proxy-api")
public class InitBinderController implements InitBinderApi {

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, "name", new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue("binder:" + text);
            }
        });
    }

    @PostMapping("/init-binder")
    @Override
    public String createWithBinder(InitBinderForm form) {
        return form.getName();
    }
}
