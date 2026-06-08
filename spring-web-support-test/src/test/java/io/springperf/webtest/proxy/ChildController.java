package io.springperf.webtest.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 继承 ParentController，验证 CGLIB 代理 + 类继承场景下路由注解能正常解析。
 * <p>
 * 类级别的 {@code @RequestMapping("/proxy-parent")} 从 ParentController 继承，
 * 通过 AnnotatedElementUtils.findMergedAnnotation 可遍历到父类。
 * method getDeclaredMethods 只返回本类声明的方法，因此子类新增的路由方法能正确注册，
 * 父类的路由方法通过父类自己的 bean 注册。
 */
@RestController
public class ChildController extends ParentController {

    /**
     * 子类新增端点：验证类级 @RequestMapping 从父类继承，方法级路由注册正确。
     */
    @GetMapping("/status")
    public String status() {
        return "child-ok";
    }

    // ============ Filter 阻断测试端点 ============

    @GetMapping("/blocked")
    public String blocked() {
        return "should-not-reach";
    }

    // ============ @ExceptionHandler 父子异常路由 ============

    @GetMapping("/parent-exception")
    public String parentException() {
        throw new RuntimeException("parent error from proxy");
    }

    @GetMapping("/child-exception")
    public String childException() {
        throw new IllegalArgumentException("child error from proxy");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleChild(IllegalArgumentException e) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", e.getMessage());
        m.put("handler", "child");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleParent(RuntimeException e) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", e.getMessage());
        m.put("handler", "parent");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(m);
    }
}