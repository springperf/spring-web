package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.core.mapping.match.MediaTypeExpressionSupport;
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 为每个 Actuator 端点操作创建 {@link CustomInvoker}，提供路由匹配所需的 Matcher。
 */
public class OperationHandlerInvoker implements CustomInvoker {

    private static final Method HANDLE_METHOD;

    static {
        try {
            HANDLE_METHOD = OperationHandlerInvoker.class.getMethod("handleMethod");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot find handle method", e);
        }
    }

    private final WebOperation operation;
    private final WebOperationRequestPredicate predicate;
    private final List<Matcher> matchers;

    public OperationHandlerInvoker(WebOperation operation, WebOperationRequestPredicate predicate,
                                   Collection<String> consumableMediaTypes) {
        this.operation = operation;
        this.predicate = predicate;
        this.matchers = initMatchers(predicate, consumableMediaTypes);
    }

    /**
     * 占位方法 —— 仅用于 {@link org.springframework.web.method.HandlerMethod} 构造时的 Method 引用。
     */
    @SuppressWarnings("unused")
    public Object handleMethod() {
        return null;
    }

    @Override
    public Method getHandleMethod() {
        return HANDLE_METHOD;
    }

    @Override
    public List<Matcher> getMatchers() {
        return matchers;
    }

    @Override
    public String getType() {
        return "Actuator";
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        // 实际调用由 PerfActuatorPathMappingContext.invoke() 覆盖，此处不应被调用
        throw new UnsupportedOperationException("OperationHandlerInvoker should not be invoked directly");
    }

    public WebOperation getOperation() {
        return operation;
    }

    public WebOperationRequestPredicate getPredicate() {
        return predicate;
    }

    // ========== Matcher 构造 ==========

    private static List<Matcher> initMatchers(WebOperationRequestPredicate predicate,
                                              Collection<String> consumableMediaTypes) {
        List<Matcher> result = new ArrayList<>(4);

        // HTTP 方法
        WebEndpointHttpMethod httpMethod = predicate.getHttpMethod();
        HttpMethod httpMethodEnum = toSpringHttpMethod(httpMethod);
        if (httpMethodEnum != null) {
            result.add(new HttpMethodMatcher(new HttpMethod[]{httpMethodEnum}));
        }

        // Consumes
        Collection<String> consumes = predicate.getConsumes();
        // 只在有明确 consumes 语义时添加 ConsumeOrProduceMatcher
        if (!CollectionUtils.isEmpty(consumes) && !CollectionUtils.isEmpty(consumableMediaTypes)) {
            List<MediaTypeExpressionSupport> expressionList = consumableMediaTypes.stream()
                    .map(MediaTypeExpressionSupport::build)
                    .collect(Collectors.toList());
            if (!expressionList.isEmpty()) {
                result.add(new ConsumeOrProduceMatcher(false, expressionList));
            }
        }

        // Produces —— Actuator 默认输出 application/json
        Collection<String> produces = predicate.getProduces();
        if (!CollectionUtils.isEmpty(produces)) {
            List<MediaTypeExpressionSupport> expressionList = produces.stream()
                    .map(MediaTypeExpressionSupport::build)
                    .collect(Collectors.toList());
            result.add(new ConsumeOrProduceMatcher(true, expressionList));
        }

        return Collections.unmodifiableList(result);
    }

    private static HttpMethod toSpringHttpMethod(WebEndpointHttpMethod method) {
        if (method == null) {
            return null;
        }
        switch (method) {
            case GET:
                return HttpMethod.GET;
            case POST:
                return HttpMethod.POST;
            case DELETE:
                return HttpMethod.DELETE;
            default:
                return null;
        }
    }
}