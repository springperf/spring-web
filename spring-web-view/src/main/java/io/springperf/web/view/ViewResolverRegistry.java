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

    public boolean hasViewResolvers() {
        return !getWebComponents(ViewResolver.class).isEmpty();
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
                // 不吞异常：resolver 初始化失败/解析失败是配置或模板问题，必须可见，避免产出空白 200
                log.warn("ViewResolver {} failed to resolve view '{}'", resolver.getClass().getName(), viewName, ex);
            }
        }
        return null;
    }

    static boolean hasResponseBody(java.lang.reflect.Method method, Class<?> clazz) {
        return AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(clazz, ResponseBody.class);
    }
}