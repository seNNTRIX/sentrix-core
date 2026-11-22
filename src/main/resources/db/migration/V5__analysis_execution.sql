ALTER TABLE analysis_jobs ADD COLUMN namespace VARCHAR(63);
ALTER TABLE analysis_jobs ADD COLUMN window_start DATETIME(6);
ALTER TABLE analysis_jobs ADD COLUMN window_end DATETIME(6);
ALTER TABLE analysis_jobs ADD COLUMN ready_at DATETIME(6);
ALTER TABLE analysis_jobs ADD COLUMN contract_version VARCHAR(100);
ALTER TABLE analysis_jobs ADD COLUMN feature_schema_version VARCHAR(100);
ALTER TABLE analysis_jobs ADD COLUMN model_version VARCHAR(100);
ALTER TABLE analysis_jobs ADD COLUMN request_json LONGTEXT;
ALTER TABLE analysis_jobs ADD COLUMN result_json LONGTEXT;
ALTER TABLE analysis_jobs ADD COLUMN started_at DATETIME(6);
ALTER TABLE analysis_jobs ADD COLUMN finished_at DATETIME(6);
ALTER TABLE analysis_jobs ADD COLUMN claim_token VARCHAR(36);
ALTER TABLE analysis_jobs ADD COLUMN lease_expires_at DATETIME(6);

CREATE INDEX ix_analysis_dispatch ON analysis_jobs(status,ready_at,created_at);
CREATE INDEX ix_analysis_lease ON analysis_jobs(status,lease_expires_at);
