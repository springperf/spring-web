package io.springperf.web.view.retval;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.RedirectView;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolverRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ViewReturnValueResolver extends BaseWebComponent implements ReturnValueResolver {

    private static final MappingCacheKey<Boolean> VIEW_NAME_KEY = MappingCacheKey.createMethodCacheKey(Boolean.class);

    private ViewResolverRegistry viewResolverRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        viewResolverRegistry = webContext.getWebComponentWithDefault(ViewResolverRegistry.class,
                new ViewResolverRegistry());
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            return;
        }
        // 注册表查询会分配并排序列表，启动期只取一次
        boolean hasResolvers = viewResolverRegistry.hasViewResolvers();
        List<String> unresolvedViewMethods = new ArrayList<>();
        for (PathMappingContext mapping : mappingRegistry.getMappingContextList()) {
            if (mapping.getMethod().getReturnType() != String.class) {
                continue;
            }
            if (ViewResolverRegistry.hasResponseBody(mapping.getMethod(), mapping.getUserClass())) {
                continue;
            }
            mapping.set(VIEW_NAME_KEY, Boolean.TRUE);
            if (!hasResolvers) {
                unresolvedViewMethods.add(mapping.getUserClass().getSimpleName() + "#" + mapping.getMethod().getName());
            }
        }
        if (!unresolvedViewMethods.isEmpty() && !hasResolvers) {
            // 显式 engine=none：仅 redirect: 场景（无需模板引擎），跳过 fail-fast
            if (isViewEngineDisabled()) {
                log.warn(
                        "spring.web.view.engine=none configured, but {} view-name method(s) "
                                + "exist and will only support redirect: view names: {}",
                        unresolvedViewMethods.size(), unresolvedViewMethods);
            } else {
                throw new IllegalStateException("View methods detected but no ViewResolver registered. "
                        + "Add a template engine dependency (thymeleaf/freemarker) to the project, "
                        + "or set spring.web.view.engine=none if only redirect: is used. " + "Affected methods: "
                        + unresolvedViewMethods);
            }
        }
    }

    /**
     * 是否显式禁用了全部模板引擎（{@code spring.web.view.engine} 配置含 {@code none}）。
     * <p>
     * 此时仅支持 {@code redirect:} 视图名，无模板引擎时不应触发 fail-fast。
     * </p>
     */
    private boolean isViewEngineDisabled() {
        String engine = webContext.getProps().get(ViewProperties.ENGINE, ViewProperties.ENGINE_DEFAULT);
        if (engine == null || engine.trim().isEmpty()) {
            return false;
        }
        for (String item : engine.split(",")) {
            if (item.trim().equalsIgnoreCase("none")) {
                return true;
            }
        }
        return false;
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
        PathMappingContext ctx = PathMappingContext.get(req);
        // 包括 redirect: —— @RestController/@ResponseBody 方法返回 "redirect:/x" 字符串时
        // 应作为 JSON 文本输出，而非被劫持成 302（Spring MVC 行为）
        return ctx != null && Boolean.TRUE.equals(ctx.get(VIEW_NAME_KEY));
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req,
            WebServerHttpResponse resp) throws Exception {
        String viewName = (String) returnValue;
        View view = resolveView(viewName, req, resp);
        if (view == null) {
            // 视图名无法解析：抛异常交 ExceptionRegistry → 500（含错误上下文），
            // 而非静默返回 200 + 空白 body
            throw new IllegalArgumentException("Unable to resolve view name '" + viewName
                    + "' with any registered ViewResolver (thymeleaf/freemarker/beetl/jsp)");
        }
        applyContentType(view, resp);
        resp.setHandled();
        view.render(ModelContext.getOrCreate(req), req, resp);
    }

    /**
     * 视图响应的 Content-Type 与响应编码由这里统一决定：View 提供的 Content-Type 原样保留，
     * 仅当其中自带 charset 时同步到响应编码（保证 Writer 与响应头一致）；未提供则回退
     * {@code text/html} + 响应编码。重定向无响应体，不设置 Content-Type。
     * <p>
     * 供 {@code ModelAndViewReturnValueResolver} 复用，避免两条链路各写一套导致头与字节编码错配。
     * </p>
     */
    public static void applyContentType(View view, WebServerHttpResponse resp) {
        HttpHeaders headers = resp.getHeaders();
        if (view instanceof RedirectView) {
            headers.remove(HttpHeaders.CONTENT_TYPE);
            return;
        }
        String contentType = view.getContentType();
        if (contentType == null || contentType.isEmpty()) {
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding()
                    : StandardCharsets.UTF_8;
            contentType = "text/html;charset=" + charset.name();
        } else {
            // 自定义 Content-Type 原样保留，不擅自追加 charset；自带 charset 时才同步响应编码
            Charset charset = MediaType.parseMediaType(contentType).getCharset();
            if (charset != null) {
                resp.setCharacterEncoding(charset);
            }
        }
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
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
}
