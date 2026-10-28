package com.sentrix.core.datasource;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "observation_data_sources")
class ObservationDataSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false) Long environmentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    DataSourceType type;
    @Column(nullable = false, length = 100) String name;
    @Column(nullable = false, length = 2048) String baseUrl;
    @Column(nullable = false) int timeoutSeconds;
    @Column(nullable = false, length = 16) String status;
    Instant lastCheckedAt;
    Long latencyMs;
    Integer httpStatus;
    @Column(length = 32) String failureCode;
    @Column(nullable = false, updatable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
    @Version Long version;

    protected ObservationDataSource() {}

    ObservationDataSource(Long environmentId, DataSourceType type) {
        this.environmentId = environmentId;
        this.type = type;
        this.createdAt = Instant.now();
    }

    void configure(String name, String url, int timeout) {
        // An identical PUT preserves the last check; changed connection settings invalidate it.
        if (!url.equals(baseUrl) || timeout != timeoutSeconds) {
            status = "NOT_TESTED";
            lastCheckedAt = null;
            latencyMs = null;
            httpStatus = null;
            failureCode = null;
        }
        this.name = name;
        this.baseUrl = url;
        this.timeoutSeconds = timeout;
        this.updatedAt = Instant.now();
    }

    void checked(DataSourceProbe.Result result) {
        status = result.failureCode() == null ? "CONNECTED" : "FAILED";
        lastCheckedAt = Instant.now();
        latencyMs = result.latencyMs();
        httpStatus = result.httpStatus();
        failureCode = result.failureCode();
        updatedAt = lastCheckedAt;
    }
}
