package io.springperf.webtest.bridge;

/**
 * Custom exception handled by {@link CustomE2eExceptionResolver}.
 * This exception type is intentionally NOT declared in any {@code @ExceptionHandler}
 * method of {@code @ControllerAdvice} classes, so the bridged exception
 * resolver is the only handler that can process it.
 */
public class CustomE2eBridgeException extends RuntimeException {

    public CustomE2eBridgeException() {
        super("e2e-bridge-exception");
    }
}
