package io.springperf.web.view;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class ViewResolverRegistry extends WebComponentContainer {

    private final List<ViewResolver> resolvers = new ArrayList<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        registerWebComponent(ViewResolver.class);
    }

    @Override
    public void initComponentPhase1() throws Exception {
        super.initComponentPhase1();
        initRealComponentList(resolvers, ViewResolver.class);
    }

    @Override
    public void initComponentPhase3() throws Exception {
        super.initComponentPhase3();
        if (!hasViewResolvers()) {
            return;
        }
        if (!webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)) {
            return;
        }
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            return;
        }
        List<PathMappingContext> mappings = mappingRegistry.getMappingContextList();
        List<String> viewMethods = new ArrayList<>();
        for (PathMappingContext mapping : mappings) {
            if (mapping.getMethod().getReturnType() == String.class
                    && !hasResponseBody(mapping.getMethod(), mapping.getUserClass())) {
                viewMethods.add(mapping.getUserClass().getSimpleName() + "#" + mapping.getMethod().getName());
            }
        }
        if (!viewMethods.isEmpty()) {
            log.info("View methods detected ({}): {}", viewMethods.size(), viewMethods);
        }
    }

    /**
     * 是否存在已启用的视图解析器。禁用（{@code spring.*.enabled=false}）的解析器不计入，
     * 以便启动期 fail-fast 仍能发现「引入了视图模块但没有任何可用引擎」。
     */
    public boolean hasViewResolvers() {
        for (ViewResolver resolver : getWebComponents(ViewResolver.class)) {
            if (resolver.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    public View resolve(String viewName, WebServerHttpRequest req) {
        if (viewName == null) {
            return null;
        }
        Locale locale = req != null ? req.getLocale() : Locale.getDefault();
        for (ViewResolver resolver : resolvers) {
            try {
                View view = resolver.resolveViewName(viewName, locale, req);
                if (view != null) {
                    return view;
                }
            } catch (Exception ex) {
                // 保留根因：模板语法错误/初始化失败不能被后续 resolver 的 null 掩盖成泛化 500
                throw new IllegalStateException("ViewResolver " + resolver.getClass().getName()
                        + " failed to resolve view '" + viewName + "'", ex);
            }
        }
        return null;
    }

    /**
     * 使所有已注册解析器的解析结果与模板引擎内部缓存失效，用于运行期新增/替换模板后主动刷新。
     * <p>
     * 取实时组件列表而非 Phase1 快照，运行期注册的 resolver 也能被覆盖。配置刷新不会重建模板引擎
     * （{@code spring.thymeleaf.*}、{@code spring.freemarker.*}、{@code spring.beetl.*} 属启动期固化配置），
     * 本方法只清缓存、不重建引擎。
     * </p>
     */
    public void invalidateViewCaches() {
        for (ViewResolver resolver : getWebComponents(ViewResolver.class)) {
            resolver.invalidateCache();
        }
    }

    public static boolean hasResponseBody(java.lang.reflect.Method method, Class<?> clazz) {
        return AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(clazz, ResponseBody.class);
    }
}
