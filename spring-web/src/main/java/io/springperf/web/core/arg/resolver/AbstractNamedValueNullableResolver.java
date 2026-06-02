package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.util.MetaUtils;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;

import java.lang.annotation.Annotation;

public abstract class AbstractNamedValueNullableResolver extends AbstractNamedValueResolver implements StaticArgumentResolver {

    protected final boolean required;
    protected final String defaultValue;

    public AbstractNamedValueNullableResolver(WebContext webContext, MappingHandlerMethod mappingContext, MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        super(webContext, mappingContext, parameter, supportClass);
        this.required = MetaUtils.getRequired(parameter, supportClass);
        this.defaultValue = MetaUtils.getDefaultValue(parameter, supportClass);
    }

    public AbstractNamedValueNullableResolver(MappingHandlerMethod mappingContext, MethodParameter parameter, WebContext webContext, String name, boolean required, String defaultValue) {
        super(mappingContext, parameter, webContext, name);
        this.required = required;
        this.defaultValue = defaultValue;
    }

    /**
     * A {@code null} results in a {@code false} value for {@code boolean}s or an exception for other primitives.
     */
    @Nullable
    protected Object handleNullValue(String name, Class<?> paramType) {
        if (defaultValue != null) {
            return defaultValue;
        } else if (required && !isOptional) {
            handleMissingValue(name, paramType);
            return null;
        } else {
            return super.handleNullValue(name, paramType);
        }
    }

    @Override
    protected Object handleEmptyValue(@Nullable Object arg, String name, Class<?> paramType) {
        if (defaultValue != null) {
            return defaultValue;
        } else {
            return arg;
        }
    }

    protected void handleMissingValue(String name, Class<?> paramType) {
        throw new IllegalStateException("Missing argument '" + name + "' for method parameter of type " + paramType.getSimpleName());
    }
}
