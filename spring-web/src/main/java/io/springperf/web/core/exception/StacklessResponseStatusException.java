package io.springperf.web.core.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 无堆栈信息的 {@link ResponseStatusException} 子类。
 * <p>覆盖 {@link #fillInStackTrace()} 返回 {@code this}，禁止 JVM 填充栈轨迹。
 * 适用于作为静态常量复用的异常信号（如 404/405），避免多线程下栈轨迹可变状态的安全问题。</p>
 */
public class StacklessResponseStatusException extends ResponseStatusException {

    public StacklessResponseStatusException(HttpStatus status) {
        super(status);
    }

    public StacklessResponseStatusException(HttpStatus status, String reason) {
        super(status, reason);
    }

    public StacklessResponseStatusException(HttpStatus status, String reason, Throwable cause) {
        super(status, reason, cause);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}