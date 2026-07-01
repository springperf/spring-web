package io.springperf.webtest.batch;

public class UserBody {
    public String name;
    public int age;

    public UserBody() {
    }

    public UserBody(String name, int age) {
        this.name = name;
        this.age = age;
    }

    @Override
    public String toString() {
        return "UserBody{name='" + name + "', age=" + age + "}";
    }
}