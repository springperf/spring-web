package io.springperf.webtest.filter;

import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * P2 test: Ordered WebFilter (Order=low) that adds a header to verify filter chain order. Used together with
 * OrderedHighWebFilter to verify that filters execute in order.
 */
@Component
@Order(5)
public class OrderedLowWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Order-Low";

    /** 两个有序 filter 共同追加的 header：按出现顺序即可判定执行次序（同名 header 多值保留）。 */
    public static final String ORDER_HEADER = "X-Order";

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response,
            io.springperf.web.core.filter.FilterChain chain) throws Exception {
        response.getHeaders().add(HEADER_NAME, "executed");
        // @Order(5) 先于 @Order(100)，因此本值应排在 X-Order 的首位
        response.getHeaders().add(ORDER_HEADER, "low");
        chain.doFilter(request, response);
    }
}
