package io.springperf.webtest;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
public class ValidObj {

    @NotEmpty(message = "name must not be empty")
    private String name;

    @NotNull
    private Integer age;
}
