package io.springperf.webtest.jsp;

public class UserDto {

    private final String name;

    public UserDto(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
