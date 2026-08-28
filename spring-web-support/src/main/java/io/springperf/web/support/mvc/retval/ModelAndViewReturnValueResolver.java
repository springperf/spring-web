package io.springperf.web.support.mvc.retval;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.ModelSupport;
import io.springperf.web.view.RedirectView;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolverRegistry;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.Charset;
import java.util.Map;

public class ModelAndViewReturnValueResolver extends BaseWebComponent implements ReturnValueResolver {

    private ViewResolverRegistry viewResolverRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        viewResolverRegistry = webContext.getWebComponentWithDefault(
                ViewResolverRegistry.class, new ViewResolverRegistry());
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return ModelAndView.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (!(returnValue instanceof ModelAndView)) {
            return false;
        }
        ModelAndView mav = (ModelAndView) returnValue;
        return mav.isReference() && !mav.wasCleared();
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                   WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        ModelAndView mav = (ModelAndView) returnValue;
        String viewName = mav.getViewName();
        if (viewName == null) {
            return;
        }

        HttpStatus status = mav.getStatus();
        if (status != null) {
            resp.setStatusCode(status);
        }

        View view = resolveView(viewName, req);
        if (view == null) {
            return;
        }

        String contentType = view.getContentType();
        if (contentType != null) {
            resp.getHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
        } else {
            Charset charset = resp.getCharacterEncoding();
            if (charset == null) {
                charset = Charset.forName("UTF-8");
            }
            resp.getHeaders().set(HttpHeaders.CONTENT_TYPE, "text/html;charset=" + charset.name());
        }
        resp.setHandled();

        Map<String, Object> model = mav.getModel();
        if (model != null && !model.isEmpty()) {
            ModelSupport.getOrCreate(req).addAllAttributes(model);
        }
        view.render(ModelSupport.getOrCreate(req), req, resp);
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 200;
    }

    private View resolveView(String viewName, WebServerHttpRequest req) {
        if (viewName.startsWith("redirect:")) {
            return new RedirectView(viewName.substring(9));
        }
        return viewResolverRegistry.resolve(viewName, req);
    }
}