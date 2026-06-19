package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.filter.match.PathMatch;

import java.util.List;

public class MatchFilterWrapper extends FilterWrapper {

    private final List<PathMatch> pathMatchList;

    public MatchFilterWrapper(javax.servlet.Filter filter, String[] supportedPathRules) {
        super(filter);
        this.pathMatchList = PathMatch.create(supportedPathRules);
    }

    public MatchFilterWrapper(javax.servlet.Filter filter, String[] supportedPathRules, int order) {
        super(filter);
        this.pathMatchList = PathMatch.create(supportedPathRules);
        this.order = order;
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        String uri = request.getPath();
        if (match(uri)) {
            doFilterInternal(request, response, chain);
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean match(String uri) {
        if (pathMatchList.isEmpty()) {
            return true;
        }
        for (PathMatch pathMatch : pathMatchList) {
            if (pathMatch.match(uri)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        if (pathMatchList.isEmpty()) {
            return filter.toString();
        } else {
            return filter.toString() + " " + pathMatchList;
        }
    }
}