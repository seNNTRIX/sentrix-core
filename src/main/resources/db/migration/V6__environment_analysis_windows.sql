ALTER TABLE monitoring_settings ADD COLUMN next_run_at DATETIME(6);
CREATE INDEX ix_monitoring_due ON monitoring_settings(enabled,next_run_at);
ALTER TABLE analysis_jobs MODIFY COLUMN incident_id VARCHAR(36) NULL;
ALTER TABLE analysis_jobs ADD COLUMN automatic_environment_id BIGINT;
ALTER TABLE analysis_jobs ADD CONSTRAINT fk_automatic_environment FOREIGN KEY (automatic_environment_id) REFERENCES environments(id);
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_owner CHECK ((incident_id IS NULL) <> (automatic_environment_id IS NULL));
CREATE UNIQUE INDEX uk_automatic_window ON analysis_jobs(automatic_environment_id,window_end);
