package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.MetaUtils;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;

import java.lang.annotation.Annotation;

public abstract class AbstractNamedValueResolver extends AbstractSupportOptionalResolver implements StaticArgumentResolver {

    protected final WebContext webContext;
    protected final String name;

    protected final WebDataBinderRegistry webDataBinderRegistry;

    public AbstractNamedValueResolver(WebContext webContext, MappingHandlerMethod mappingContext, MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        super(mappingContext, parameter);
        this.webContext = webContext;
        this.name = MetaUtils.getParameterName(parameter, supportClass);
        this.webDataBinderRegistry = webContext.getWebComponent(WebDataBinderRegistry.class);
    }

    public AbstractNamedValueResolver(MappingHandlerMethod mappingContext, MethodParameter parameter, WebContext webContext, String name) {
        super(mappingContext, parameter);
        this.webContext = webContext;
        this.name = name;
        this.webDataBinderRegistry = webContext.getWebComponent(WebDataBinderRegistry.class);
    }

    protected abstract Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;

    @Override
    protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        Object arg = resolveByName(request, response);
        if (arg == null) {
            arg = handleNullValue(name, paramType);
        } else if (isEmpty(arg)) {
            arg = handleEmptyValue(arg, name, paramType);
        }
        return convert(arg);
    }

    protected Object convert(Object arg) {
        if (arg == null) return null;
        if (paramType.isAssignableFrom(arg.getClass())) {
            return arg;
        }
        return webDataBinderRegistry.getConversionService(mappingContext).convert(arg, paramType);
    }

    /**
     * Handle a null value for the named parameter.
     * <p>A {@code null} results in a {@code false} value for {@code boolean}s or an exception for other primitives.</p>
     *
     * @param name      the parameter name
     * @param paramType the parameter type
     * @return the value to use for null (Boolean.FALSE for booleans, null for others)
     */
    @Nullable
    protected Object handleNullValue(String name, Class<?> paramType) {
        if (Boolean.TYPE.equals(paramType)) {
            return Boolean.FALSE;
        } else if (paramType.isPrimitive()) {
            throw new IllegalStateException("Optional " + paramType.getSimpleName() + " parameter '" + name + "' is present but cannot be translated into a null value due to being declared as a " + "primitive type. Consider declaring it as object wrapper for the corresponding primitive type.");
        }
        return null;
    }

    protected Object handleEmptyValue(@Nullable Object arg, String name, Class<?> paramType) {
        return arg;
    }

    protected boolean isEmpty(Object arg) {
        return arg == null || "".equals(arg);
    }
}
