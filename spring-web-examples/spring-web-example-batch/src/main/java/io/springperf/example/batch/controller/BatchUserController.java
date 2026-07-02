package io.springperf.example.batch.controller;

import io.springperf.example.batch.model.UserBody;
import io.springperf.example.batch.request.CreateUserRequest;
import io.springperf.example.batch.request.GetUserRequest;
import io.springperf.web.batch.annotation.BatchMapping;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/batch")
public class BatchUserController {

    /**
     * 单个用户创建入口
     * 返回 BatchRequest，框架自动聚合请求后调用 @BatchMapping 方法
     */
    @PostMapping("/users")
    public CreateUserRequest createUser(@RequestParam String name, @RequestBody UserBody body) {
        return null;
    }

    /**
     * 批量处理用户创建
     * 多个独立请求被聚合为 List 批量处理
     */
    @BatchMapping(method = "createUser")
    public void batchCreateUser(List<CreateUserRequest> requests) {
        for (CreateUserRequest req : requests) {
            String name = req.getName();
            UserBody body = req.getBody();
            req.setResult("created: name=" + name + ", age=" + (body != null ? body.getAge() : "null"));
        }
    }

    @GetMapping("/users/{id}")
    public GetUserRequest getUser(@PathVariable String id, @RequestParam(required = false) String fields) {
        return null;
    }

    @BatchMapping(method = "getUser")
    public void batchGetUser(List<GetUserRequest> requests) {
        for (GetUserRequest req : requests) {
            String id = req.getId();
            String fields = req.getFields();
            req.setResult("user:" + id + (fields != null ? "?fields=" + fields : ""));
        }
    }
}