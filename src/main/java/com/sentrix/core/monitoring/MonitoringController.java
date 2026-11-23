package com.sentrix.core.monitoring;

import com.sentrix.core.analysis.AnalysisModelSettings;
import com.sentrix.core.analysis.AnalysisProtocol;
import com.sentrix.core.environment.EnvironmentRepository;
import com.sentrix.core.datasource.PrometheusClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Tag(name="06. 분석 실행 설정")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
class MonitoringController {
    private final EnvironmentRepository environments;
    private final JdbcTemplate db;
    private final AnalysisModelSettings analysis;
    private final ObjectMapper mapper;
    private final boolean workerEnabled;

    MonitoringController(EnvironmentRepository environments, JdbcTemplate db, AnalysisModelSettings analysis,
                         ObjectMapper mapper, @Value("${sentrix.analysis.worker-enabled:true}") boolean workerEnabled) {
        this.environments=environments;
        this.db=db;
        this.analysis=analysis;
        this.mapper=mapper;
        this.workerEnabled=workerEnabled;
    }

    @Operation(summary="환경 자동 분석 설정과 마지막 처리 시각 조회")
    @GetMapping("/monitoring")
    State get(@PathVariable Long environmentId){
        check(environmentId);
        var rows=db.query("SELECT enabled,interval_seconds,updated_at,next_run_at FROM monitoring_settings WHERE environment_id=?",
                (r,n)->new Configuration(r.getBoolean(1),r.getInt(2),instant(r.getTimestamp(3)),instant(r.getTimestamp(4))),environmentId);
        var config=rows.isEmpty()?new Configuration(false,60,null,null):rows.getFirst();
        var finished=db.queryForObject("SELECT MAX(finished_at) FROM analysis_jobs WHERE automatic_environment_id=? AND status='SUCCEEDED'",
                Timestamp.class,environmentId);
        String status="DISABLED";
        if(config.enabled()){
            if(!analysis.enabled()) status="BLOCKED_MODEL_UNAVAILABLE";
            else if(!workerEnabled) status="BLOCKED_AUTOMATIC_ANALYSIS_UNAVAILABLE";
            else status=config.nextRunAt()==null?"ACTIVATION_REQUIRED":"SCHEDULED";
        }
        return new State(environmentId,config.enabled(),config.interval(),status,analysis.enabled()?"CONFIGURED":"UNAVAILABLE",
                instant(finished),config.updated(),config.nextRunAt());
    }

    @Operation(summary="환경 자동 분석 시작·중지",description="30~3600초 주기입니다. 중지하면 진행 중인 자동 작업의 늦은 결과도 저장하지 않습니다.")
    @PutMapping("/monitoring")
    @Transactional
    State put(@PathVariable Long environmentId,@Valid @RequestBody Settings input){
        check(environmentId);
        db.queryForObject("SELECT id FROM environments WHERE id=? FOR UPDATE",Long.class,environmentId);
        var now=Timestamp.from(Instant.now());
        var next=input.enabled()?now:null;
        int updated=db.update("""
                UPDATE monitoring_settings SET next_run_at=CASE WHEN ?=false THEN NULL
                    WHEN interval_seconds<>? OR next_run_at IS NULL THEN ? ELSE next_run_at END,
                    enabled=?, interval_seconds=?, updated_at=? WHERE environment_id=?
                """,input.enabled(),input.intervalSeconds(),next,input.enabled(),input.intervalSeconds(),now,environmentId);
        if(updated==0)
            db.update("INSERT INTO monitoring_settings(environment_id,enabled,interval_seconds,updated_at,next_run_at) VALUES(?,?,?,?,?)",
                    environmentId,input.enabled(),input.intervalSeconds(),now,next);
        if(!input.enabled())
            db.update("""
                    UPDATE analysis_jobs SET status='CANCELLED',reason='MONITORING_DISABLED',finished_at=?,updated_at=?,
                    claim_token=NULL,lease_expires_at=NULL WHERE automatic_environment_id=? AND status IN ('QUEUED','RUNNING')
                    """,now,now,environmentId);
        return get(environmentId);
    }

