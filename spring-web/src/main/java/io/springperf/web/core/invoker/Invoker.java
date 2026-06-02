package io.springperf.web.core.invoker;

public interface Invoker {

    Object invoke(Object[] args) throws Throwable;
}
