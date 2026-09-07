package io.springperf.web.core.model;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.ModelMap;

/**
 * 请求级 Model 容器（一等公民，core 持有）。
 * <p>与 {@link org.springframework.ui.Model} 语义对齐：同一请求内所有 {@code Model}/
 * {@code ModelMap}/{@code ExtendedModelMap} 参数共享同一实例，handler 前预置、参数解析、
 * 返回值处理、视图渲染均从同一容器读写。</p>
 * <p>懒创建：仅在真正需要 Model 的请求（声明了 Model 参数 / 注册了 ModelLifecycle）时
 * 才实例化 {@link ExtendedModelMap}，纯 API 请求零开销。</p>
 */
public final class ModelContext {

    private static final RequestAttribute<ModelMap> MODEL_KEY =
            RequestAttribute.createAttribute(ModelMap.class);

    private ModelContext() {
    }

    public static ModelMap getOrCreate(WebServerHttpRequest req) {
        RequestContext ctx = req.getRequestContext();
        ModelMap model = ctx.getAttribute(MODEL_KEY);
        if (model == null) {
            model = new ExtendedModelMap();
            ctx.setAttribute(MODEL_KEY, model);
        }
        return model;
    }

    /**
     * 非创建查询：仅当本请求已存在 Model 时返回，否则 {@code null}。
     * <p>供 {@code @ModelAttribute} 解析"复用已有属性"时使用——纯 API 请求（无 Model 参数、
     * 无 ModelLifecycle）不会被此查询副作用创建 Model 容器。</p>
     */
    public static ModelMap get(WebServerHttpRequest req) {
        return req.getRequestContext().getAttribute(MODEL_KEY);
    }
}
