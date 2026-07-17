package io.springperf.web.core.retval;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MethodReturnValueContextTest {

    @Test
    void returnType_initialState_isNull() {
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        assertNull(ctx.getReturnType());
    }

    @Test
    void returnValueResolver_initialState_isNull() {
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        assertNull(ctx.getReturnValueResolver());
    }

    @Test
    void setAndGetReturnType() throws Exception {
        Method method = getClass().getMethod("dummy");
        MethodParameter mp = new MethodParameter(method, -1);
        MethodReturnValueContext ctx = new MethodReturnValueContext();

        ctx.setReturnType(mp);

        assertSame(mp, ctx.getReturnType());
    }

    @Test
    void setAndGetReturnValueResolver() {
        ReturnValueResolver resolver = mockResolver();
        MethodReturnValueContext ctx = new MethodReturnValueContext();

        ctx.setReturnValueResolver(resolver);

        assertSame(resolver, ctx.getReturnValueResolver());
    }

    // ===== 新增字段 =====

    @Test
    void innerReturnType_initialState_isNull() {
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        assertNull(ctx.getInnerReturnType());
    }

    @Test
    void setAndGetInnerReturnType() throws Exception {
        MethodParameter mp = new MethodParameter(getClass().getMethod("dummy"), -1);
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        ctx.setInnerReturnType(mp);
        assertSame(mp, ctx.getInnerReturnType());
    }

    @Test
    void innerReturnValueResolver_initialState_isNull() {
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        assertNull(ctx.getInnerReturnValueResolver());
    }

    @Test
    void setAndGetInnerReturnValueResolver() {
        ReturnValueResolver resolver = mockResolver();
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        ctx.setInnerReturnValueResolver(resolver);
        assertSame(resolver, ctx.getInnerReturnValueResolver());
    }

    @Test
    void asyncType_initialState_isFalse() {
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        assertFalse(ctx.isAsyncType());
    }

    @Test
    void setAsyncType_toTrue() {
        MethodReturnValueContext ctx = new MethodReturnValueContext();
        ctx.setAsyncType(true);
        assertTrue(ctx.isAsyncType());
    }

    private ReturnValueResolver mockResolver() {
        return new ReturnValueResolver() {
            @Override
            public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) { return false; }
            @Override
            public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) { return false; }
            @Override
            public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) {}
        };
    }

    @SuppressWarnings("unused")
    public void dummy() {}
}
