package com.poc.userservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    @Value("${spring.application.name:user-service}")
    private String appName;

    // In-memory store for POC purposes
    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    public UserController() {
        // Seed data
        users.put(1L, new User(1L, "Durai SK", "durai@valignit.com", "ADMIN"));
        users.put(2L, new User(2L, "Fahath", "fahath@valignit.com", "USER"));
        users.put(3L, new User(3L, "Seema", "seema@valignit.com", "USER"));
        counter.set(3L);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String podName = System.getenv().getOrDefault("HOSTNAME", "local");
        logger.info("Health check called on pod: {}", podName);
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", appName,
            "pod", podName
        ));
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        String podName = System.getenv().getOrDefault("HOSTNAME", "local");
        logger.info("GET all users called on pod: {}", podName);
        return ResponseEntity.ok(new ArrayList<>(users.values()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        User user = users.get(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(user);
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user) {
        long newId = counter.incrementAndGet();
        user.setId(newId);
        users.put(newId, user);
        logger.info("Created user: {}", user.getName());
        return ResponseEntity.ok(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User user) {
        if (!users.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        user.setId(id);
        users.put(id, user);
        return ResponseEntity.ok(user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        if (!users.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        users.remove(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Simulate CPU load for HPA testing
     */
    @GetMapping("/simulate-load")
    public ResponseEntity<Map<String, Object>> simulateLoad(
            @RequestParam(defaultValue = "1000") int iterations) {
        logger.info("Simulating CPU load with {} iterations", iterations);
        long start = System.currentTimeMillis();
        double result = 0;
        for (int i = 0; i < iterations * 10000; i++) {
            result += Math.sqrt(i) * Math.log(i + 1);
        }
        long duration = System.currentTimeMillis() - start;
        return ResponseEntity.ok(Map.of(
            "iterations", iterations,
            "durationMs", duration,
            "pod", System.getenv().getOrDefault("HOSTNAME", "local"),
            "result", result
        ));
    }
}
