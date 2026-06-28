package io.springperf.webtest;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ValidObj {

    @NotEmpty(message = "name must not be empty")
    private String name;

    @NotNull
    private Integer age;
}
