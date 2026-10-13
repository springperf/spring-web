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
        // 仅支持纯 Errors/BindingResult 形参（须紧跟模型属性 / @RequestBody / @RequestPart 之后）。
        //
        // 必须排除异常类型：MethodArgumentNotValidException 与 BindException 家族都 implements
        // BindingResult，会被 isAssignableFrom(Errors) 命中，但 @ExceptionHandler 上的这类形参语义是
        // 「被抛出的异常本身」（由 ExceptionArgumentResolverProvider 注入）。若不排除，本 provider 会
        // 抢先命中并在请求属性里查不到 BindingResult 而抛 IllegalStateException →
        // 最常见的 @ExceptionHandler(MethodArgumentNotValidException ex) 写法变成 500。
        Class<?> type = parameter.getParameterType();
        return Errors.class.isAssignableFrom(type) && !Throwable.class.isAssignableFrom(type);
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
