package io.springperf.web.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

public class RedirectView implements View {

    private final String redirectUrl;

    public RedirectView(String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }

    @Override
    public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        resp.setStatusCode(HttpStatus.FOUND);
        String location = buildLocation(redirectUrl, req);
        String query = buildQueryString(model);
        if (!query.isEmpty()) {
            location += "?" + query;
        }
        resp.getHeaders().set(HttpHeaders.LOCATION, location);
    }

    private static String buildLocation(String url, WebServerHttpRequest req) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String cp = req.getWebContext().getContextPath();
        if (url.startsWith("/")) {
            return cp + url;
        }
        return cp + "/" + url;
    }

    private static String buildQueryString(Map<String, ?> model) {
        if (model == null || model.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> entry : model.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!isSimpleType(value.getClass())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(encode(entry.getKey())).append("=").append(encode(value.toString()));
        }
        return sb.toString();
    }

    private static boolean isSimpleType(Class<?> type) {
        return type == String.class || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class || type == Boolean.class
                || type == Short.class || type == Byte.class || type == Character.class
                || type.isPrimitive();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}