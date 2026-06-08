package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三级类继承链：ParentController → ChildController → GrandchildController。
 * <p>
 * 验证 {@code @RequestMapping("/proxy-parent")} 通过
 * {@code AnnotatedElementUtils.findMergedAnnotation} 在三级继承链上都能正确继承。
 * 框架通过 {@code getDeclaredMethods()} 扫描，GrandchildController 新增的路由方法
 * 能正确注册为 /proxy-parent/grandchild-status。
 */
@RestController
public class GrandchildController extends ChildController {

    @GetMapping("/grandchild-status")
    public String grandchildStatus() {
        return "grandchild-ok";
    }
}