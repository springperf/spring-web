package io.springperf.webtest.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.lang.reflect.Method;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 把「写在文档里的架构承诺」变成可执行断言。
 * <p>
 * 这里只放**其它门禁覆盖不到**的约束：Maven 依赖图能保证的（如 spring-web 模块没有 servlet 依赖）不重复写， 写了也只是装饰；真正会随时间腐化的是包级方向与热点路径的实现方式。
 * </p>
 * <p>
 * 每条规则都对应文档中的明确表述，失败时应改代码而不是放宽规则——除非同时更新文档并说明理由。
 * </p>
 */
@AnalyzeClasses(packages = "io.springperf.web", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /**
     * core 不依赖 Servlet API。{@code DispatcherHandler} 的注释写明了这条：core 无法构造 Spring 的 servlet 侧异常，统一用
     * {@code ResponseStatusException} 承载状态码。依赖一旦引入，core 就无法脱离容器测试。
     */
    @ArchTest
    static final ArchRule core_does_not_depend_on_servlet_api = noClasses().that()
            .resideInAPackage("io.springperf.web.core..").should().dependOnClassesThat()
            .resideInAPackage("jakarta.servlet..").because("core 刻意与 Servlet 容器解耦，异常与请求模型需能脱离容器使用");

    /**
     * core 不依赖 SpringMVC 的 servlet 栈。本项目的定位是替换 SpringMVC；core 若反向依赖 {@code org.springframework.web.servlet}，则「移除
     * SpringMVC 依赖即可替换」的承诺不成立。
     */
    @ArchTest
    static final ArchRule core_does_not_depend_on_spring_mvc = noClasses().that()
            .resideInAPackage("io.springperf.web.core..").should().dependOnClassesThat()
            .resideInAPackage("org.springframework.web.servlet..").because("框架替代 SpringMVC，core 不能反向依赖被替代的实现");

    /**
     * 每请求热点路径不做反射调用。项目承诺「运行时零反射」：路由匹配、参数解析、返回值解析所需的 {@code Method} 调用都在启动期由 invoker 固化（见 19 篇 ADR）。这三处是性能基线所在， 一旦有人回退到
     * {@code Method.invoke}，基线会悄悄劣化且功能测试全绿。
     */
    @ArchTest
    static final ArchRule hot_path_has_no_reflective_invocation = noClasses().that()
            .resideInAnyPackage("io.springperf.web.core.mapping..", "io.springperf.web.core.arg.resolver..",
                    "io.springperf.web.core.retval..")
            .should().callMethod(Method.class, "invoke").because("热点路径的方法调用必须在启动期固化，不能在请求线程里反射");

    /**
     * util 是零业务依赖的工具层。反向依赖 core/server/context 会形成 {@code util → core → util} 的环， 也会让工具类无法在纯单元测试里使用。
     */
    @ArchTest
    static final ArchRule util_does_not_depend_on_core = noClasses().that().resideInAPackage("io.springperf.web.util..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("io.springperf.web.core..", "io.springperf.web.server..", "io.springperf.web.context..")
            .because("工具层反向依赖业务层会形成环，且削弱可测试性");
}
