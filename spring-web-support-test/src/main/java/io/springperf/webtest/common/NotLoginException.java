package io.springperf.webtest.common;

public class NotLoginException extends RuntimeException {
    
    public NotLoginException() {
        super(ApiErrorCode.UNAUTHORIZED.getMsg());
    }

    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
