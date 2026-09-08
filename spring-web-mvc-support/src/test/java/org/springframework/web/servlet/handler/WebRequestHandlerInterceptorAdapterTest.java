package org.springframework.web.servlet.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ModelMap;
import org.springframework.web.context.request.AsyncWebRequestInterceptor;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebRequestHandlerInterceptorAdapterTest {

    @Mock WebRequestInterceptor requestInterceptor;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock ModelAndView modelAndView;
    @Captor ArgumentCaptor<ServletWebRequest> webRequestCaptor;

    @Test
    void constructor_nullInterceptor_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebRequestHandlerInterceptorAdapter(null));
    }

    @Test
    void preHandle_delegatesAndReturnsTrue() throws Exception {
        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);

        boolean result = adapter.preHandle(request, response, "handler");

        assertTrue(result);
        verify(requestInterceptor).preHandle(any(ServletWebRequest.class));
    }

    @Test
    void postHandle_delegatesWithModelMap() throws Exception {
        ModelMap modelMap = new ModelMap("key", "value");
        when(modelAndView.wasCleared()).thenReturn(false);
        when(modelAndView.getModelMap()).thenReturn(modelMap);

        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);
        adapter.postHandle(request, response, "handler", modelAndView);

        verify(requestInterceptor).postHandle(any(ServletWebRequest.class), same(modelMap));
    }

    @Test
    void postHandle_clearedModel_passesNullModel() throws Exception {
        when(modelAndView.wasCleared()).thenReturn(true);

        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);
        adapter.postHandle(request, response, "handler", modelAndView);

        verify(requestInterceptor).postHandle(any(ServletWebRequest.class), isNull());
    }

    @Test
    void postHandle_nullModelAndView_passesNullModel() throws Exception {
        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);
        adapter.postHandle(request, response, "handler", null);

        verify(requestInterceptor).postHandle(any(ServletWebRequest.class), isNull());
    }

    @Test
    void afterCompletion_delegatesWithException() throws Exception {
        Exception ex = new Exception("test");

        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);
        adapter.afterCompletion(request, response, "handler", ex);

        verify(requestInterceptor).afterCompletion(any(ServletWebRequest.class), eq(ex));
    }

    @Test
    void afterCompletion_nullException_delegates() throws Exception {
        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);
        adapter.afterCompletion(request, response, "handler", null);

        verify(requestInterceptor).afterCompletion(any(ServletWebRequest.class), isNull());
    }

    @Test
    void afterConcurrentHandlingStarted_regularInterceptor_noOp() {
        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(requestInterceptor);
        adapter.afterConcurrentHandlingStarted(request, response, "handler");

        // 默认 WebRequestInterceptor 不实现 AsyncWebRequestInterceptor，不应调用任何方法
        verifyNoMoreInteractions(requestInterceptor);
    }

    @Test
    void afterConcurrentHandlingStarted_asyncInterceptor_delegates() {
        AsyncWebRequestInterceptor asyncInterceptor = mock(AsyncWebRequestInterceptor.class);
        WebRequestHandlerInterceptorAdapter adapter = new WebRequestHandlerInterceptorAdapter(asyncInterceptor);

        adapter.afterConcurrentHandlingStarted(request, response, "handler");

        verify(asyncInterceptor).afterConcurrentHandlingStarted(any(ServletWebRequest.class));
    }
}