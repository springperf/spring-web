package io.springperf.web.core.cors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

public abstract class CorsUtils {

    /**
     * Returns {@code true} if the request is a valid CORS one by checking
     * the {@code Origin} header against the request URI.
     *
     * @param request the HTTP request to check
     * @return {@code true} if the request has a cross-origin {@code Origin} header
     */
    @SuppressWarnings("deprecation")
    public static boolean isCorsRequest(ServerHttpRequest request) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null) {
            return false;
        }

        URI uri = request.getURI();
        String actualScheme = uri.getScheme();
        String actualHost = uri.getHost();
        int actualPort = getPort(uri.getScheme(), uri.getPort());
        Assert.notNull(actualScheme, "Actual request scheme must not be null");
        Assert.notNull(actualHost, "Actual request host must not be null");
        Assert.isTrue(actualPort != -1, "Actual request port must not be undefined");

        UriComponents originUrl = UriComponentsBuilder.fromOriginHeader(origin).build();
        boolean isSameOrigin = (actualScheme.equals(originUrl.getScheme()) &&
                actualHost.equals(originUrl.getHost()) &&
                actualPort == getPort(originUrl.getScheme(), originUrl.getPort()));
        return !isSameOrigin;
    }

    /**
     * Returns {@code true} if the request is a valid CORS pre-flight one by checking {@code OPTIONS} method with
     * {@code Origin} and {@code Access-Control-Request-Method} headers presence.
     *
     * @param request the HTTP request to check
     * @return {@code true} if the request is a CORS preflight
     */
    public static boolean isPreFlightRequest(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        return (request.getMethod() == HttpMethod.OPTIONS && headers.containsKey(HttpHeaders.ORIGIN)
                && headers.containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD));
    }

    private static int getPort(@Nullable String scheme, int port) {
        if (port == -1) {
            if ("http".equals(scheme) || "ws".equals(scheme)) {
                port = 80;
            } else if ("https".equals(scheme) || "wss".equals(scheme)) {
                port = 443;
            }
        }
        return port;
    }
}
