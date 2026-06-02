package io.springperf.web.support.servlet.context;

import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import lombok.Getter;

import javax.servlet.FilterChain;

@Getter
public class ServletAdapterContext {

    public static final String REQUEST_ATTRIBUTE_NAME = "spring.web.rest.ServletAdapterContext";

    private PerfHttpServletRequest request;

    private PerfHttpServletResponse response;

    private FilterChain filterChain;

    public ServletAdapterContext(PerfHttpServletRequest request, PerfHttpServletResponse response, FilterChain filterChain) {
        this.request = request;
        this.response = response;
        this.filterChain = filterChain;
    }
}
