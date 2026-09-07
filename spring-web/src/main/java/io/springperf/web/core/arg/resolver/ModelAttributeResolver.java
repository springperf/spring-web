package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.databinder.PerfDataBinder;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;

import java.lang.reflect.Constructor;

public class ModelAttributeResolver extends AbstractSupportOptionalResolver {

    protected final WebDataBinderFactory binderFactory;

    protected final WebContext webContext;
    protected final Constructor constructor;
    private final String name;
    private final boolean binding;

    public ModelAttributeResolver(WebDataBinderFactory binderFactory, WebContext webContext, MappingHandlerMethod mappingContext, MethodParameter parameter, String name, boolean binding) {
        super(mappingContext, parameter);
        this.binderFactory = binderFactory;
        this.webContext = webContext;
        this.name = name;
        this.binding = binding;
        this.constructor = initialConstructor(paramType);
    }

    protected Constructor initialConstructor(Class<?> clazz) {
        Constructor<?> ctor = BeanUtils.findPrimaryConstructor(clazz);
        if (ctor != null) {
            return ctor;
        }
        Constructor<?>[] constructors = clazz.getConstructors();
        if (constructors.length == 1) {
            ctor = constructors[0];
        } else {
            try {
                ctor = clazz.getDeclaredConstructor();
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("No primary or default constructor found for " + clazz, ex);
            }
        }
        if (ctor.getParameterCount() > 0) {
            throw new IllegalStateException("No primary or default constructor found for " + clazz);
        }
        return ctor;
    }

    protected Object constructAttribute() {
        return BeanUtils.instantiateClass(constructor);
    }

    @Override
    protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        // 通用语义（对齐 Spring MVC）：@ModelAttribute 参数先查请求级 Model，
        // 命中则直接复用（支持方法级处理器如 @SessionAttributes 预置的值），
        // 未命中才 new + bind，并回写 Model 供后续参数/视图复用。
        ModelMap model = ModelContext.get(request);
        if (model != null) {
            Object existing = model.get(name);
            if (existing != null) {
                return existing;
            }
        }
        NativeWebRequest webRequest = AsyncSupportUtils.getAsyncWebRequest(request, response);
        Object attribute = constructAttribute();
        WebDataBinder binder = binderFactory.createBinder(webRequest, attribute, name);
        if (binder.getTarget() != null && binding) {
            PerfDataBinder.bind(request, binder);
        }
        ModelContext.getOrCreate(request).addAttribute(name, attribute);
        return attribute;
    }
}
