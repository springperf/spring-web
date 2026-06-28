package io.springperf.web.support.servlet.context;

import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import jakarta.servlet.FilterChain;
import lombok.Getter;

@Getter
public class ServletAdapterContext {

    private PerfHttpServletRequest request;

    private PerfHttpServletResponse response;

    private FilterChain filterChain;

    public ServletAdapterContext(PerfHttpServletRequest request, PerfHttpServletResponse response, FilterChain filterChain) {
        this.request = request;
        this.response = response;
        this.filterChain = filterChain;
    }
}
