package io.springperf.web.core.codec.interceptor;

import io.springperf.web.context.WebComponentWrapper;
import org.springframework.web.method.ControllerAdviceBean;

public class WebComponentControllerAdviceBean<T> extends WebComponentWrapper<T> {

    private final ControllerAdviceBean adviceBean;

    public WebComponentControllerAdviceBean(ControllerAdviceBean adviceBean) {
        super((T) adviceBean.resolveBean(), adviceBean.toString());
        this.adviceBean = adviceBean;
    }

    public WebComponentControllerAdviceBean(ControllerAdviceBean adviceBean, T realBean) {
        super(realBean, adviceBean.toString());
        this.adviceBean = adviceBean;
    }

    public WebComponentControllerAdviceBean(String componentName, T realBean) {
        super(realBean, componentName);
        this.adviceBean = null;
    }

    public boolean isApplicableToBeanType(Class<?> beanType) {
        if (adviceBean == null) {
            return true;
        }
        return adviceBean.isApplicableToBeanType(beanType);
    }

}
