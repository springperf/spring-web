package io.springperf.web.view.retval;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.ModelSupport;
import io.springperf.web.view.RedirectView;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolverRegistry;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class ViewReturnValueResolver extends BaseWebComponent implements ReturnValueResolver {

    private static final MappingCacheKey<Boolean> VIEW_NAME_KEY =
            MappingCacheKey.createMethodCacheKey(Boolean.class);

    private ViewResolverRegistry viewResolverRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        viewResolverRegistry = webContext.getWebComponentWithDefault(
                ViewResolverRegistry.class, new ViewResolverRegistry());
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            return;
        }
        List<String> unresolvedViewMethods = new ArrayList<>();
        for (PathMappingContext mapping : mappingRegistry.getMappingContextList()) {
            if (mapping.getMethod().getReturnType() != String.class) {
                continue;
            }
            if (hasResponseBody(mapping.getMethod(), mapping.getUserClass())) {
                continue;
            }
            mapping.set(VIEW_NAME_KEY, Boolean.TRUE);
            if (!viewResolverRegistry.hasViewResolvers()) {
                unresolvedViewMethods.add(mapping.getUserClass().getSimpleName()
                        + "#" + mapping.getMethod().getName());
            }
        }
        if (!unresolvedViewMethods.isEmpty() && !viewResolverRegistry.hasViewResolvers()) {
            throw new IllegalStateException(
                    "View methods detected but no ViewResolver registered. "
                    + "Add a template engine dependency (thymeleaf/freemarker) to the project, "
                    + "or set spring.web.view.engine=none if only redirect: is used. "
                    + "Affected methods: " + unresolvedViewMethods);
        }
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return Boolean.TRUE.equals(mappingContext.get(VIEW_NAME_KEY));
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (!(returnValue instanceof String)) {
            return false;
        }
        String viewName = (String) returnValue;
        if (viewName.startsWith("redirect:")) {
            return true;
        }
        PathMappingContext ctx = PathMappingContext.get(req);
        return ctx != null && Boolean.TRUE.equals(ctx.get(VIEW_NAME_KEY));
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                   WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        String viewName = (String) returnValue;
        View view = resolveView(viewName, req, resp);
        if (view == null) {
            return;
        }
        String contentType = view.getContentType();
        if (contentType != null) {
            resp.getHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
        } else {
            Charset charset = resp.getCharacterEncoding();
            if (charset == null) {
                charset = Charset.forName("UTF-8");
            }
            resp.getHeaders().set(HttpHeaders.CONTENT_TYPE, "text/html;charset=" + charset.name());
        }
        resp.setHandled();
        view.render(ModelSupport.getOrCreate(req), req, resp);
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 200;
    }

    private View resolveView(String viewName, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (viewName.startsWith("redirect:")) {
            return new RedirectView(viewName.substring(9));
        }
        return viewResolverRegistry.resolve(viewName, req);
    }

    private static boolean hasResponseBody(java.lang.reflect.Method method, Class<?> clazz) {
        return AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(clazz, ResponseBody.class);
    }
}