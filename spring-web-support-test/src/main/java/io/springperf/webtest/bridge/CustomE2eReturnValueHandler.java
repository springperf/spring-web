package io.springperf.webtest.bridge;

import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.servlet.http.HttpServletResponse;

/**
 * A custom {@link HandlerMethodReturnValueHandler} registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#addReturnValueHandlers}.
 * <p>
 * For {@link CustomE2eReturnValueDto} return types, it writes a response prefixed with "custom-handled:".
 * For other return types, it passes through the original string value.
 */
public class CustomE2eReturnValueHandler implements HandlerMethodReturnValueHandler {

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return CustomE2eReturnValueDto.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public void handleReturnValue(Object returnValue, MethodParameter returnType,
                                  ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {
        HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);
        if (response == null) {
            return;
        }
        String body;
        if (returnValue instanceof CustomE2eReturnValueDto) {
            body = "custom-handled:" + ((CustomE2eReturnValueDto) returnValue).getMessage();
        } else {
            body = returnValue != null ? returnValue.toString() : "";
        }
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}
