ALTER TABLE incidents ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
CREATE INDEX ix_incident_detection ON incidents(environment_id, source, status);
ALTER TABLE analysis_jobs ADD COLUMN detected_incident_id VARCHAR(36);
ALTER TABLE analysis_jobs ADD CONSTRAINT fk_job_detected_incident FOREIGN KEY (detected_incident_id) REFERENCES incidents(id);
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_job_detection_owner CHECK (detected_incident_id IS NULL OR automatic_environment_id IS NOT NULL);
CREATE INDEX ix_job_detected_history ON analysis_jobs(detected_incident_id,created_at,id);
