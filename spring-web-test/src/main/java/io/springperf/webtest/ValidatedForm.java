package io.springperf.webtest;

import jakarta.validation.constraints.NotBlank;

public class ValidatedForm {

    @NotBlank(message = "name is required")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}