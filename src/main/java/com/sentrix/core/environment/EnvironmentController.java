package com.sentrix.core.environment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {
    private final EnvironmentRepository repository;

    public EnvironmentController(EnvironmentRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<EnvironmentResponse> create(@Valid @RequestBody CreateEnvironment request) {
        Environment environment;
        try {
            environment = repository.saveAndFlush(new Environment(request.name(), request.namespace()));
        } catch (DataIntegrityViolationException exception) {
            // The database constraint also protects against concurrent registrations.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment name already exists");
        }
        return ResponseEntity.created(URI.create("/api/v1/environments/" + environment.getId()))
                .body(EnvironmentResponse.from(environment));
    }

    @GetMapping
    public EnvironmentPage list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        var result = repository.findAll(PageRequest.of(page, size, Sort.by("id").ascending()));
        return new EnvironmentPage(result.getContent().stream().map(EnvironmentResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    public EnvironmentResponse detail(@PathVariable Long id) {
        return repository.findById(id).map(EnvironmentResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));
    }

    public record CreateEnvironment(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 63)
            @Pattern(regexp = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?",
                    message = "must be a Kubernetes namespace using lowercase letters, digits and hyphens")
            String namespace) {
        public CreateEnvironment {
            name = name == null ? null : name.strip();
        }
    }

    public record EnvironmentResponse(Long id, String name, String namespace, Instant createdAt) {
        static EnvironmentResponse from(Environment environment) {
            return new EnvironmentResponse(environment.getId(), environment.getName(),
                    environment.getNamespace(), environment.getCreatedAt());
        }
    }

    public record EnvironmentPage(List<EnvironmentResponse> items, int page, int size,
                                  long totalElements, int totalPages) {}
}
