package io.springperf.example.rest.controller;

import io.springperf.example.rest.exception.ApiResult;
import io.springperf.example.rest.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @GetMapping
    public ApiResult<Map<Long, User>> list() {
        return ApiResult.success(users);
    }

    @GetMapping("/{id}")
    public ApiResult<User> get(@PathVariable Long id) {
        User user = users.get(id);
        if (user == null) {
            return ApiResult.error(404, "user not found");
        }
        return ApiResult.success(user);
    }

    @PostMapping
    public ApiResult<User> create(@Valid @RequestBody User user) {
        user.setId(idSeq.getAndIncrement());
        users.put(user.getId(), user);
        log.info("created user: {}", user);
        return ApiResult.success(user);
    }

    @PutMapping("/{id}")
    public ApiResult<User> update(@PathVariable Long id, @Valid @RequestBody User user) {
        if (!users.containsKey(id)) {
            return ApiResult.error(404, "user not found");
        }
        user.setId(id);
        users.put(id, user);
        log.info("updated user: {}", user);
        return ApiResult.success(user);
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        User removed = users.remove(id);
        if (removed == null) {
            return ApiResult.error(404, "user not found");
        }
        log.info("deleted user: {}", removed);
        return ApiResult.success(null);
    }
}