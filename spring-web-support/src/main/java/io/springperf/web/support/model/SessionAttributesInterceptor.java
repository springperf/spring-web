package io.springperf.web.support.model;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.DefaultSessionAttributeStore;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.bind.support.SimpleSessionStatus;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.SessionAttributesHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link HandlerInterceptor} 实现 {@code @SessionAttributes}（复数）：将类级别
 * 声明的 model 属性在请求间与 session 同步。
 * <p>类级匹配通过 {@link ControllerAdvice}（annotations = {@code @SessionAttributes}）
 * 声明，由 {@code InterceptorRegistry} 包装 {@code ControllerAdviceBean} 按 handler 的
 * controller 类型匹配（区别于 path 维度的普通拦截器）。</p>
 * <ul>
 *   <li>{@link #preHandle}：handler 前从 session 恢复声明属性到 Model（支持
 *       {@code @ModelAttribute} 参数复用，经由 core 的 "先查 Model" 语义）；</li>
 *   <li>{@link #postHandle}：handler 后把 Model 中匹配属性写回 session；若本次请求的
 *       {@link SessionStatus#setComplete()} 被调用则清理 session 属性（同步与异步完成
 *       均经此回调——异步在 {@code asyncDispatch} 完成后执行）。</li>
 * </ul>
 * 复用 Spring 原生 {@link SessionAttributesHandler} + {@link DefaultSessionAttributeStore}
 * （基于 {@link WebRequest}，不依赖具体容器），仅 session 读写经由本框架的
 * servlet 桥接层（{@link ServletAttribute}）。
 */
@ControllerAdvice(annotations = SessionAttributes.class)
public class SessionAttributesInterceptor implements HandlerInterceptor {

    static final RequestAttribute<SessionStatus> SESSION_STATUS_KEY =
            RequestAttribute.createAttribute(SessionStatus.class);

    private final ConcurrentHashMap<Class<?>, SessionAttributesHandler> handlerCache = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                             Object handler) {
        MappingHandlerMethod mapping = asHandlerMethod(handler);
        if (mapping == null) {
            return true;
        }
        SessionAttributesHandler attrsHandler = resolveHandler(mapping.getBeanType());
        if (attrsHandler == null || !attrsHandler.hasSessionAttributes()) {
            return true;
        }
        Map<String, Object> attributes = attrsHandler.retrieveAttributes(webRequest(request, response));
        if (attributes.isEmpty()) {
            return true;
        }
        ModelMap model = ModelContext.getOrCreate(request);
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            model.addAttribute(entry.getKey(), entry.getValue());
        }
        return true;
    }

    @Override
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response,
                           Object handler, Object result) {
        MappingHandlerMethod mapping = asHandlerMethod(handler);
        if (mapping == null) {
            return;
        }
        updateModel(request, response, mapping);
    }

    private void updateModel(WebServerHttpRequest request, WebServerHttpResponse response,
                             MappingHandlerMethod handler) {
        SessionAttributesHandler attrsHandler = resolveHandler(handler.getBeanType());
        if (attrsHandler == null || !attrsHandler.hasSessionAttributes()) {
            return;
        }
        WebRequest webRequest = webRequest(request, response);
        SessionStatus status = request.getRequestContext().getAttribute(SESSION_STATUS_KEY);
        if (status != null && status.isComplete()) {
            attrsHandler.cleanupAttributes(webRequest);
            return;
        }
        ModelMap model = ModelContext.get(request);
        if (model == null) {
            return;
        }
        attrsHandler.storeAttributes(webRequest, model);
    }

    /**
     * 获取（或创建）本次请求绑定的 {@link SessionStatus}，供
     * {@link SessionStatusArgumentResolverProvider} 注入 handler 参数。
     */
    public SessionStatus getOrCreateSessionStatus(WebServerHttpRequest request,
                                                  WebServerHttpResponse response) {
        SessionStatus status = request.getRequestContext().getAttribute(SESSION_STATUS_KEY);
        if (status == null) {
            status = new SimpleSessionStatus();
            request.getRequestContext().setAttribute(SESSION_STATUS_KEY, status);
        }
        return status;
    }

    private static MappingHandlerMethod asHandlerMethod(Object handler) {
        return handler instanceof MappingHandlerMethod ? (MappingHandlerMethod) handler : null;
    }

    private SessionAttributesHandler resolveHandler(Class<?> beanType) {
        return handlerCache.computeIfAbsent(beanType, type ->
                AnnotatedElementUtils.hasAnnotation(type, SessionAttributes.class)
                        ? new SessionAttributesHandler(type, new DefaultSessionAttributeStore())
                        : null);
    }

    /**
     * 仅供测试：包可见的 handler 解析入口（验证 @SessionAttributes 类级匹配判定）。
     */
    SessionAttributesHandler resolveHandlerForTest(Class<?> beanType) {
        return resolveHandler(beanType);
    }

    private static WebRequest webRequest(WebServerHttpRequest request, WebServerHttpResponse response) {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(request, response);
        return new ServletWebRequest(adapterContext.getRequest(), adapterContext.getResponse());
    }
}
