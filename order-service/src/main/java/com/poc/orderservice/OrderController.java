package com.poc.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    @Value("${spring.application.name:order-service}")
    private String appName;

    @Value("${user.service.url:http://user-service:8081}")
    private String userServiceUrl;

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    public OrderController() {
        // Seed data
        orders.put(1L, new Order(1L, 1L, "Laptop", 1, 1299.99, "DELIVERED"));
        orders.put(2L, new Order(2L, 2L, "Mouse", 2, 49.98, "PROCESSING"));
        orders.put(3L, new Order(3L, 1L, "Keyboard", 1, 79.99, "SHIPPED"));
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
    public ResponseEntity<List<Order>> getAllOrders() {
        String podName = System.getenv().getOrDefault("HOSTNAME", "local");
        logger.info("GET all orders called on pod: {}", podName);
        return ResponseEntity.ok(new ArrayList<>(orders.values()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        Order order = orders.get(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getOrdersByUserId(@PathVariable Long userId) {
        List<Order> userOrders = orders.values().stream()
            .filter(o -> o.getUserId().equals(userId))
            .toList();
        return ResponseEntity.ok(userOrders);
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        long newId = counter.incrementAndGet();
        order.setId(newId);
        order.setStatus("PENDING");
        orders.put(newId, order);
        logger.info("Created order for userId: {}, product: {}", order.getUserId(), order.getProduct());
        return ResponseEntity.ok(order);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        Order order = orders.get(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        order.setStatus(status);
        return ResponseEntity.ok(order);
    }

    /**
     * Calls user-service to get user details for an order (inter-service communication demo)
     */
    @GetMapping("/{id}/with-user")
    public ResponseEntity<Map<String, Object>> getOrderWithUser(@PathVariable Long id) {
        Order order = orders.get(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            WebClient client = WebClient.create(userServiceUrl);
            Map userDetails = client.get()
                .uri("/api/users/{userId}", order.getUserId())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
            return ResponseEntity.ok(Map.of("order", order, "user", userDetails));
        } catch (Exception e) {
            logger.warn("Could not fetch user details: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("order", order, "user", Map.of("error", "user-service unavailable")));
        }
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
