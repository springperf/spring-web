package io.springperf.webtest.bridge;

/**
 * DTO used by {@link CustomE2eReturnValueHandler} to demonstrate
 * that the bridged return value handler is invoked at runtime.
 */
public class CustomE2eReturnValueDto {

    private String message;

    public CustomE2eReturnValueDto() {
    }

    public CustomE2eReturnValueDto(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
