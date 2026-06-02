package io.springperf.webtest;

import lombok.Data;

@Data
public class RequestObj<T> {

    private T data;

    private Long tid;

    private String code;
}
