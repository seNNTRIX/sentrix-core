CREATE TABLE monitoring_settings (
 environment_id BIGINT NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL,
 interval_seconds INT NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_monitoring_environment FOREIGN KEY(environment_id) REFERENCES environments(id)
);
