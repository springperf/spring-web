package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpRequestWrapper;

import java.net.URI;

public class ForwardWebServerHttpRequest extends WebServerHttpRequestWrapper {

    private final String forwardPath;
    private final String forwardUri;
    private final String forwardUriWithQuery;
    private final URI forwardUriObj;

    public ForwardWebServerHttpRequest(WebServerHttpRequest request, String forwardPath) {
        super(request);
        int queryIdx = forwardPath.indexOf('?');
        if (queryIdx >= 0) {
            this.forwardPath = forwardPath.substring(0, queryIdx);
            this.forwardUri = this.forwardPath;
            this.forwardUriWithQuery = forwardPath;
        } else {
            this.forwardPath = forwardPath;
            this.forwardUri = forwardPath;
            String originalQuery = null;
            try {
                String q = request.getURI().getRawQuery();
                if (q != null && !q.isEmpty()) {
                    originalQuery = "?" + q;
                }
            } catch (Exception ignored) {
            }
            this.forwardUriWithQuery = originalQuery != null ? forwardPath + originalQuery : forwardPath;
        }
        URI originalUri = request.getURI();
        String scheme = originalUri.getScheme();
        String authority = originalUri.getRawAuthority();
        if (authority != null) {
            this.forwardUriObj = URI.create(scheme + "://" + authority + this.forwardUriWithQuery);
        } else {
            this.forwardUriObj = URI.create(this.forwardUriWithQuery);
        }
    }

    @Override
    public String getPath() {
        return forwardPath;
    }

    @Override
    public String getUriStr() {
        return forwardUri;
    }

    @Override
    public String getUriStrWithQuery() {
        return forwardUriWithQuery;
    }

    @Override
    public URI getURI() {
        return forwardUriObj;
    }
}