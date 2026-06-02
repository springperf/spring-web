package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import org.springframework.http.HttpMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * 用于 Actuator links 端点（{@code GET /actuator}）的 {@link CustomInvoker}。
 * <p>该端点返回所有已发现 Actuator 端点的链接列表。</p>
 */
public class LinksOperationInvoker implements CustomInvoker {

    private static final Method HANDLE_METHOD;

    static {
        try {
            HANDLE_METHOD = LinksOperationInvoker.class.getMethod("handleMethod");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot find handle method", e);
        }
    }

    private static final List<Matcher> MATCHERS = Collections.singletonList(
            new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET})
    );

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
        return MATCHERS;
    }

    @Override
    public String getType() {
        return "ActuatorLinks";
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        throw new UnsupportedOperationException("LinksOperationInvoker should not be invoked directly");
    }
}