package com.sentrix.core.environment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "environments")
public class Environment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(nullable = false, length = 63)
    private String namespace;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Environment() {}

    public Environment(String name, String namespace) {
        this.name = name;
        this.namespace = namespace;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public Instant getCreatedAt() { return createdAt; }
}
