package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;

public class ErrorsResolverProvider implements StaticArgumentResolverProvider {

    public static final String REQUEST_ATTRIBUTE_KEY = BindingResult.MODEL_KEY_PREFIX + "errors.";

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return Errors.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        String attributeName = (BindingResult.MODEL_KEY_PREFIX + parameter.getParameterIndex()).intern();
        return (request, response) -> {
            Object errors = request.getRequestContext().removeAttribute(attributeName);
            if (errors != null) {
                return errors;
            }
            throw new IllegalStateException(
                    "An Errors/BindingResult argument is expected to be declared immediately after " +
                            "the model attribute, the @RequestBody or the @RequestPart arguments " +
                            "to which they apply: " + parameter.getMethod());
        };
    }
}
