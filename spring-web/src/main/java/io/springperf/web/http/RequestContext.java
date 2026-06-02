package io.springperf.web.http;

import java.util.Map;

public interface RequestContext {

    Map<String, Object> getAttributes();

    Object getAttribute(String name);

    void setAttribute(String name, Object o);

    Object removeAttribute(String name);

    <T> T getAttribute(RequestAttribute<T> key);

    <T> void setAttribute(RequestAttribute<T> key, T value);
}
