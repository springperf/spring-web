package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * 接口声明 @RequestPart 参数注解，实现类不重复。
 * CGLIB 代理场景下，createMethodParameters 需通过 findAnnotatedMethod
 * 从接口方法解析 @RequestPart 注解，使 MultipartFileResolverProvider 能正确匹配。
 */
public interface RequestPartApi {

    String uploadFile(@RequestPart("file") MultipartFile file);
}