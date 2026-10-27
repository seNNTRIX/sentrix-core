CREATE TABLE observation_data_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    environment_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    timeout_seconds INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_checked_at DATETIME(6),
    latency_ms BIGINT,
    http_status INT,
    failure_code VARCHAR(32),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_data_source_environment_type UNIQUE (environment_id, type),
    CONSTRAINT fk_data_source_environment FOREIGN KEY (environment_id) REFERENCES environments (id)
);
