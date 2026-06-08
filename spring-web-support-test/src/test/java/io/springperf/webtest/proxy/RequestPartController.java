package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 实现 RequestPartApi 接口，方法上有 @PostMapping 路由注解，
 * 但参数上无 @RequestPart —— 该注解在接口 RequestPartApi 上。
 * <p>
 * 配合 @EnableAspectJAutoProxy(proxyTargetClass = true)，
 * Spring 会为此 Controller 创建 CGLIB 代理，验证框架的代理类 @RequestPart 解析能力。
 */
@RestController
@RequestMapping("/proxy-api")
public class RequestPartController implements RequestPartApi {

    @PostMapping("/upload")
    @Override
    public String uploadFile(MultipartFile file) {
        return file.getOriginalFilename() + "-" + file.getSize();
    }
}