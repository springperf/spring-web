package io.springperf.benchmark.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public class UserReq {
    @NotEmpty
    private String name;
    @NotNull
    private Integer age;
    private String email;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}