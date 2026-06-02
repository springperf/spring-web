package io.springperf.webtest;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
public class RequestObj<T> {


    @Valid
    private T data;

    @NotNull
    private Long tid;

    @NotEmpty
    private String code;
}
