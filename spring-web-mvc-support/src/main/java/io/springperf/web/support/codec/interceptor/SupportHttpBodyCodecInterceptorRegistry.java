package io.springperf.web.support.codec.interceptor;

import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptorRegistry;
import io.springperf.web.core.codec.interceptor.WebComponentControllerAdviceBean;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

public class SupportHttpBodyCodecInterceptorRegistry extends HttpBodyCodecInterceptorRegistry {

    @Override
    protected void initCodecInterceptors() {
        super.initCodecInterceptors();
        List<ControllerAdviceBean> adviceBeans1 = getControllerAdviceBean(RequestBodyAdvice.class);
        for (ControllerAdviceBean adviceBean : adviceBeans1) {
            registerWebComponent(new WebComponentControllerAdviceBean<>(adviceBean, new RequestBodyAdviceCodecInterceptor((RequestBodyAdvice) adviceBean.resolveBean())));
        }
        List<ControllerAdviceBean> adviceBeans2 = getControllerAdviceBean(ResponseBodyAdvice.class);
        for (ControllerAdviceBean adviceBean : adviceBeans2) {
            registerWebComponent(new WebComponentControllerAdviceBean<>(adviceBean, new ResponseBodyAdviceCodecInterceptor((ResponseBodyAdvice) adviceBean.resolveBean())));
        }
    }
}
