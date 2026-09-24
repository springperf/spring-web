package io.springperf.web.support.mvc.config;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.RedirectView;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolverRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

/**
 * 视图控制器的 {@link CustomInvoker}：把 {@code addViewController}/
 * {@code addRedirectViewController}/{@code addStatusController} 注册项路由为三种行为——
 * <ul>
 * <li><b>viewName</b>：经 {@link ViewResolverRegistry} 解析并渲染（缺 resolver 时 500，与 视图方法返回不可解析视图名一致）</li>
 * <li><b>redirect</b>：{@link RedirectView}（302 + Location，自动拼 context-path 与 model query）</li>
 * <li><b>status</b>：仅设置状态码</li>
 * </ul>
 * viewName 与 status 可组合（先设状态再渲染，如 200 + 指定视图）。
 */
public class ViewControllerInvoker implements CustomInvoker {

    public static final Method HANDLE_METHOD = ReflectionUtils.findMethod(ViewControllerInvoker.class, "handle",
            WebServerHttpRequest.class, WebServerHttpResponse.class);

    private final String viewName;
    private final HttpStatus statusCode;
    private final WebContext webContext;
    private volatile ViewResolverRegistry viewResolverRegistry;

    ViewControllerInvoker(WebContext webContext, String viewName, HttpStatus statusCode) {
        Assert.isTrue(viewName != null || statusCode != null, "ViewController requires a viewName or a statusCode");
        this.webContext = webContext;
        this.viewName = viewName;
        this.statusCode = statusCode;
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        WebServerHttpRequest req = (WebServerHttpRequest) args[0];
        WebServerHttpResponse resp = (WebServerHttpResponse) args[1];
        handle(req, resp);
        return null;
    }

    void handle(WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        if (statusCode != null) {
            resp.setStatusCode(statusCode);
        }
        if (viewName == null) {
            // 纯 status 控制器
            resp.setHandled();
            return;
        }
        View view = resolveView(viewName, req, resp);
        if (view == null) {
            // 与视图方法返回不可解析视图名一致：抛异常交 ExceptionRegistry（500，含上下文）
            throw new IllegalArgumentException("Unable to resolve view name '" + viewName
                    + "' with any registered ViewResolver (thymeleaf/freemarker/beetl/jsp)");
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
        view.render(ModelContext.getOrCreate(req), req, resp);
    }

    private View resolveView(String name, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (name.startsWith("redirect:")) {
            return new RedirectView(name.substring("redirect:".length()));
        }
        ViewResolverRegistry registry = this.viewResolverRegistry;
        if (registry == null) {
            // getWebComponentWithDefault 缺失时注册并返回（幂等），与 ThymeleafViewResolver 同模式
            registry = webContext.getWebComponentWithDefault(ViewResolverRegistry.class, new ViewResolverRegistry());
            this.viewResolverRegistry = registry;
        }
        return registry.resolve(name, req);
    }

    @Override
    public Method getHandleMethod() {
        return HANDLE_METHOD;
    }

    /** 视图控制器仅响应 GET（HEAD 由 HttpMethodMatcher 自动映射，对齐 ResourceRequestHandler）。 */
    @Override
    public List<Matcher> getMatchers() {
        return Arrays.asList(new HttpMethodMatcher(new HttpMethod[] { HttpMethod.GET }));
    }

    @Override
    public String getType() {
        return "ViewController";
    }

    /** 供测试：当前关联的 ViewResolverRegistry。 */
    ViewResolverRegistry getViewResolverRegistryForTest() {
        return viewResolverRegistry;
    }
}
