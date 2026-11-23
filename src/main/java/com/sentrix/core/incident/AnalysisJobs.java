package com.sentrix.core.incident;

import com.sentrix.core.analysis.AnalysisModelSettings;
import com.sentrix.core.analysis.AnalysisProtocol;
import com.sentrix.core.datasource.PrometheusClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisJobs {
    private static final String JOIN = "SELECT j.*, i.id AS linked_incident_id, i.environment_id FROM analysis_jobs j JOIN incidents i ON i.id=COALESCE(j.incident_id,j.detected_incident_id)";
    private static final String ACTIVE = "('BLOCKED_MODEL_UNAVAILABLE','QUEUED','RUNNING')";
    private final JdbcTemplate db;
    private final AnalysisModelSettings settings;
    private final ObjectMapper mapper;

    AnalysisJobs(JdbcTemplate db, AnalysisModelSettings settings, ObjectMapper mapper) {
        this.db = db; this.settings = settings; this.mapper = mapper;
    }

    public record Job(String id, String incidentId, Long environmentId, String status, String reason,
                      Instant createdAt, Instant updatedAt, String namespace, Instant windowStart,
                      Instant windowEnd, Instant readyAt, String contractVersion, String featureSchemaVersion,
                      String modelVersion, Instant startedAt, Instant finishedAt) {}
    public record JobPage(List<Job> items, int page, int size, long totalElements, long totalPages) {}
    private record Pending(String id, String incidentId, long environmentId) {}
    public record Claim(String jobId, String token, String requestJson) {}

    private static Instant time(ResultSet r, String column) throws SQLException {
        var value = r.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private Job row(ResultSet r, int n) throws SQLException {
        return new Job(r.getString("id"), r.getString("linked_incident_id"), r.getLong("environment_id"),
                r.getString("status"), r.getString("reason"), time(r,"created_at"), time(r,"updated_at"),
                r.getString("namespace"), time(r,"window_start"), time(r,"window_end"), time(r,"ready_at"),
                r.getString("contract_version"), r.getString("feature_schema_version"), r.getString("model_version"),
                time(r,"started_at"), time(r,"finished_at"));
    }
    private static org.springframework.web.server.ResponseStatusException error(HttpStatus status, String code) {
        return PrometheusClient.error(status,code,code);
    }
    private record Incident(String status, Instant occurredAt) {}
    private Incident incident(Long environmentId, UUID incidentId, boolean lock) {
        var rows = db.query("SELECT status,occurred_at FROM incidents WHERE environment_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (r,n) -> new Incident(r.getString(1),r.getTimestamp(2).toInstant()), environmentId,incidentId.toString());
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"INCIDENT_NOT_FOUND");
        return rows.getFirst();
    }

    @Transactional
    public Job create(Long environmentId, UUID incidentId, UUID requestKey, Instant requestedEnd, boolean latest) {
        var incident = incident(environmentId,incidentId,true);
        if (latest && requestedEnd != null) throw error(HttpStatus.BAD_REQUEST,"INVALID_ANALYSIS_WINDOW");
        if (requestedEnd != null && (requestedEnd.getNano() != 0 || requestedEnd.getEpochSecond() % 10 != 0 || requestedEnd.isAfter(Instant.now()) || requestedEnd.isBefore(Instant.parse("1970-01-01T00:00:30Z"))))
            throw error(HttpStatus.BAD_REQUEST,"INVALID_ANALYSIS_WINDOW");
        var prior = db.query(JOIN + " WHERE j.incident_id=? AND j.request_key=?",this::row,incidentId.toString(),requestKey.toString());
        if (!prior.isEmpty()) {
            var job = prior.getFirst();
            if (requestedEnd != null && !requestedEnd.equals(job.windowEnd())) throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
            return job;
        }
        if (incident.status().equals("RESOLVED")) throw error(HttpStatus.CONFLICT,"INCIDENT_RESOLVED");
        if (db.queryForObject("SELECT COUNT(*) FROM analysis_jobs WHERE incident_id=? AND status IN " + ACTIVE, Long.class,incidentId.toString()) > 0)
            throw error(HttpStatus.CONFLICT,"ACTIVE_ANALYSIS_JOB_EXISTS");
        // Resolve a relative window only after looking up the idempotency key.
        if (latest) requestedEnd = Instant.ofEpochSecond(Math.floorDiv(Instant.now().getEpochSecond(),10)*10);
        Instant end = requestedEnd != null ? requestedEnd : Instant.ofEpochSecond(Math.floorDiv(incident.occurredAt().getEpochSecond(),30)*30+30);
        if (end.isBefore(Instant.parse("1970-01-01T00:00:30Z"))) throw error(HttpStatus.BAD_REQUEST,"INVALID_ANALYSIS_WINDOW");
        Instant start = end.minusSeconds(30), ready = end.plusSeconds(30), now = Instant.now();
        String namespace = db.queryForObject("SELECT namespace FROM environments WHERE id=?",String.class,environmentId);
        UUID id = insert(environmentId, incidentId, requestKey, namespace, start, end, ready, now);
        return get(environmentId,incidentId,id);
    }

    private UUID insert(long environmentId, UUID incidentId, UUID key, String namespace, Instant start,
                        Instant end, Instant ready, Instant now) {
        UUID id = UUID.randomUUID();
        var request = new AnalysisProtocol.Request(AnalysisProtocol.CONTRACT_VERSION,id,environmentId,namespace,start,end,
                settings.enabled() ? settings.featureSchemaVersion() : null,settings.enabled() ? settings.modelVersion() : null);
        db.update("""
                INSERT INTO analysis_jobs(id,incident_id,automatic_environment_id,request_key,status,reason,created_at,updated_at,namespace,
                    window_start,window_end,ready_at,contract_version,feature_schema_version,model_version,request_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id.toString(),incidentId == null ? null : incidentId.toString(),incidentId == null ? environmentId : null,key.toString(),
                settings.enabled()?"QUEUED":"BLOCKED_MODEL_UNAVAILABLE",settings.enabled()?"AWAITING_EXECUTION":"MODEL_UNAVAILABLE",
                Timestamp.from(now),Timestamp.from(now),namespace,Timestamp.from(start),Timestamp.from(end),Timestamp.from(ready),
                request.contractVersion(),request.featureSchemaVersion(),request.modelVersion(),mapper.writeValueAsString(request));
        return id;
    }

    @Transactional
    public void enqueueDue(Instant now) {
        if (!settings.enabled()) return;
        var due = db.queryForList("""
                SELECT environment_id FROM monitoring_settings
                WHERE enabled=true AND next_run_at<=? ORDER BY next_run_at,environment_id LIMIT 16
                """,Long.class,Timestamp.from(now));
        due.sort(Long::compareTo);
        Instant end = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond()-30,10)*10);
        for (long env : due) {
            // Use the same environment-first lock order as settings updates and recheck eligibility.
            String namespace = db.queryForObject("SELECT namespace FROM environments WHERE id=? FOR UPDATE",String.class,env);
            var intervals = db.queryForList("""
                    SELECT interval_seconds FROM monitoring_settings
                    WHERE environment_id=? AND enabled=true AND next_run_at<=? FOR UPDATE
                    """,Integer.class,env,Timestamp.from(now));
            if (intervals.isEmpty()) continue;
            db.update("UPDATE monitoring_settings SET next_run_at=? WHERE environment_id=?",
                    Timestamp.from(now.plusSeconds(intervals.getFirst())),env);
            // After downtime, analyze the newest mature window and leave gaps visible.
            var previous = db.queryForList("""
                    SELECT id FROM analysis_jobs WHERE automatic_environment_id=?
                    AND (window_end>=? OR status IN ('QUEUED','RUNNING')) LIMIT 1 FOR UPDATE
                    """,String.class,env,Timestamp.from(end));
            if (previous.isEmpty()) {
                insert(env,null,UUID.randomUUID(),namespace,end.minusSeconds(30),end,end.plusSeconds(30),now);
            }
        }
    }

    public Job get(Long environmentId, UUID incidentId, UUID jobId) {
        var rows = db.query(JOIN + " WHERE i.environment_id=? AND i.id=? AND j.id=?",this::row,environmentId,incidentId.toString(),jobId.toString());
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"ANALYSIS_JOB_NOT_FOUND");
        return rows.getFirst();
    }
    public JobPage list(Long environmentId, UUID incidentId, int page, int size) {
        incident(environmentId,incidentId,false);
        long count = db.queryForObject("SELECT COUNT(*) FROM analysis_jobs WHERE incident_id=? OR detected_incident_id=?",Long.class,incidentId.toString(),incidentId.toString());
        var rows = db.query(JOIN + " WHERE i.id=? ORDER BY j.created_at DESC,j.id DESC LIMIT ? OFFSET ?",this::row,incidentId.toString(),size,(long)page*size);
        return new JobPage(rows,page,size,count,(count+size-1)/size);
    }
    @Transactional
    public Job cancel(Long environmentId, UUID incidentId, UUID jobId) {
        incident(environmentId,incidentId,true); get(environmentId,incidentId,jobId);
        var now = Timestamp.from(Instant.now());
        db.update("UPDATE analysis_jobs SET status='CANCELLED',reason='USER_CANCELLED',updated_at=?,finished_at=?,claim_token=NULL,lease_expires_at=NULL WHERE id=? AND status IN " + ACTIVE,
                now,now,jobId.toString());
        return get(environmentId,incidentId,jobId);
    }
    public void cancelForIncident(UUID incidentId, Instant instant) {
        var now = Timestamp.from(instant);
        db.update("UPDATE analysis_jobs SET status='CANCELLED',reason='INCIDENT_RESOLVED',updated_at=?,finished_at=?,claim_token=NULL,lease_expires_at=NULL WHERE incident_id=? AND status IN " + ACTIVE,
                now,now,incidentId.toString());
    }
    public AnalysisProtocol.Response result(Long environmentId, UUID incidentId, UUID jobId) {
        var job = get(environmentId,incidentId,jobId);
        if (!job.status().equals("SUCCEEDED")) throw error(HttpStatus.CONFLICT,"ANALYSIS_RESULT_NOT_AVAILABLE");
        String json = db.queryForObject("SELECT result_json FROM analysis_jobs WHERE id=?",String.class,jobId.toString());
        return mapper.readValue(json,AnalysisProtocol.Response.class);
    }

    @Transactional
    public Claim claimNext() {
        var now = Timestamp.from(Instant.now());
        var candidates = db.query("""
                SELECT j.id,j.incident_id,i.environment_id FROM analysis_jobs j LEFT JOIN incidents i ON i.id=j.incident_id
                WHERE j.status='QUEUED' AND j.ready_at<=? ORDER BY j.created_at,j.id LIMIT 1
                """,(r,n)->new Pending(r.getString(1),r.getString(2),r.getLong(3)),now);
        if (candidates.isEmpty()) return null;
        var job = candidates.getFirst();
        if (job.incidentId()!=null) {
            UUID incidentId = UUID.fromString(job.incidentId());
            if (incident(job.environmentId(),incidentId,true).status().equals("RESOLVED")) {
                cancelForIncident(incidentId,now.toInstant());
                return null;
            }
        }
        now = Timestamp.from(Instant.now());
        String token = UUID.randomUUID().toString();
        var expiry = Timestamp.from(now.toInstant().plusSeconds(settings.timeoutSeconds()+30));
        int changed = db.update("UPDATE analysis_jobs SET status='RUNNING',reason='EXECUTING',started_at=?,updated_at=?,claim_token=?,lease_expires_at=? WHERE id=? AND status='QUEUED'",now,now,token,expiry,job.id());
        if (changed == 0) return null;
        return new Claim(job.id(),token,db.queryForObject("SELECT request_json FROM analysis_jobs WHERE id=?",String.class,job.id()));
    }
    public void recoverExpired() {
        var now = Timestamp.from(Instant.now());
        db.update("UPDATE analysis_jobs SET status='FAILED',reason='WORKER_INTERRUPTED',updated_at=?,finished_at=?,claim_token=NULL,lease_expires_at=NULL WHERE status='RUNNING' AND lease_expires_at<=?",now,now,now);
    }
    public void blockQueued() {
        db.update("UPDATE analysis_jobs SET status='BLOCKED_MODEL_UNAVAILABLE',reason='MODEL_UNAVAILABLE',updated_at=? WHERE status='QUEUED'",Timestamp.from(Instant.now()));
    }
    @Transactional
    public void finish(Claim claim, AnalysisProtocol.Response result, String failureCode) {
        Long environment = null;
        if (result != null && result.decision() != AnalysisProtocol.Decision.ABSTAIN) {
            environment = db.queryForObject("SELECT automatic_environment_id FROM analysis_jobs WHERE id=?", Long.class, claim.jobId());
            if (environment != null) {
                db.queryForObject("SELECT id FROM environments WHERE id=? FOR UPDATE", Long.class, environment);
            }
        }
        // Refresh the clock after acquiring the lock; database lock waits can outlive a claim.
        var leases = db.query("SELECT lease_expires_at FROM analysis_jobs WHERE id=? AND status='RUNNING' AND claim_token=? FOR UPDATE",
                (r,n) -> r.getTimestamp(1),claim.jobId(),claim.token());
        if (leases.isEmpty()) return;
        var now = Timestamp.from(Instant.now());
        if (leases.getFirst() == null || !leases.getFirst().after(now)) {
            db.update("UPDATE analysis_jobs SET status='FAILED',reason='WORKER_INTERRUPTED',updated_at=?,finished_at=?,claim_token=NULL,lease_expires_at=NULL WHERE id=? AND status='RUNNING' AND claim_token=?",now,now,claim.jobId(),claim.token());
            return;
        }
        // A cancelled or expired claim cannot publish a late model response.
        int published = db.update("UPDATE analysis_jobs SET status=?,reason=?,result_json=?,updated_at=?,finished_at=?,claim_token=NULL,lease_expires_at=NULL WHERE id=? AND status='RUNNING' AND claim_token=? AND lease_expires_at>?",
                result == null ? "FAILED" : "SUCCEEDED",result == null ? failureCode : "COMPLETED",
                result == null ? null : mapper.writeValueAsString(result),now,now,claim.jobId(),claim.token(),now);
        if (published == 1 && environment != null) {
            recordDetection(environment, result, now);
        }
    }

    private void recordDetection(long environment, AnalysisProtocol.Response result, Timestamp now) {
        // The scheduler permits one active automatic job per environment. The job lock is held until commit.
        var open = db.queryForList("SELECT id FROM incidents WHERE environment_id=? AND source='AUTOMATIC' AND status='OPEN' FOR UPDATE",
                String.class, environment);
        String incidentId;
        var observedAt = Timestamp.from(result.windowEnd());
        if (open.isEmpty()) {
            if (result.decision() == AnalysisProtocol.Decision.NORMAL) return;
            incidentId = UUID.randomUUID().toString();
            var first = result.candidates().getFirst();
            boolean edge = first.kind() == AnalysisProtocol.CandidateKind.EDGE;
            String service = edge ? first.source() : first.service();
            String candidate = edge ? first.source() + " → " + first.target() : service;
            db.update("""
                    INSERT INTO incidents(id,environment_id,request_key,service,title,description,occurred_at,created_at,status,source)
                    VALUES(?,?,?,?,?,?,?,?,'OPEN','AUTOMATIC')
                    """, incidentId, environment, UUID.randomUUID().toString(), service, "모델 이상 감지",
                    "최초 판정의 원인 후보 1순위 · " + candidate, observedAt, now);
        } else {
            incidentId = open.getFirst();
            if (result.decision() == AnalysisProtocol.Decision.NORMAL) {
                db.update("UPDATE incidents SET status='RESOLVED',resolved_at=? WHERE id=?", observedAt, incidentId);
                cancelForIncident(UUID.fromString(incidentId), now.toInstant());
            }
        }
        db.update("UPDATE analysis_jobs SET detected_incident_id=? WHERE id=?", incidentId, result.jobId().toString());
    }
}
