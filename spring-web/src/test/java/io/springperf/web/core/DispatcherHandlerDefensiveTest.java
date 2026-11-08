package io.springperf.web.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/** 收尾路径的防御分支：异步状态探测失败、错误响应写出失败都不得抛出二次异常。 */
class DispatcherHandlerDefensiveTest {

    @Test
    void isAsyncStillPending_requestContextFailureReturnsFalse() throws Exception {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        when(request.getRequestContext()).thenThrow(new IllegalStateException("boom"));
        Method method = DispatcherHandler.class.getDeclaredMethod("isAsyncStillPending", WebServerHttpRequest.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(null, request), "拿不到 RequestContext 时必须保守地判定为「非挂起」，让收尾继续");
    }

    @Test
    void sendError_responseFailureIsSwallowed() throws Exception {
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        doThrow(new IllegalStateException("boom")).when(response).sendError(any(HttpStatus.class), anyString());

        DispatcherHandler.sendError(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");

        verify(response).sendError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }
}
