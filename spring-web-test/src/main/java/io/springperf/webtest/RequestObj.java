package io.springperf.webtest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RequestObj<T> {


    @Valid
    private T data;

    @NotNull
    private Long tid;

    @NotEmpty
    private String code;
}
