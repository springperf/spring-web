package io.springperf.webtest;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolver;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * E2E 用视图解析器：命中 {@code render-boom} 时先写出半截 HTML 再抛异常，
 * 用于验证渲染失败时错误响应能覆盖半截页面、而不是返回 200。
 */
@Component
public class RenderBoomViewResolver extends BaseWebComponent implements ViewResolver {

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) {
        if (!"render-boom".equals(viewName)) {
            return null;
        }
        return (model, request, response) -> {
            response.getBody().write("RENDER-BOOM-PARTIAL".getBytes(StandardCharsets.UTF_8));
            throw new RenderBoomException("render boom");
        };
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** 受检异常：不与任何 {@code @ExceptionHandler} 的 RuntimeException 映射重合，确保走 500 兜底。 */
    public static class RenderBoomException extends Exception {
        public RenderBoomException(String message) {
            super(message);
        }
    }
}
