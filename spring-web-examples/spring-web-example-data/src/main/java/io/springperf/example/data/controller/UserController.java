package io.springperf.example.data.controller;

import io.springperf.example.data.model.User;
import io.springperf.example.data.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    @GetMapping
    public List<User> list(@RequestParam(required = false) String name) {
        if (name != null) {
            return userRepository.findByNameContaining(name);
        }
        return userRepository.findAll();
    }

    @GetMapping("/{id}")
    public User get(@PathVariable Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("user not found, id=" + id));
    }

    @PostMapping
    public User create(@Valid @RequestBody User user) {
        User saved = userRepository.save(user);
        log.info("created user: {}", saved);
        return saved;
    }

    @PutMapping("/{id}")
    public User update(@PathVariable Long id, @Valid @RequestBody User user) {
        userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("user not found, id=" + id));
        user.setId(id);
        User saved = userRepository.save(user);
        log.info("updated user: {}", saved);
        return saved;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            throw new IllegalArgumentException("user not found, id=" + id);
        }
        userRepository.deleteById(id);
        log.info("deleted user: id={}", id);
    }
}