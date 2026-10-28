package com.sentrix.core.datasource;

import com.sentrix.core.environment.EnvironmentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/environments/{environmentId}/data-sources")
class DataSourceController {
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceTargetPolicy policy;
    private final DataSourceProbe probe;

    DataSourceController(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                         DataSourceTargetPolicy policy, DataSourceProbe probe) {
        this.environments = environments;
        this.sources = sources;
        this.policy = policy;
        this.probe = probe;
    }

    @GetMapping
    List<DataSourceResponse> list(@PathVariable Long environmentId) {
        requireEnvironment(environmentId);
        return sources.findByEnvironmentIdOrderByTypeAsc(environmentId).stream()
                .map(DataSourceResponse::from).toList();
    }

    @PutMapping("/{type}")
    DataSourceResponse configure(@PathVariable Long environmentId, @PathVariable String type,
                                 @Valid @RequestBody ConfigureDataSource request) {
        var sourceType = DataSourceType.parse(type);
        requireEnvironment(environmentId);
        String url = policy.validate(request.baseUrl());
        var source = sources.findByEnvironmentIdAndType(environmentId, sourceType)
                .orElseGet(() -> new ObservationDataSource(environmentId, sourceType));
        source.configure(request.name(), url, request.timeoutSeconds());
        return persist(source);
    }

    @PostMapping("/{type}/test")
    DataSourceResponse test(@PathVariable Long environmentId, @PathVariable String type) {
        var sourceType = DataSourceType.parse(type);
        requireEnvironment(environmentId);
        var source = sources.findByEnvironmentIdAndType(environmentId, sourceType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Data source is not configured"));
        // No DB transaction is held while waiting on the remote server.
        source.checked(probe.check(source));
        return persist(source);
    }

    private DataSourceResponse persist(ObservationDataSource source) {
        try {
            return DataSourceResponse.from(sources.saveAndFlush(source));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Data source changed concurrently; reload and retry");
        }
    }

    private void requireEnvironment(Long id) {
        if (!environments.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found");
        }
    }

    record ConfigureDataSource(@NotBlank @Size(max = 100) String name,
                               @NotBlank @Size(max = 2048) String baseUrl,
                               @NotNull @Min(1) @Max(10) Integer timeoutSeconds) {
        ConfigureDataSource {
            name = name == null ? null : name.strip();
            baseUrl = baseUrl == null ? null : baseUrl.strip();
        }
    }

    record DataSourceResponse(Long id, Long environmentId, String type, String name, String baseUrl,
                              int timeoutSeconds, String status, Instant lastCheckedAt, Long latencyMs,
                              Integer httpStatus, String failureCode, Instant createdAt, Instant updatedAt) {
        static DataSourceResponse from(ObservationDataSource s) {
            return new DataSourceResponse(s.id, s.environmentId, s.type.name().toLowerCase(Locale.ROOT), s.name,
                    s.baseUrl, s.timeoutSeconds, s.status, s.lastCheckedAt, s.latencyMs, s.httpStatus,
                    s.failureCode, s.createdAt, s.updatedAt);
        }
    }
}