    @Operation(summary="자동 분석 구간과 모델 점수 조회",description="최신 구간부터 반환합니다. 점수가 없으면 null이며 보류·실패 사유를 함께 제공합니다. queueWaitMs는 접수부터 실행 시작까지, executionMs는 실행 시작부터 종료까지입니다.")
    @GetMapping("/anomaly-scores")
    List<Window> windows(@PathVariable Long environmentId,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){
        check(environmentId);
        return readWindows("ORDER BY window_end DESC LIMIT ?", environmentId, limit);
    }

    private List<Window> readWindows(String criteria, Object... args) {
        return db.query("""
                SELECT id,detected_incident_id,window_start,window_end,status,reason,result_json,created_at,started_at,finished_at,model_version
                FROM analysis_jobs WHERE automatic_environment_id=?
                """ + criteria,(r,n)->{
            var json=r.getString("result_json");
            var result=json==null?null:mapper.readValue(json,AnalysisProtocol.Response.class);
            Instant created=instant(r.getTimestamp("created_at")),started=instant(r.getTimestamp("started_at")),finished=instant(r.getTimestamp("finished_at"));
            String reason=result!=null && result.abstainReason()!=null?result.abstainReason():r.getString("reason");
            return new Window(r.getString("id"),instant(r.getTimestamp("window_start")),instant(r.getTimestamp("window_end")),
                    r.getString("status"),reason,result==null?null:result.score(),result==null?null:result.decision(),
                    r.getString("model_version"),created,started,finished,elapsed(created,started),elapsed(started,finished),r.getString("detected_incident_id"),
                    result == null || result.candidates() == null ? List.of() : result.candidates().stream()
                            .limit(3).map(c -> new Location(c.rank(), c.kind(), c.service(), c.source(), c.target())).toList());
        },args);
    }

    @Operation(summary="환경 장애와 최근 자동 분석 요약",description="from·to를 함께 지정하면 최대 24시간 동안 겹치는 모든 분석 구간을 반환합니다. 생략하면 최근 구간을 반환합니다. 서비스별 RED 지표는 metrics API에서 선택한 기간으로 조회합니다.")
    @GetMapping("/overview")
    @Transactional(readOnly=true)
    Overview overview(@PathVariable Long environmentId, @RequestParam(defaultValue="12") @Min(1) @Max(100) int limit,
                      @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to) {
        if ((from == null) != (to == null) || from != null &&
                (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(1)) > 0))
            throw PrometheusClient.error(HttpStatus.BAD_REQUEST,"INVALID_TIME_RANGE","Provide a range of up to 24 hours");
        check(environmentId);
        var recent = from == null
                ? readWindows("ORDER BY window_end DESC LIMIT ?", environmentId, limit)
                : readWindows("AND window_end > ? AND window_start < ? ORDER BY window_end DESC",
                        environmentId, Timestamp.from(from), Timestamp.from(to));
        long open = db.queryForObject("SELECT COUNT(*) FROM incidents WHERE environment_id=? AND status='OPEN'",
                Long.class, environmentId);
        return new Overview(environmentId, Instant.now(), open, recent);
    }

    record Overview(Long environmentId, Instant queriedAt, long openIncidents, List<Window> recentWindows) {}

    private void check(Long id){if(!environments.existsById(id))throw PrometheusClient.error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND","Environment not found");}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static Long elapsed(Instant start,Instant end){return start==null||end==null?null:Duration.between(start,end).toMillis();}
    private record Configuration(boolean enabled,int interval,Instant updated,Instant nextRunAt){}
    record Settings(@NotNull Boolean enabled,@NotNull @Min(30) @Max(3600) Integer intervalSeconds){}
    record State(Long environmentId,boolean enabled,int intervalSeconds,String executionStatus,String modelStatus,Instant lastSuccessfulAt,Instant updatedAt,Instant nextRunAt){}
    record Window(String jobId,Instant windowStart,Instant windowEnd,String status,String reason,Double score,AnalysisProtocol.Decision decision,
                  String modelVersion,Instant createdAt,Instant startedAt,Instant finishedAt,Long queueWaitMs,Long executionMs,String incidentId,List<Location> locations){}
    record Location(int rank,AnalysisProtocol.CandidateKind kind,String service,String source,String target){}
}
