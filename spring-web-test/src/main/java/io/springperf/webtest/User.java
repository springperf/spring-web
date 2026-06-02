package io.springperf.webtest;

import lombok.Data;

import java.util.List;

@Data
public class User {

    private String name;

    private int age;

    private List<String> ids;
}
