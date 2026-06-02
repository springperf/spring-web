package io.springperf.web.core.retval;

import org.springframework.core.MethodParameter;

public class MethodReturnValueContext {

    protected MethodParameter returnType;
    protected ReturnValueResolver returnValueResolver;

    public MethodParameter getReturnType() {
        return returnType;
    }

    public void setReturnType(MethodParameter returnType) {
        this.returnType = returnType;
    }

    public ReturnValueResolver getReturnValueResolver() {
        return returnValueResolver;
    }

    public void setReturnValueResolver(ReturnValueResolver returnValueResolver) {
        this.returnValueResolver = returnValueResolver;
    }
}
