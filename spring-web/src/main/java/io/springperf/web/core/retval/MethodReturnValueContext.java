package io.springperf.web.core.retval;

import org.springframework.core.MethodParameter;

public class MethodReturnValueContext {

    protected MethodParameter returnType;
    protected ReturnValueResolver returnValueResolver;

    /** 异步类型的泛型参数类型，如 DeferredResult<String> 中的 String */
    protected MethodParameter innerReturnType;
    /** 匹配 innerReturnType 的解析器 */
    protected ReturnValueResolver innerReturnValueResolver;
    /** 是否为异步返回类型 */
    protected boolean asyncType;

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

    public MethodParameter getInnerReturnType() {
        return innerReturnType;
    }

    public void setInnerReturnType(MethodParameter innerReturnType) {
        this.innerReturnType = innerReturnType;
    }

    public ReturnValueResolver getInnerReturnValueResolver() {
        return innerReturnValueResolver;
    }

    public void setInnerReturnValueResolver(ReturnValueResolver innerReturnValueResolver) {
        this.innerReturnValueResolver = innerReturnValueResolver;
    }

    public boolean isAsyncType() {
        return asyncType;
    }

    public void setAsyncType(boolean asyncType) {
        this.asyncType = asyncType;
    }
}