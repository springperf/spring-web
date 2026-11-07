package io.springperf.web.view;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.lang.Nullable;

import java.util.Locale;

public interface ViewResolver extends WebComponent {

    @Nullable
    View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception;

    /**
     * 当前解析器是否参与视图解析与启动期校验。
     * <p>
     * 引擎级开关（{@code spring.*.enabled}）通过此接口告知注册中心，使「引入视图模块但所有引擎都被禁用」
     * 仍能触发启动期 fail-fast，而不是退化成每请求 500。自定义实现默认启用。
     * </p>
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 使本解析器的解析结果与模板引擎内部缓存失效。
     * <p>
     * 运行期新增或替换模板后，由 {@link ViewResolverRegistry#invalidateViewCaches()} 统一调用；
     * 内置实现须同时清模板引擎缓存，否则替换后的模板不会生效。无缓存的自定义实现保持默认空实现。
     * </p>
     */
    default void invalidateCache() {
    }
}
