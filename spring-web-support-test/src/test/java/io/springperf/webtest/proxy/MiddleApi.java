package io.springperf.webtest.proxy;

import org.springframework.web.bind.annotation.RequestParam;

/**
 * 中间接口：继承 RootApi，新增带 @RequestParam 的方法。
 * 验证 findAnnotatedMethod 遍历两层接口层级。
 */
public interface MiddleApi extends RootApi {

    String middleQuery(@RequestParam("name") String name);
}