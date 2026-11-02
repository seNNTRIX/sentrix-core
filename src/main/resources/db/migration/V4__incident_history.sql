CREATE TABLE incidents (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 environment_id BIGINT NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 service VARCHAR(253) NOT NULL,
 title VARCHAR(200) NOT NULL,
 description VARCHAR(2000) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 status VARCHAR(16) NOT NULL,
 resolved_at DATETIME(6),
 CONSTRAINT fk_incident_environment FOREIGN KEY(environment_id) REFERENCES environments(id),
 CONSTRAINT uk_incident_request UNIQUE(environment_id,request_key),
 INDEX ix_incident_history(environment_id,occurred_at,id)
);
CREATE TABLE analysis_jobs (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 incident_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(36) NOT NULL,
 status VARCHAR(40) NOT NULL,
 reason VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_job_incident FOREIGN KEY(incident_id) REFERENCES incidents(id),
 CONSTRAINT uk_job_request UNIQUE(incident_id,request_key),
 INDEX ix_job_history(incident_id,created_at,id)
);
