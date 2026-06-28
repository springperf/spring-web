package org.springframework.web.servlet.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.context.request.AsyncWebRequestInterceptor;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

public class WebRequestHandlerInterceptorAdapter implements AsyncHandlerInterceptor {

    private final WebRequestInterceptor requestInterceptor;


    /**
     * Create a new WebRequestHandlerInterceptorAdapter for the given WebRequestInterceptor.
     *
     * @param requestInterceptor the WebRequestInterceptor to wrap
     */
    public WebRequestHandlerInterceptorAdapter(WebRequestInterceptor requestInterceptor) {
        Assert.notNull(requestInterceptor, "WebRequestInterceptor must not be null");
        this.requestInterceptor = requestInterceptor;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        this.requestInterceptor.preHandle(new ServletWebRequest(request, response));
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           @Nullable ModelAndView modelAndView) throws Exception {

        this.requestInterceptor.postHandle(new ServletWebRequest(request, response),
                (modelAndView != null && !modelAndView.wasCleared() ? modelAndView.getModelMap() : null));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception ex) throws Exception {

        this.requestInterceptor.afterCompletion(new ServletWebRequest(request, response), ex);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (this.requestInterceptor instanceof AsyncWebRequestInterceptor) {
            AsyncWebRequestInterceptor asyncInterceptor = (AsyncWebRequestInterceptor) this.requestInterceptor;
            ServletWebRequest webRequest = new ServletWebRequest(request, response);
            asyncInterceptor.afterConcurrentHandlingStarted(webRequest);
        }
    }

}
