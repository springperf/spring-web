package io.springperf.benchmark.controller;

import io.springperf.benchmark.dto.UserReq;
import io.springperf.benchmark.dto.UserResp;
import io.springperf.web.annotation.Optimize;
import javax.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * perf profile 专属 Controller：类级 {@code @Optimize}，让全部 benchmark 端点启用激进优化
 * （writeBody 方法级内容协商缓存 + FastInvoker 字节码调用）。
 * <p>
 * 本框架 MappingRegistry 用 {@code targetClass.getDeclaredMethods()} 扫描映射（不含继承方法），
 * 故需在此重声明全部 {@code @RequestMapping} 方法并委托父类，否则父类被排除扫描后其余端点 404。
 * 共享 {@link BenchmarkController} 供 tomcat/undertow（标准 Spring MVC，无 @Optimize）编译使用，
 * PerfApplication 通过 excludeFilters 排除它，避免同路径双映射歧义。
 * <p>
 * 必须直接标注 {@code @RestController}：@Controller/@RestController 非 @Inherited，
 * component scan 需在候选类上直接命中 @Component 元注解才会生成 bean，仅继承父类注解的
 * 子类不会被扫描为 bean（与标准 Spring MVC 的扫描行为一致）。
 */
@RestController
@Optimize
public class PerfBenchmarkController extends BenchmarkController {

    @PostMapping("/demo/echo")
    public ResponseEntity<UserResp> echo(@RequestBody UserReq req) {
        return super.echo(req);
    }

    @GetMapping("/demo/hello/{name}/aaaxxx")
    public UserResp hello(@PathVariable("name") String name,
                          @RequestParam("p1") String p1,
                          @RequestParam("p2") String p2,
                          @RequestParam("p3") String p3,
                          @RequestParam("p4") String p4,
                          @RequestParam("p5") String p5) {
        return super.hello(name, p1, p2, p3, p4, p5);
    }

    @GetMapping("/core/deferred-result")
    public DeferredResult<UserResp> deferredResult() {
        return super.deferredResult();
    }

    @GetMapping("/core/bytes")
    public byte[] bytes() {
        return super.bytes();
    }

    @PostMapping("/core/validate")
    public UserResp validate(@Valid @RequestBody UserReq req) {
        return super.validate(req);
    }

    @GetMapping("/core/large-response")
    public byte[] largeResponse() {
        return super.largeResponse();
    }
}
