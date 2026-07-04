package io.springperf.example.openapi.controller;

import io.springperf.example.openapi.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Tag(name = "User Management", description = "用户管理接口")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Operation(summary = "获取用户列表", description = "返回所有用户，支持按 name 模糊搜索")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "成功"))
    @GetMapping
    public Map<Long, User> list(@Parameter(description = "按名称搜索") @RequestParam(required = false) String name) {
        if (name == null) {
            return users;
        }
        ConcurrentHashMap<Long, User> filtered = new ConcurrentHashMap<>();
        users.forEach((id, user) -> {
            if (user.getName() != null && user.getName().contains(name)) {
                filtered.put(id, user);
            }
        });
        return filtered;
    }

    @Operation(summary = "获取单个用户")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    @GetMapping("/{id}")
    public User get(@Parameter(description = "用户ID") @PathVariable Long id) {
        User user = users.get(id);
        if (user == null) {
            throw new IllegalArgumentException("user not found, id=" + id);
        }
        return user;
    }

    @Operation(summary = "创建用户")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "创建成功"))
    @PostMapping
    public User create(@RequestBody User user) {
        user.setId(idSeq.getAndIncrement());
        users.put(user.getId(), user);
        return user;
    }

    @Operation(summary = "更新用户")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    @PutMapping("/{id}")
    public User update(@Parameter(description = "用户ID") @PathVariable Long id, @RequestBody User user) {
        if (!users.containsKey(id)) {
            throw new IllegalArgumentException("user not found, id=" + id);
        }
        user.setId(id);
        users.put(id, user);
        return user;
    }

    @Operation(summary = "删除用户")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "用户ID") @PathVariable Long id) {
        User removed = users.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("user not found, id=" + id);
        }
    }
}